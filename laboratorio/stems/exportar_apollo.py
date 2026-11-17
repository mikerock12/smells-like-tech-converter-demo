"""
Exporta o Apollo (JusperLee/Apollo, CC BY-SA 4.0: código e pesos) para ONNX, em blocos fixos.

O Apollo restaura música comprimida (MP3/AAC de taxa baixa): STFT de 20 ms (882 pontos, passo
441), 80 bandas e 6 camadas de modelagem por banda e por tempo; a saída é o espectro
restaurado. Para rodar igual no navegador (WASM), no Android e no Windows, a STFT e a ISTFT
entram no grafo como convoluções (janela de Hann × base de Fourier), sem números complexos e
sem o operador STFT. A entrada é um bloco estéreo; o cliente junta os blocos com sobreposição e
transição linear, como já faz com o RoFormer.

O Apollo recria o espectro inteiro, inclusive os graves que o MP3 preserva bem (numa música
limpa, a saída fica a ~17 dB SI-SDR da entrada). Por isso o grafo cruza os dois espectros:
abaixo de `corte` (Hz) fica o original; acima, original + `mistura` × (Apollo − original), com
transição sigmoide de ~1,5 kHz. Com corte −10⁶ e mistura 1, a saída é o Apollo puro (conferido).

    python laboratorio/stems/exportar_apollo.py --codigo build/nativo/laboratorio-stems/apollo-src \
        --pesos build/nativo/laboratorio-stems/pesos/apollo/pytorch_model.bin \
        --destino build/nativo/laboratorio-stems/pesos/apollo/apollo-fp32-5s.onnx

O carregamento usa weights_only=True e libera só as classes de dados que o checkpoint traz
(configuração omegaconf, dicionários e tensores): nenhum código do arquivo é executado.
Confere o grafo contra o PyTorch original (erro máximo) e grava um relatório JSON ao lado.
"""
import argparse
import hashlib
import json
import math
import os
import sys
import time

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

TAXA = 44100


def carregar(codigo, pesos):
    sys.path.insert(0, codigo)
    # A classe base só usa o mixin do Hugging Face para publicar modelos; não é preciso aqui.
    import types
    if "huggingface_hub" not in sys.modules:
        stub = types.ModuleType("huggingface_hub")
        stub.PyTorchModelHubMixin = type("PyTorchModelHubMixin", (), {"__init_subclass__": classmethod(lambda cls, **k: None)})
        sys.modules["huggingface_hub"] = stub
    from look2hear.models.apollo import Apollo  # noqa: E402
    import omegaconf
    import typing
    from collections import OrderedDict, defaultdict
    torch.serialization.add_safe_globals([
        omegaconf.dictconfig.DictConfig, omegaconf.listconfig.ListConfig, omegaconf.base.ContainerMetadata,
        omegaconf.base.Metadata, omegaconf.nodes.AnyNode, typing.Any, OrderedDict, defaultdict, dict, list, int,
        torch.torch_version.TorchVersion,
    ])
    dados = torch.load(pesos, map_location="cpu", weights_only=True)
    estado = dados.get("state_dict", dados)
    estado = {k.split("audio_model.", 1)[1] if k.startswith("audio_model.") else k: v for k, v in estado.items()}
    import look2hear.models.apollo as fonte

    def atencao_sem_mT(self, input):
        # A mesma conta do Roformer.forward, com transpose no lugar de .mT (o exportador ONNX não conhece aten::mT).
        B, _, T = input.shape
        weight = self.weight(self.input_drop(self.input_norm(input))).reshape(B, self.num_head, self.hidden_size * 3, T).transpose(-1, -2)
        Q, K, V = torch.split(weight, self.hidden_size, dim=-1)
        Q_rot = self._add_rotary_sequence(Q)
        K_rot = self._add_rotary_sequence(K)
        saida = F.scaled_dot_product_attention(Q_rot.contiguous(), K_rot.contiguous(), V.contiguous(), dropout_p=self.attention_drop, is_causal=self.causal)
        saida = saida.transpose(-1, -2).reshape(B, -1, T)
        output = self.output(saida) + input
        gate, z = self.MLP(output).chunk(2, dim=1)
        output = output + self.MLP_output(F.silu(gate) * z)
        return output, (K_rot, V)

    fonte.Roformer.forward = atencao_sem_mT
    modelo = Apollo(sr=TAXA, win=20, feature_dim=256, layer=6)
    faltando = modelo.load_state_dict({k: v for k, v in estado.items() if k in modelo.state_dict()}, strict=True)
    print("pesos carregados", faltando)
    return modelo.eval()


