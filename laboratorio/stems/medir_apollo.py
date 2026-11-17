"""
Mede o Apollo (ONNX) num caso controlado: referência sem perdas → MP3 em taxa baixa → Apollo,
comparando com a referência (SI-SDR e distância log-espectral, LSD; menor LSD é melhor).
Os blocos de 5 s com sobreposição e transição linear são os mesmos que os clientes usam.

    python laboratorio/stems/medir_apollo.py --modelo <apollo.onnx> --referencia <wav 44,1 kHz> --kbps 64
"""
import argparse
import json
import os
import subprocess
import tempfile
import time

import numpy as np
import onnxruntime as ort
import soundfile as sf

TAXA = 44100


def blocos(sessao, x, bloco=220500, corte=0.0, mistura=1.0):
    passo = bloco // 2
    n = x.shape[1]
    saida = np.zeros_like(x)
    pesos = np.zeros(n)
    transicao = bloco // 10
    for inicio in range(0, max(1, n - passo), passo):
        fim = min(n, inicio + bloco)
        pedaco = np.zeros((2, bloco), np.float32)
        pedaco[:, :fim - inicio] = x[:, inicio:fim]
        y = sessao.run(["restaurado"], {"audio": pedaco[None], "corte": np.array([corte], np.float32),
                                        "mistura": np.array([mistura], np.float32)})[0][0][:, :fim - inicio]
        p = np.ones(fim - inicio)
        if inicio > 0:
            p[:transicao] = np.linspace(0, 1, transicao)
        if fim < n:
            p[-transicao:] = np.linspace(1, 0, transicao)
        saida[:, inicio:fim] += y * p
        pesos[inicio:fim] += p
    return saida / np.maximum(pesos, 1e-9)


def si_sdr(ref, est):
    ref = ref.reshape(-1); est = est.reshape(-1)
    alfa = np.dot(est, ref) / (np.dot(ref, ref) + 1e-12)
    alvo = alfa * ref
    return 10 * np.log10(np.sum(alvo ** 2) / (np.sum((est - alvo) ** 2) + 1e-12))


def lsd(ref, est, n=2048):
    def espectro(x):
        quadros = np.lib.stride_tricks.sliding_window_view(x, n)[::n // 2] * np.hanning(n)
        return np.log10(np.abs(np.fft.rfft(quadros, axis=-1)) ** 2 + 1e-10)
    total = []
    for c in range(ref.shape[0]):
        a, b = espectro(ref[c]), espectro(est[c])
        total.append(np.mean(np.sqrt(np.mean((a - b) ** 2, axis=-1))))
    return float(np.mean(total))



def por_banda(ref, est, taxa=TAXA):
    """SI-SDR abaixo de 4 kHz (onde o MP3 preserva) e energia relativa acima de 12 kHz (onde ele corta)."""
    from numpy.fft import irfft, rfft
    def filtrar(x, de, ate):
        X = rfft(x, axis=-1)
        f = np.fft.rfftfreq(x.shape[-1], 1 / taxa)
        X[..., (f < de) | (f >= ate)] = 0
        return irfft(X, n=x.shape[-1], axis=-1)
    graves = si_sdr(filtrar(ref, 0, 4000), filtrar(est, 0, 4000))
    agudos = 10 * np.log10(np.sum(filtrar(est, 12000, taxa / 2) ** 2) / (np.sum(filtrar(ref, 12000, taxa / 2) ** 2) + 1e-12) + 1e-12)
    return {"siSdrAbaixoDe4k": float(graves), "energiaAcimaDe12kDb": float(agudos)}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--modelo", nargs="+", required=True)
    p.add_argument("--referencia", required=True)
    p.add_argument("--kbps", type=int, default=64)
    p.add_argument("--segundos", type=float, default=15)
    p.add_argument("--corte", type=float, nargs="+", default=[-1e6, 8000.0])
    a = p.parse_args()
    ref, taxa = sf.read(a.referencia, dtype="float32", always_2d=True)
    assert taxa == TAXA
    ref = ref.T[:2, : int(a.segundos * TAXA)]
    if ref.shape[0] == 1:
        ref = np.vstack([ref, ref])
    with tempfile.TemporaryDirectory() as d:
        sf.write(os.path.join(d, "ref.wav"), ref.T, TAXA, subtype="FLOAT")
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(d, "ref.wav"), "-c:a", "libmp3lame", "-b:a", f"{a.kbps}k", os.path.join(d, "x.mp3")], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(d, "x.mp3"), "-ar", str(TAXA), "-c:a", "pcm_f32le", os.path.join(d, "x.wav")], check=True)
        mp3, _ = sf.read(os.path.join(d, "x.wav"), dtype="float32", always_2d=True)
    mp3 = mp3.T
    # O MP3 desloca o começo (atraso do codificador): alinha pela correlação.
    atraso = int(np.argmax(np.correlate(mp3[0, :TAXA * 2], ref[0, :TAXA], mode="valid")))
    mp3 = mp3[:, atraso:atraso + ref.shape[1]]
    n = min(mp3.shape[1], ref.shape[1])
    ref, mp3 = ref[:, :n], mp3[:, :n]
    resultado = {"referencia": os.path.basename(a.referencia), "kbps": a.kbps, "segundos": n / TAXA,
                 "mp3": {"siSdr": si_sdr(ref, mp3), "lsd": lsd(ref, mp3), **por_banda(ref, mp3)}}
    for modelo in a.modelo:
        opcoes = ort.SessionOptions()
        opcoes.intra_op_num_threads = max(1, os.cpu_count() - 1)
        sessao = ort.InferenceSession(modelo, opcoes, providers=["CPUExecutionProvider"])
        for corte in a.corte:
            t = time.time()
            est = blocos(sessao, mp3, corte=corte)
            resultado[f"{os.path.basename(modelo)} corte {corte:.0f} Hz"] = {"siSdr": si_sdr(ref, est), "lsd": lsd(ref, est), "segundos": time.time() - t, **por_banda(ref, est)}
    print(json.dumps(resultado, indent=2, default=float))


if __name__ == "__main__":
    main()

