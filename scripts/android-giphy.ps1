param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tarefas = @(':app:assembleSiteDebug')
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'giphy-ambiente.ps1')
$estadoGiphy = Ativar-AmbienteGiphy
try {
    Push-Location (Join-Path $PSScriptRoot '../android')
    try {
        & ./gradlew.bat @Tarefas --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'O build Android falhou. Consulte os diagnósticos acima.' }
    } finally { Pop-Location }
} finally { Restaurar-AmbienteGiphy $estadoGiphy }
