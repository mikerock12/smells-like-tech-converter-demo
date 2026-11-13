# Baixa o SDK MIT fixado e, opcionalmente, compila o plugin de referência do SDK.
param([switch]$CompilarExemplo)

$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
$sdk = Join-Path $raiz 'build/nativo/vst3sdk'
$tag = 'v3.8.1_build_84'
$commit = '3cdf9ca5d1f5b1b21e0a86832aa4abe55607bd96'

if (-not (Test-Path -LiteralPath (Join-Path $sdk '.git'))) {
    git clone --depth 1 --branch $tag https://github.com/steinbergmedia/vst3sdk.git $sdk
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar o SDK VST3.' }
}
$versao = git -C $sdk rev-parse HEAD
if ($LASTEXITCODE -ne 0 -or $versao -ne $commit) {
    throw "A pasta do SDK VST3 precisa estar no commit $commit."
}
git -C $sdk submodule update --init --depth 1 -- base cmake pluginterfaces public.sdk
if ($LASTEXITCODE -ne 0) { throw 'Falha ao baixar os módulos do SDK VST3.' }
$estado = git -C $sdk submodule status -- base cmake pluginterfaces public.sdk
if ($LASTEXITCODE -ne 0 -or ($estado | Where-Object { $_ -match '^[-+U]' })) {
    throw 'Os módulos do SDK VST3 precisam estar nas revisões fixadas pelo SDK.'
}

if ($CompilarExemplo) {
    $build = Join-Path $raiz 'build/nativo/vst3-exemplos'
    $opcoes = @('-DSMTG_ENABLE_VSTGUI_SUPPORT=OFF', '-DSMTG_ENABLE_VST3_HOSTING_EXAMPLES=OFF',
        '-DSMTG_CREATE_PLUGIN_LINK=OFF')
    cmake -S $sdk -B $build -A x64 @opcoes
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao configurar os exemplos VST3.' }
    cmake --build $build --config Release --target adelay --parallel
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao compilar o plugin de referência VST3.' }
}
