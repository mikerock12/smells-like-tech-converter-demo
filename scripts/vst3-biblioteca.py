"""
Biblioteca de VST3 gratuitos e de código aberto do Converter para Windows (aplicativo e plugin).

Baixa cada plugin do lançamento oficial no GitHub (versão e SHA-256 fixados), extrai o bundle
de 64 bits sem executar instalador nenhum (7-Zip para .zip, innoextract para Inno Setup),
empacota cada bundle num .zip próprio, calcula tamanho e SHA-256 e traz o texto da licença.

    python scripts/vst3-biblioteca.py

Saídas (fora do Git):
    build/nativo/vst3-biblioteca/pacotes/<id>/<Bundle>.vst3   bundles prontos (os pré-instalados vão no instalador)
    build/nativo/vst3-biblioteca/zips/<id>-<versão>-win64.zip os extras, para o R2 em modelos/vst3/
    build/nativo/vst3-biblioteca/biblioteca.json              tamanho e SHA-256 de cada .zip
Licenças (no Git): desktop/installer/Licenses/vst3/<id>-LICENSE.txt

Os extras são servidos pelo próprio site (R2, /modelos/vst3/...) e conferidos pelo SHA-256
no aplicativo. Os fontes de cada plugin ficam no repositório oficial, na mesma versão
(campo "fonte" do catálogo), como pede a GPL-3.0 dos que a usam.
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(RAIZ, "build", "nativo", "vst3-biblioteca")
SETEZIP = r"C:\Program Files\7-Zip\7z.exe"
INNOEXTRACT = os.path.join(BASE, "ferramentas", "innoextract.exe")

PLUGINS = [
    # id, repositório, versão, arquivo do lançamento, SHA-256 do arquivo, extração, bundle dentro da extração, licença
    ("openriffbox", "dlujic/open-riff-box", "v0.9.1", "OpenRiffBox-windows-x64.zip",
     "53b2482acd6b1afe6574136943c465e69f47a49fced2a634ab4d09ea4ab3dbf4", "zip", "OpenRiffBox.vst3", "GPL-3.0"),
    ("chowcentaur", "jatinchowdhury18/KlonCentaur", "v1.4.0", "ChowCentaur-Win-1.4.0.exe",
     "3ff707e3e49e7cac0bb0819219eefcc6ed59b281246b43fc0465c0fb1d198048", "inno", "code$GetDir$VST3_64/ChowCentaur.vst3", "BSD-3-Clause"),
    ("ts-m1n3", "GuitarML/TS-M1N3", "v1.2.0", "TS-M1N3-Win-1.2.0.exe",
     "71b9c02884721c98a849f76231b6cb137cbbeb2c6c807f33987072e2050652ad", "inno", "code$GetDir$VST3_64/TS-M1N3.vst3", "GPL-3.0"),
    ("chowtape", "jatinchowdhury18/AnalogTapeModel", "v2.11.4", "ChowTapeModel-Win-64bit-2.11.4.exe",
     "8ba6e91196067244837877d9ca4a8e4a1f5bfad60cf33fa699c8a5b41f1211bf", "inno", "code$GetDir$VST3_64/CHOWTapeModel.vst3", "GPL-3.0"),
    ("chowmatrix", "Chowdhury-DSP/ChowMatrix", "v1.3.0", "ChowMatrix-Win-1.3.0.exe",
     "b1e2451ae66362bf82112e77fccc48a1233780b6cbfb58fe1a588b17a77456c5", "inno", "code$GetDir$VST3_64/ChowMatrix.vst3", "BSD-3-Clause"),
    ("dragonfly-room", "michaelwillis/dragonfly-reverb", "3.2.10", "dragonfly-reverb-3.2.10-win64.zip",
     "ca5f35f9dcd9a33f06490d3c92f04061a1a798cb81257d5ca5851bdda0824c66", "zip", "dragonfly-reverb-3.2.10/DragonflyRoomReverb.vst3", "GPL-3.0"),
    ("dragonfly-plate", "michaelwillis/dragonfly-reverb", "3.2.10", "dragonfly-reverb-3.2.10-win64.zip",
     "ca5f35f9dcd9a33f06490d3c92f04061a1a798cb81257d5ca5851bdda0824c66", "zip", "dragonfly-reverb-3.2.10/DragonflyPlateReverb.vst3", "GPL-3.0"),
    ("dragonfly-hall", "michaelwillis/dragonfly-reverb", "3.2.10", "dragonfly-reverb-3.2.10-win64.zip",
     "ca5f35f9dcd9a33f06490d3c92f04061a1a798cb81257d5ca5851bdda0824c66", "zip", "dragonfly-reverb-3.2.10/DragonflyHallReverb.vst3", "GPL-3.0"),
    ("dragonfly-er", "michaelwillis/dragonfly-reverb", "3.2.10", "dragonfly-reverb-3.2.10-win64.zip",
     "ca5f35f9dcd9a33f06490d3c92f04061a1a798cb81257d5ca5851bdda0824c66", "zip", "dragonfly-reverb-3.2.10/DragonflyEarlyReflections.vst3", "GPL-3.0"),
    ("surge-fx", "surge-synthesizer/releases-xt", "1.3.4", "surge-xt-win64-1.3.4-pluginsonly.zip",
     "564e162c560af07ad4ed47fe1bfcd827cf97a575de30d06c48249aad2e7c35e6", "zip", "Surge XT Effects.vst3", "GPL-3.0"),
]
# Onde está o LICENSE de cada projeto (o surge publica os binários num repositório e o código em outro).
LICENCAS = {"surge-synthesizer/releases-xt": ("surge-synthesizer/surge", "release_xt_1.3.4")}


def sha256(caminho):
    h = hashlib.sha256()
    with open(caminho, "rb") as f:
        for bloco in iter(lambda: f.read(1 << 20), b""):
            h.update(bloco)
    return h.hexdigest()


def baixar(repo, versao, arquivo, esperado):
    pasta = os.path.join(BASE, "baixados")
    os.makedirs(pasta, exist_ok=True)
    destino = os.path.join(pasta, arquivo)
    if not os.path.exists(destino) or sha256(destino) != esperado:
        url = f"https://github.com/{repo}/releases/download/{versao}/{arquivo}"
        print("baixando", url)
        urllib.request.urlretrieve(url, destino)
    if sha256(destino) != esperado:
        sys.exit(f"{arquivo}: SHA-256 não confere com o fixado")
    return destino


def extrair(arquivo, tipo):
    pasta = os.path.join(BASE, "extraidos", os.path.splitext(os.path.basename(arquivo))[0])
    if os.path.isdir(pasta) and os.listdir(pasta):
        return pasta
    os.makedirs(pasta, exist_ok=True)
    if tipo == "zip":
        subprocess.run([SETEZIP, "x", "-y", f"-o{pasta}", arquivo], check=True, stdout=subprocess.DEVNULL)
    else:
        subprocess.run([INNOEXTRACT, "-s", "-d", pasta, arquivo], check=True)
    return pasta


def licenca(repo, versao, ident):
    origem, ref = LICENCAS.get(repo, (repo, versao))
    destino = os.path.join(RAIZ, "desktop", "installer", "Licenses", "vst3", f"{ident}-LICENSE.txt")
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    for nome in ("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "LICENSE.GPL3"):
        try:
            with urllib.request.urlopen(f"https://raw.githubusercontent.com/{origem}/{ref}/{nome}") as r:
                texto = r.read().decode("utf-8", "replace")
            with open(destino, "w", encoding="utf-8", newline="\n") as f:
                f.write(f"{ident} — fontes em https://github.com/{origem}/tree/{ref}\n\n{texto}")
            return
        except Exception:
            continue
    sys.exit(f"{ident}: licença não encontrada em {origem}@{ref}")


def main():
    resultado = {}
    for ident, repo, versao, arquivo, esperado, tipo, interno, spdx in PLUGINS:
        pasta = extrair(baixar(repo, versao, arquivo, esperado), tipo)
        bundle = os.path.join(pasta, *interno.split("/"))
        nome = os.path.basename(bundle)
        alvo = os.path.join(BASE, "pacotes", ident, nome)
        shutil.rmtree(os.path.dirname(alvo), ignore_errors=True)
        shutil.copytree(bundle, alvo)
        os.makedirs(os.path.join(BASE, "zips"), exist_ok=True)
        z = os.path.join(BASE, "zips", f"{ident}-{versao.lstrip('v')}-win64.zip")
        with zipfile.ZipFile(z, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as saida:
            for raiz, _, arquivos in os.walk(alvo):
                for a in sorted(arquivos):
                    completo = os.path.join(raiz, a)
                    info = zipfile.ZipInfo(os.path.relpath(completo, os.path.dirname(alvo)).replace(os.sep, "/"), (2026, 1, 1, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    with open(completo, "rb") as f:
                        saida.writestr(info, f.read())
        licenca(repo, versao, ident)
        resultado[ident] = {"pacote": nome, "versao": versao.lstrip("v"), "licenca": spdx, "zip": os.path.basename(z), "bytes": os.path.getsize(z), "sha256": sha256(z)}
        print(ident, resultado[ident]["bytes"], resultado[ident]["sha256"])
    with open(os.path.join(BASE, "biblioteca.json"), "w", encoding="utf-8") as f:
        json.dump(resultado, f, indent=2, ensure_ascii=False)


if __name__ == "__main__":
    main()
