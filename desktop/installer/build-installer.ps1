<#
.SYNOPSIS
    Publica o Smells Like Tech Converter em Release e gera o instalador .exe.

.EXAMPLE
    pwsh desktop/installer/build-installer.ps1
    pwsh desktop/installer/build-installer.ps1 -Version 0.2.0 -SkipPublish
    pwsh desktop/installer/build-installer.ps1 -ApenasPublicarNoSite
#>
[CmdletBinding()]
param(
    [string]$Version = "",
    [switch]$SkipPublish,
    [switch]$SkipVerify,
    # Nao gera nada: pega o instalador que ja esta em artifacts e o prepara para o
    # site (copia com a versao no nome e regrava o app.json). Recompilar produziria
    # outro arquivo, com outro SHA-256, e a versao que ja foi verificada e guardada
    # em backup deixaria de ser a que esta no ar.
    [switch]$ApenasPublicarNoSite
)

$ErrorActionPreference = "Stop"

$installerDir = $PSScriptRoot
$desktopDir = Split-Path -Parent $installerDir
$project = Join-Path $desktopDir "src\SmellsLikeTech.Converter.Desktop\SmellsLikeTech.Converter.Desktop.csproj"
$payloadDir = Join-Path $desktopDir "artifacts\app"
$outputDir = Join-Path $desktopDir "artifacts\installer"

# Versão: parâmetro > <Version> do Directory.Build.props
if (-not $Version) {
    $props = Get-Content (Join-Path $desktopDir "Directory.Build.props") -Raw
    if ($props -match "<Version>([^<]+)</Version>") { $Version = $Matches[1] } else { $Version = "0.1.0" }
}

Write-Host "Smells Like Tech Converter $Version" -ForegroundColor Yellow

if ($ApenasPublicarNoSite) { $SkipPublish = $true; $SkipVerify = $true }

if (-not $SkipPublish) {
    Write-Host "-> publicando (Release, self-contained, win-x64)…" -ForegroundColor Cyan
    if (Test-Path $payloadDir) { Remove-Item $payloadDir -Recurse -Force }
    dotnet publish $project -c Release -o $payloadDir --nologo
    if ($LASTEXITCODE -ne 0) { throw "Falha ao publicar o aplicativo." }
}

if (-not (Test-Path (Join-Path $payloadDir "SmellsLikeTechConverter.exe"))) {
    throw "Payload não encontrado em $payloadDir. Rode sem -SkipPublish."
}

# O .pri carrega o XAML compilado; sem ele o app publicado não abre.
if (-not (Test-Path (Join-Path $payloadDir "SmellsLikeTechConverter.pri"))) {
    throw "SmellsLikeTechConverter.pri ausente no payload: verifique EnableMsixTooling no csproj."
}

if (-not $ApenasPublicarNoSite) {
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
& $iscc "/DAppVersion=$Version" (Join-Path $installerDir "SmellsLikeTechConverter.iss") | Select-Object -Last 6
if ($LASTEXITCODE -ne 0) { throw "Falha ao compilar o instalador." }
}

$setup = Join-Path $outputDir "SmellsLikeTechConverterSetup.exe"
if (-not (Test-Path $setup)) { throw "Instalador nao encontrado em $setup." }
$size = [Math]::Round((Get-Item $setup).Length / 1MB, 1)

# Instalação de teste: já saiu um pacote com arquivo corrompido (hash SHA-256 diferente
# do original), e isso só aparece na hora de instalar. Melhor descobrir aqui.
if (-not $SkipVerify) {
    Write-Host "-> verificando o instalador (instalação de teste)…" -ForegroundColor Cyan
    $prova = Join-Path ([System.IO.Path]::GetTempPath()) "slt-verificacao-$([Guid]::NewGuid().ToString('N'))"
    $registro = "$prova.log"

    $processo = Start-Process -FilePath $setup -Wait -PassThru -ArgumentList @(
        "/VERYSILENT", "/CURRENTUSER", "/SUPPRESSMSGBOXES", "/NORESTART", "/DIR=$prova", "/LOG=$registro")

    $instalados = if (Test-Path $prova) { (Get-ChildItem $prova -Recurse -File).Count } else { 0 }

    if ($processo.ExitCode -ne 0 -or $instalados -lt 100) {
        if (Test-Path $registro) { Get-Content $registro -Tail 8 | ForEach-Object { Write-Host "   $_" -ForegroundColor DarkYellow } }
        throw "O instalador não passou na verificação (código $($processo.ExitCode), $instalados arquivos). Recompile."
    }

    $desinstalador = Join-Path $prova "unins000.exe"
    if (Test-Path $desinstalador) {
        Start-Process -FilePath $desinstalador -Wait -ArgumentList "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART" | Out-Null
        Start-Sleep -Seconds 2
    }

    Remove-Item $prova -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item $registro -Force -ErrorAction SilentlyContinue
    Write-Host "   instalou e desinstalou $instalados arquivos" -ForegroundColor DarkGray
}

# Prepara para o site baixar.
#
# O .exe fica fora do controle de versao de proposito: sao dezenas de MB por versao,
# e o repositorio carregaria todas elas para sempre. E tambem fora de public/: os assets
# do Worker param em 25 MiB, e o site o entrega a partir do bucket R2. Aqui ele so ganha
# a versao no nome, ao lado do original; quem o envia ao R2 e `npm run instaladores:publicar`.
# O JSON em public/baixar, esse sim, e versionado — e o que a pagina de download le para
# mostrar tamanho e conferencia, e o que o envio confere antes de subir o arquivo.
$paraOSite = Join-Path (Split-Path -Parent $desktopDir) "public/baixar"
New-Item -ItemType Directory -Force -Path $paraOSite | Out-Null

$nomePublicado = "SmellsLikeTechConverterSetup-$Version.exe"
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
$metadados | ConvertTo-Json | Set-Content (Join-Path $paraOSite "app.json") -Encoding UTF8

Write-Host ""
Write-Host "OK: $setup ($size MB)" -ForegroundColor Green
Write-Host "Pronto para o site: $publicado (public/baixar/app.json atualizado)" -ForegroundColor Green
Write-Host "Envie ao R2 com: npm run instaladores:publicar -- --so app" -ForegroundColor Green
