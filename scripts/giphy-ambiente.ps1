param(
    [string]$Comando,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Argumentos = @()
)

# Configuração local fora do Git. Valores só passam pelo ambiente dos builds filhos.
function Proteger-ConfiguracaoGiphy {
    param([string]$Texto)
    # Ofuscação de distribuição, não armazenamento secreto: o cliente precisa decifrar.
    $chave = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $mascara = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
    $nonce = [Security.Cryptography.RandomNumberGenerator]::GetBytes(12)
    $dados = [Text.Encoding]::UTF8.GetBytes($Texto)
    $cifra = [byte[]]::new($dados.Length)
    $tag = [byte[]]::new(16)
    $aes = [Security.Cryptography.AesGcm]::new($chave, 16)
    try {
        $aes.Encrypt($nonce, $dados, $cifra, $tag)
        for ($i = 0; $i -lt 32; $i++) { $chave[$i] = $chave[$i] -bxor $mascara[$i] }
        return 'v1.' + ((@($nonce, $cifra, $tag, $chave, $mascara) | ForEach-Object { [Convert]::ToBase64String($_) }) -join '.')
    } finally { $aes.Dispose(); [Array]::Clear($dados); [Array]::Clear($chave); [Array]::Clear($mascara) }
}

function Ativar-AmbienteGiphy {
    param([string]$Arquivo = (Join-Path $env:USERPROFILE '.smellsliketech/catalogos/giphy.dpapi.json'))
    $anteriores = @{}
    if (-not (Test-Path -LiteralPath $Arquivo)) { return $anteriores }
    try {
        $registros = @(Get-Content -LiteralPath $Arquivo -Raw | ConvertFrom-Json)
        $registro = $registros | Where-Object { $_.escopo -eq 'android-figurinhas' -and $_.tipo -eq 'API' } | Select-Object -Last 1
        if ($null -eq $registro) { return $anteriores }
        $segredo = ConvertTo-SecureString -String $registro.chaveCriptografada
    } catch { throw 'Não foi possível ler a configuração GIPHY criptografada.' }
    $ponteiro = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($segredo)
    try {
        $texto = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ponteiro)
        if ($texto -notmatch '^[A-Za-z0-9_-]{16,256}$') { throw 'Configuração GIPHY inválida.' }
        $protegido = Proteger-ConfiguracaoGiphy $texto
        foreach ($variavel in @('GIPHY_ANDROID_FIGURINHAS_API_KEY','GIPHY_ANDROID_EDITOR_API_KEY','GIPHY_WINDOWS_EDITOR_API_KEY','NEXT_PUBLIC_GIPHY_EDITOR_API_KEY')) {
            $anteriores[$variavel] = [Environment]::GetEnvironmentVariable($variavel, 'Process')
            [Environment]::SetEnvironmentVariable($variavel, $protegido, 'Process')
        }
    } finally {
        $texto = $null
        $protegido = $null
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ponteiro)
        $segredo.Dispose()
    }
    return $anteriores
}

function Restaurar-AmbienteGiphy {
    param([hashtable]$Anteriores)
    foreach ($variavel in $Anteriores.Keys) {
        [Environment]::SetEnvironmentVariable($variavel, $Anteriores[$variavel], 'Process')
    }
}

if ($Comando) {
    $estadoGiphy = Ativar-AmbienteGiphy
    try {
        & $Comando @Argumentos
        $codigoGiphy = $LASTEXITCODE
        if ($null -eq $codigoGiphy) { $codigoGiphy = 0 }
    } finally { Restaurar-AmbienteGiphy $estadoGiphy }
    exit $codigoGiphy
}
