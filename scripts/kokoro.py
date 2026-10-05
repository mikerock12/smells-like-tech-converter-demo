"""Prepara ativos oficiais fixados e verificados; nenhuma entrada de usuário é lida."""
import argparse
import hashlib
import json
import pathlib
import re
import shutil
import tarfile
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORK = ROOT / 'outputs/kokoro'
VERSION = '1.13.8'
ASSETS = {
    'model': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2', '4c3052abaa60943a341f193888cf6abd68787dae6ab8ae5c925a706caa247e4e'),
    'webmodel': ('https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2', 'c5f7e2d2caf082bc1d20fb70334a61d99d20b484500aad32e7cf84c128ea3298'),
    'wasm': (f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-wasm-simd-{VERSION}-kokoro-multi-lang-v1_0.tar.bz2', '39180cc8851bc5c4a2b8f47e2546b31f58704169421fd2e2dc06c459b7d367e7'),
    'android': (f'https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar', '633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96'),
}
LICENSES = {
    'GPL-3.0.txt': ('https://raw.githubusercontent.com/csukuangfj/espeak-ng/ed530aa113046142eb5115cf2fc9157854d0ffe1/COPYING', '8ceb4b9ee5adedde47b31e975c1d90c73ad27b6b165a1dcd80c7c545eb65b903'),
    'Sherpa-ONNX.txt': ('https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/LICENSE', 'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'),
    'ONNX-Runtime.txt': ('https://raw.githubusercontent.com/microsoft/onnxruntime/v1.23.2/LICENSE', '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c'),
}
SOURCES = {
    'sherpa-onnx-1.13.8.zip': ('https://github.com/k2-fsa/sherpa-onnx/archive/refs/tags/v1.13.8.zip', 'b63b7613812346f2d1396a3a7f94accd47539e3dadc3ea385ba6474c70ab9897'),
    'espeak-ng-ed530aa.zip': ('https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip', 'e4e262cbe34f7fe21f91f1ba3397f2728e1f30eafbae7853f2b753a9ed13f0dd'),
    'onnxruntime-1.28.2.zip': ('https://github.com/microsoft/onnxruntime/archive/refs/tags/v1.28.2.zip', '0a71177b993c8406e0ae50574e2319bd0f53d3e89857cd9acdde0aea22ba27f1'),
    'onnxruntime-receitas.zip': ('https://github.com/csukuangfj/onnxruntime-libs/archive/bae1313acef164c37e124e2cbbdcad4d01a712db.zip', 'a201f8199f24e304d7e6cc6d3f29a294856fcb763929c9ebc77968cb5efdc43f'),
}


def sources():
    directory = WORK / 'fontes'
    directory.mkdir(parents=True, exist_ok=True)
    inventory = []
    for name, (url, expected) in SOURCES.items():
        path = directory / name
        if not path.exists() or sha(path) != expected:
            partial = path.with_suffix('.partial')
            urllib.request.urlretrieve(url, partial)
            if sha(partial) != expected:
                raise ValueError(f'Fonte inválida: {name}')
            partial.replace(path)
        inventory.append({'name': name, 'url': url, 'sha256': expected, 'bytes': path.stat().st_size})
    with zipfile.ZipFile(WORK / 'kokoro-runtime-fontes-1.13.8.zip', 'w', compression=zipfile.ZIP_STORED) as bundle:
        for name in SOURCES:
            bundle.write(directory / name, name)
        for name in ['LICENSE', 'LICENSING.md', 'docs/kokoro-fontes.md', 'scripts/kokoro.py', 'packages/kokoro/NOTICE.txt']:
            bundle.write(ROOT / name, name)
        bundle.writestr('FONTES.json', json.dumps(inventory, ensure_ascii=False, indent=2))
    print('Fontes oficiais e receitas reunidos com SHA-256 conferido.', flush=True)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def download(key):
    url, expected = ASSETS[key]
    target = WORK / 'downloads' / ('sherpa.aar' if key == 'android' else f'{key}.tar.bz2')
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or sha(target) != expected:
        partial = target.with_suffix('.partial')
        print(f'Kokoro: baixando {key} oficial...', flush=True)
        urllib.request.urlretrieve(url, partial)
        if sha(partial) != expected:
            raise ValueError(f'SHA-256 incorreto: {key}')
        partial.replace(target)
    return target


def model():
    source = WORK / 'kokoro-multi-lang-v1_0'
    archive = download('webmodel')
    if not (source / 'model.onnx').exists():
        with tarfile.open(archive) as tar:
            tar.extractall(WORK, filter='data')
    target = ROOT / 'packages/kokoro/model'
    if (target / 'model.int8.onnx').exists():
        raise ValueError('Assets int8 antigos: preserve packages/kokoro/model em outputs antes de preparar fp32. Não empacotar os dois modelos.')
    target.mkdir(parents=True, exist_ok=True)
    for name in ['model.onnx', 'voices.bin', 'tokens.txt', 'LICENSE']:
        shutil.copy2(source / name, target / name)
    shutil.copytree(source / 'espeak-ng-data', target / 'espeak-ng-data', dirs_exist_ok=True)
    notices = target / 'Licenses'
    notices.mkdir(exist_ok=True)
    for name, (url, expected) in LICENSES.items():
        original = WORK / 'downloads' / name
        if not original.exists() or sha(original) != expected:
            urllib.request.urlretrieve(url, original)
        if sha(original) != expected:
            raise ValueError(f'Licença inválida: {name}')
        shutil.copy2(original, notices / name)
    shutil.copy2(ROOT / 'packages/kokoro/NOTICE.txt', notices / 'NOTICE.txt')
    shutil.copy2(ROOT / 'LICENSING.md', notices / 'LICENSING.md')
    shutil.copy2(ROOT / 'docs/kokoro-fontes.md', notices / 'FONTES.md')
    # Mesmos pesos fp32 em todas as plataformas; o dialeto é explicitamente pt-br.
    return target


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('target', choices=['all', 'web', 'android', 'windows', 'fontes'])
    args = parser.parse_args()
    if args.target == 'fontes':
        sources()
        return
    target = model()
    if args.target in ['all', 'android']:
        if (ROOT / 'android/app/src/main/assets/kokoro/model.int8.onnx').exists():
            raise ValueError('Assets Android int8 antigos: preserve a pasta kokoro em outputs antes de preparar fp32.')
        aar = download('android')
        libs = ROOT / 'android/app/libs'
        libs.mkdir(parents=True, exist_ok=True)
        shutil.copy2(aar, libs / f'sherpa-onnx-{VERSION}.aar')
        shutil.copytree(target, ROOT / 'android/app/src/main/assets/kokoro', dirs_exist_ok=True)
    if args.target in ['all', 'web']:
        # O int8 oficial produziu saídas silenciosas intermitentes no WASM.
        # No navegador usamos o mesmo Kokoro v1, com os pesos oficiais fp32.
        web_source = WORK / 'kokoro-multi-lang-v1_0'
        web_archive = download('webmodel')
        if not (web_source / 'model.onnx').exists():
            with tarfile.open(web_archive) as tar:
                tar.extractall(WORK, filter='data')
        web_target = ROOT / 'packages/kokoro/model-web'
        web_target.mkdir(parents=True, exist_ok=True)
        for name in ['model.onnx', 'voices.bin', 'tokens.txt', 'LICENSE']:
            shutil.copy2(web_source / name, web_target / name)
        shutil.copytree(web_source / 'espeak-ng-data', web_target / 'espeak-ng-data', dirs_exist_ok=True)
        shutil.copytree(target / 'Licenses', web_target / 'Licenses', dirs_exist_ok=True)
        archive = download('wasm')
        directory = f'sherpa-onnx-wasm-simd-{VERSION}-kokoro-multi-lang-v1_0'
        public = ROOT / 'public/motores/kokoro'
        public.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as tar:
            for name in ['sherpa-onnx-tts.js', 'sherpa-onnx-wasm-main-tts.js', 'sherpa-onnx-wasm-main-tts.wasm']:
                matches = [f for f in tar.getmembers() if f.name.endswith('/' + directory + '/' + name) or f.name == directory + '/' + name or f.name == './' + directory + '/' + name]
                if len(matches) != 1:
                    raise ValueError(f'Arquivo do runtime não encontrado: {name}')
                data = tar.extractfile(matches[0]).read()
                if name.endswith('main-tts.js'):
                    # O pacote upstream carrega 380 MB de inglês/chinês automaticamente.
                    # Mantemos o runtime intacto, mas carregamos nosso pacote fp32 via FS.
                    text = data.decode()
                    text, count = re.subn(r'loadPackage\(\{files:.*?remote_package_size:\d+\}\)', '/* Kokoro: FS inicializado pelo runner local. */', text, count=1)
                    if count != 1:
                        raise ValueError('Formato do preload upstream mudou')
                    data = text.encode()
                (public / name).write_bytes(data)
        shutil.copy2(ROOT / 'packages/kokoro/runner.js', public / 'runner.js')
        shutil.copytree(target / 'Licenses', public / 'Licenses', dirs_exist_ok=True)
        shutil.copy2(web_target / 'LICENSE', public / 'LICENSE')
        bundle = WORK / 'kokoro-82m-v1-fp32.zip'
        with zipfile.ZipFile(bundle, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zip:
            for path in sorted(web_target.rglob('*')):
                if path.is_file():
                    info = zipfile.ZipInfo(path.relative_to(web_target).as_posix(), date_time=(2026, 10, 4, 0, 0, 0))
                    info.compress_type = zipfile.ZIP_DEFLATED
                    zip.writestr(info, path.read_bytes(), compresslevel=6)
        files = [{'name': p.relative_to(web_target).as_posix(), 'bytes': p.stat().st_size, 'sha256': sha(p)} for p in sorted(web_target.rglob('*')) if p.is_file()]
        manifest = {
            'version': 'kokoro-82m-v1-fp32-ptbr', 'runtime': VERSION, 'language': 'pt-br',
            'bundle': {'name': bundle.name, 'bytes': bundle.stat().st_size, 'sha256': sha(bundle)},
            'files': files,
            'voices': [{'id': 'pf_dora', 'sid': 42, 'name': 'Dora'}, {'id': 'pm_alex', 'sid': 43, 'name': 'Alex'}, {'id': 'pm_santa', 'sid': 44, 'name': 'Santa'}],
        }
        (public / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f'Kokoro web: pacote {bundle.stat().st_size / 1024**2:.1f} MB, SHA-256 {sha(bundle)}', flush=True)
    print(f'Kokoro {args.target}: preparado com integridade verificada.', flush=True)


if __name__ == '__main__':
    main()