class ApolloEmBlocos(nn.Module):
    """O mesmo cálculo do Apollo.forward, com a STFT e a ISTFT como convoluções reais."""

    def __init__(self, modelo, amostras):
        super().__init__()
        self.m = modelo
        n = modelo.win  # 882
        h = modelo.stride  # 441
        b = modelo.enc_dim  # 442
        self.n, self.h, self.b, self.amostras = n, h, b, amostras
        janela = torch.hann_window(n, dtype=torch.float64)
        t = torch.arange(n, dtype=torch.float64)
        k = torch.arange(b, dtype=torch.float64)
        angulo = 2 * math.pi * k[:, None] * t[None, :] / n
        # Análise: [2B, 1, N] — reais e imaginárias da rfft do quadro janelado.
        analise = torch.cat([torch.cos(angulo) * janela, -torch.sin(angulo) * janela], 0)
        self.register_buffer("analise", analise.unsqueeze(1).float())
        # Síntese: irfft (DC e Nyquist com peso 1, o resto 2) × janela, para ConvTranspose1d.
        peso = torch.full((b,), 2.0, dtype=torch.float64)
        peso[0] = 1
        peso[-1] = 1
        sintese = torch.cat([torch.cos(angulo) * peso[:, None], -torch.sin(angulo) * peso[:, None]], 0) * janela / n
        self.register_buffer("sintese", sintese.unsqueeze(1).float())
        # O envelope (soma de janela² sobreposta) sai no próprio grafo: qualquer duração serve.
        self.register_buffer("janela2", (janela ** 2).float().reshape(1, 1, n))
        self.register_buffer("frequencias", (k * TAXA / n).float().reshape(1, b, 1))

    def forward(self, audio, corte, mistura):  # [1, 2, amostras], [1] Hz, [1] de 0 a 1
        m = self.m
        canais = audio.shape[1]
        amostras = audio.shape[2]
        x = audio.reshape(canais, 1, amostras)
        x = F.pad(x, (self.n // 2, self.n // 2), mode="reflect")
        espectro = F.conv1d(x, self.analise, stride=self.h)  # [C, 2B, T]
        real, imag = espectro[:, :self.b], espectro[:, self.b:]
        caracteristicas = []
        inicio = 0
        for i, largura in enumerate(m.band_width):
            r = real[:, inicio:inicio + largura]
            im = imag[:, inicio:inicio + largura]
            potencia = torch.sqrt((r * r + im * im).sum(1, keepdim=True) + m.eps)
            junto = torch.cat([r / potencia, im / potencia, torch.log(potencia)], 1)
            caracteristicas.append(m.BN[i](junto))
            inicio += largura
        f = torch.stack(caracteristicas, 1)  # [C, nband, N, T]
        f = m.net(f)
        reais, imags = [], []
        for i, largura in enumerate(m.band_width):
            ri = m.output[i](f[:, i]).view(canais, 2, largura, -1)
            reais.append(ri[:, 0])
            imags.append(ri[:, 1])
        restaurado = torch.cat([torch.cat(reais, 1), torch.cat(imags, 1)], 1)  # [C, 2B, T]
        peso = torch.sigmoid((self.frequencias - corte.reshape(1, 1, 1)) / 375.0) * mistura.reshape(1, 1, 1)
        peso = torch.cat([peso, peso], 1)
        saida = espectro + peso * (restaurado - espectro)
        y = F.conv_transpose1d(saida, self.sintese, stride=self.h)  # [C, 1, (T-1)h + N]
        envelope = F.conv_transpose1d(torch.ones_like(saida[:1, :1]), self.janela2, stride=self.h)
        y = y[:, 0, self.n // 2:self.n // 2 + amostras] / envelope[:, 0, self.n // 2:self.n // 2 + amostras].clamp_min(1e-11)
        return y.reshape(1, canais, amostras)


def sinal_de_teste(amostras, semente=3):
    """Acorde com ataques e um pouco de ruído, comprimido em MP3 pelo FFmpeg quando houver."""
    g = np.random.default_rng(semente)
    t = np.arange(amostras) / TAXA
    x = np.zeros((2, amostras))
    for f, a in ((110, 0.3), (220.5, 0.2), (330, 0.15), (987, 0.05), (3520, 0.02)):
        x += a * np.sin(2 * np.pi * f * t + g.uniform(0, 6.28, (2, 1)))
    x *= np.exp(-2.5 * np.mod(t, 0.5))
    x += 0.01 * g.standard_normal((2, amostras))
    return torch.tensor(x[None], dtype=torch.float32)


def sha256(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--codigo", required=True)
    p.add_argument("--pesos", required=True)
    p.add_argument("--destino", required=True)
    p.add_argument("--segundos", type=float, default=5)
    a = p.parse_args()
    amostras = int(round(a.segundos * TAXA))
    torch.set_num_threads(max(1, os.cpu_count() - 1))
    modelo = carregar(a.codigo, a.pesos)
    blocos = ApolloEmBlocos(modelo, amostras).eval()
    x = sinal_de_teste(amostras)
    with torch.no_grad():
        t0 = time.time()
        original = modelo(x)
        t1 = time.time()
        nosso = blocos(x, torch.tensor([-1e6]), torch.tensor([1.0]))
        cruzado = blocos(x, torch.tensor([24000.0]), torch.tensor([1.0]))
        t2 = time.time()
    erro = float((original - nosso).abs().max())
    print(f"PyTorch original × convoluções: erro máximo {erro:.3g} ({t1 - t0:.2f} s e {t2 - t1:.2f} s para {a.segundos} s)")
    assert erro < 1e-3, "a reescrita da STFT não bate com o original"
    erro_identidade = float((cruzado - x).abs().max())
    print(f"corte acima de Nyquist devolve a entrada: erro máximo {erro_identidade:.3g}")
    assert erro_identidade < 1e-4
    # O tracer cai (access violation) com o MKLDNN ligado nesta versão do PyTorch no Windows.
    torch.backends.mkldnn.enabled = False
    curto = sinal_de_teste(TAXA)  # rastreia com 1 s; o eixo do tempo fica livre no grafo
    with torch.no_grad():
        torch.onnx.export(blocos, (curto, torch.tensor([6000.0]), torch.tensor([1.0])), a.destino, input_names=["audio", "corte", "mistura"],
                          output_names=["restaurado"], opset_version=17, do_constant_folding=True, dynamo=False,
                          dynamic_axes={"audio": {2: "amostras"}, "restaurado": {2: "amostras"}})
    import onnxruntime as ort
    opcoes = ort.SessionOptions()
    opcoes.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    sessao = ort.InferenceSession(a.destino, opcoes, providers=["CPUExecutionProvider"])
    t3 = time.time()
    saida = sessao.run(["restaurado"], {"audio": x.numpy(), "corte": np.array([-1e6], np.float32), "mistura": np.array([1], np.float32)})[0]
    t4 = time.time()
    erro_onnx = float(np.abs(saida - original.numpy()).max())
    print(f"ONNX Runtime × PyTorch original: erro máximo {erro_onnx:.3g} ({t4 - t3:.2f} s)")
    relatorio = {
        "modelo": "JusperLee/Apollo", "licenca": "CC-BY-SA-4.0", "pesosOriginais": os.path.basename(a.pesos), "sha256Original": sha256(a.pesos),
        "segundos": a.segundos, "amostras": amostras, "erroReescrita": erro, "erroOnnx": erro_onnx, "erroIdentidade": erro_identidade,
        "entradas": {"audio": "[1, 2, amostras] float32 a 44,1 kHz", "corte": "[1] Hz", "mistura": "[1] de 0 a 1"},
        "arquivo": os.path.basename(a.destino), "bytes": os.path.getsize(a.destino), "sha256": sha256(a.destino),
        "segundosOnnx": t4 - t3, "segundosPyTorch": t1 - t0, "opset": 17,
    }
    with open(os.path.splitext(a.destino)[0] + ".json", "w", encoding="utf-8") as f:
        json.dump(relatorio, f, indent=2)
    print(json.dumps(relatorio, indent=2))


if __name__ == "__main__":
    main()
