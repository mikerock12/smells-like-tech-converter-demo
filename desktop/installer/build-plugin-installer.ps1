<#
.SYNOPSIS
    Publica o plugin em Release e gera o instalador .exe dele.

.DESCRIPTION
    O plugin é um produto separado do aplicativo desktop: tem instalador próprio,
    pasta própria e desinstalação própria. Este script não toca no aplicativo.

.EXAMPLE
    pwsh desktop/installer/build-plugin-installer.ps1
    pwsh desktop/installer/build-plugin-installer.ps1 -Version 0.2.0 -SkipPublish
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [switch]$SkipPublish,
    [switch]$SkipVerify
)

$ErrorActionPreference = "Stop"

$installerDir = $PSScriptRoot
$desktopDir = Split-Path -Parent $installerDir
$project = Join-Path $desktopDir "src\SmellsLikeTech.Converter.Bridge\SmellsLikeTech.Converter.Bridge.csproj"
$payloadDir = Join-Path $desktopDir "artifacts\plugin"
$outputDir = Join-Path $desktopDir "artifacts\installer"
$exeName = "SmellsLikeTechPlugin.exe"

if (-not $Version) {
    $props = Get-Content (Join-Path $desktopDir "Directory.Build.props") -Raw
    if ($props -match "<Version>([^<]+)</Version>") { $Version = $Matches[1] } else { $Version = "0.1.0" }
}

Write-Host "Smells Like Tech Converter — Plugin $Version" -ForegroundColor Yellow

# O plugin trava o próprio executável enquanto roda: sem encerrar, o publish falha.
$rodando = Get-Process -Name ([IO.Path]::GetFileNameWithoutExtension($exeName)) -ErrorAction SilentlyContinue
if ($rodando) {
    Write-Host "-> encerrando o plugin em execução…" -ForegroundColor DarkGray
    $rodando | Stop-Process -Force
    Start-Sleep -Seconds 2
}

if (-not $SkipPublish) {
    Write-Host "-> publicando (Release, self-contained, win-x64)…" -ForegroundColor Cyan
    if (Test-Path $payloadDir) { Remove-Item $payloadDir -Recurse -Force }
    dotnet publish $project -c Release -o $payloadDir --nologo
    if ($LASTEXITCODE -ne 0) { throw "Falha ao publicar o plugin." }
}

$exe = Join-Path $payloadDir $exeName
if (-not (Test-Path $exe)) {
    throw "Payload não encontrado em $payloadDir. Rode sem -SkipPublish."
}

# O ícone da bandeja é a única presença visível do plugin: sem ele o usuário não
# tem como saber que está ligado nem como desligar.
if (-not (Test-Path (Join-Path $payloadDir "Assets\icon.ico"))) {
    throw "Assets\icon.ico ausente no payload: o ícone da bandeja não apareceria."
}

$candidates = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
)
$iscc = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) {
    throw "ISCC.exe não encontrado. Instale com: winget install JRSoftware.InnoSetup"
}

New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

Write-Host "-> compilando o instalador…" -ForegroundColor Cyan
& $iscc "/DAppVersion=$Version" (Join-Path $installerDir "SmellsLikeTechPlugin.iss") | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o instalador do plugin." }

$setup = Join-Path $outputDir "SmellsLikeTechPluginSetup.exe"
$size = [Math]::Round((Get-Item $setup).Length / 1MB, 1)

# Instalação de teste. Já saiu um pacote com arquivo corrompido antes, e isso só
# aparece na hora de instalar de verdade.
if (-not $SkipVerify) {
    Write-Host "-> verificando (instalação de teste, silenciosa)…" -ForegroundColor Cyan
    $destino = Join-Path $env:TEMP "verificacao-plugin-$(Get-Random)"
    & $setup /VERYSILENT /SUPPRESSMSGBOXES /NORESTART "/DIR=$destino" /TASKS="" | Out-Null
    Start-Sleep -Seconds 3

    $instalado = Join-Path $destino $exeName
    if (-not (Test-Path $instalado)) { throw "A instalação de teste não produziu $exeName." }

    Write-Host "-> ligando o plugin instalado e perguntando quem ele é…" -ForegroundColor Cyan
    $processo = Start-Process -FilePath $instalado -PassThru
    try {
        $apresentacao = $null
        foreach ($tentativa in 1..30) {
            Start-Sleep -Seconds 2
            try {
                $apresentacao = Invoke-RestMethod -Uri "http://127.0.0.1:5199/v1/ola" -TimeoutSec 3 `
                    -Headers @{ "Origin" = "https://converter.smellsliketech.com.br" }
                break
            } catch { }
        }

        if (-not $apresentacao) { throw "O plugin instalado não respondeu em 127.0.0.1:5199." }
        if ($apresentacao.papel -ne "plugin") { throw "Resposta inesperada de /v1/ola." }

        $disponiveis = @($apresentacao.operacoes | Where-Object { $_.disponivel }).Count
        Write-Host "   $($apresentacao.maquina.processador) · $disponiveis operações disponíveis" -ForegroundColor DarkGray
    }
    finally {
        if ($processo -and -not $processo.HasExited) { $processo | Stop-Process -Force }
        Start-Sleep -Seconds 1
        & (Join-Path $destino "unins000.exe") /VERYSILENT /SUPPRESSMSGBOXES /NORESTART | Out-Null
        Start-Sleep -Seconds 3
        if (Test-Path $destino) { Remove-Item $destino -Recurse -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "   instalação, execução e desinstalação verificadas." -ForegroundColor DarkGray
}

# Prepara para o site baixar.
#
# O .exe fica fora do controle de versao de proposito: sao 64 MB por versao, e o
# repositorio carregaria todas elas para sempre. E tambem fora de public/: os assets
# do Worker param em 25 MiB, e o site o entrega a partir do bucket R2. Aqui ele so ganha
# a versao no nome, ao lado do original; quem o envia ao R2 e `npm run instaladores:publicar`.
# O JSON em public\baixar, esse sim, e versionado — e o que a pagina de download le para
# mostrar tamanho e conferencia, e o que o envio confere antes de subir o arquivo.
$paraOSite = Join-Path (Split-Path -Parent $desktopDir) "public\baixar"
New-Item -ItemType Directory -Force -Path $paraOSite | Out-Null

$nomePublicado = "SmellsLikeTechPluginSetup-$Version.exe"
$publicado = Join-Path $outputDir $nomePublicado
Copy-Item $setup $publicado -Force

$hash = (Get-FileHash $publicado -Algorithm SHA256).Hash.ToLower()
$metadados = [ordered]@{
    versao    = $Version
    arquivo   = $nomePublicado
    bytes     = (Get-Item $publicado).Length
    sha256    = $hash
    publicado = (Get-Date -Format "yyyy-MM-dd")
}
$metadados | ConvertTo-Json | Set-Content (Join-Path $paraOSite "plugin.json") -Encoding UTF8

Write-Host ""
Write-Host "Instalador do plugin: $setup ($size MB)" -ForegroundColor Green
Write-Host "Pronto para o site: $publicado (public\baixar\plugin.json atualizado)" -ForegroundColor Green
Write-Host "Envie ao R2 com: npm run instaladores:publicar -- --so plugin" -ForegroundColor Green
