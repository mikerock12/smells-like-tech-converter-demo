$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '../scripts/giphy-ambiente.ps1')
$variaveis = @('GIPHY_ANDROID_FIGURINHAS_API_KEY','GIPHY_ANDROID_EDITOR_API_KEY','GIPHY_WINDOWS_EDITOR_API_KEY','NEXT_PUBLIC_GIPHY_EDITOR_API_KEY')
$ambienteOriginal = @{}
foreach ($nome in $variaveis) { $ambienteOriginal[$nome] = [Environment]::GetEnvironmentVariable($nome, 'Process') }
$pasta = Join-Path ([IO.Path]::GetTempPath()) ('slt-giphy-teste-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $pasta | Out-Null
try {
    $arquivo = Join-Path $pasta 'configuracao.dpapi.json'
    $ficticia = 'chave-ficticia-apenas-para-teste-1234'
    $segredo = ConvertTo-SecureString -String $ficticia -AsPlainText -Force
    try { $criptografada = ConvertFrom-SecureString -SecureString $segredo } finally { $segredo.Dispose() }
    @(@{escopo='android-figurinhas';tipo='API';chaveCriptografada=$criptografada}) | ConvertTo-Json | Set-Content -LiteralPath $arquivo -Encoding utf8
    [Environment]::SetEnvironmentVariable('NEXT_PUBLIC_GIPHY_EDITOR_API_KEY', 'configuracao-anterior', 'Process')
    $antes = Ativar-AmbienteGiphy -Arquivo $arquivo
    try {
        if ($antes.Count -ne 4) { throw 'Quatro destinos precisam ser configurados.' }
        foreach ($nome in $variaveis) {
            if (-not [Environment]::GetEnvironmentVariable($nome, 'Process').StartsWith('v1.')) { throw 'Os destinos não receberam a mesma configuração.' }
        }
    } finally { Restaurar-AmbienteGiphy $antes }
    if ($env:NEXT_PUBLIC_GIPHY_EDITOR_API_KEY -ne 'configuracao-anterior') { throw 'Ambiente anterior não foi restaurado.' }
    if ((Ativar-AmbienteGiphy -Arquivo (Join-Path $pasta 'ausente.json')).Count -ne 0) { throw 'Ausência de arquivo não deve modificar o ambiente.' }
    @(@{escopo='android-figurinhas';tipo='SDK';chaveCriptografada=$criptografada}) | ConvertTo-Json | Set-Content -LiteralPath $arquivo -Encoding utf8
    if ((Ativar-AmbienteGiphy -Arquivo $arquivo).Count -ne 0) { throw 'Uma chave SDK não deve ser usada como API.' }
    Set-Content -LiteralPath $arquivo -Value 'conteudo-invalido' -Encoding utf8
    $recusou = $false
    try { Ativar-AmbienteGiphy -Arquivo $arquivo | Out-Null } catch { $recusou = $_.Exception.Message -eq 'Não foi possível ler a configuração GIPHY criptografada.' }
    if (-not $recusou) { throw 'Configuração inválida precisa falhar sem revelar conteúdo.' }
    Write-Output 'Configuração fictícia: quatro destinos, restauração, ausência, tipo SDK e erro seguro aprovados.'
} finally {
    Restaurar-AmbienteGiphy $ambienteOriginal
    $destino = [IO.Path]::GetFullPath($pasta)
    $raiz = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if (-not $destino.StartsWith($raiz, [StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($destino)).StartsWith('slt-giphy-teste-')) { throw 'Destino de limpeza inválido.' }
    Remove-Item -LiteralPath $destino -Recurse -Force
}
