$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path -Parent $PSScriptRoot) 'secrets'
if (Test-Path -LiteralPath $target) { throw 'secrets already exists; refusing to replace existing keys.' }
New-Item -ItemType Directory -Path $target | Out-Null
$rsa = [System.Security.Cryptography.RSA]::Create(3072)
try {
    [IO.File]::WriteAllText((Join-Path $target 'private.pem'), $rsa.ExportPkcs8PrivateKeyPem())
    [IO.File]::WriteAllText((Join-Path $target 'public.pem'), $rsa.ExportSubjectPublicKeyInfoPem())
    $mailKey = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $rateKey = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    [IO.File]::WriteAllLines((Join-Path $target 'symmetric.env'), @("AUTH_MAIL_KEY=$mailKey", "AUTH_RATE_KEY=$rateKey"))
    Write-Host 'Created secrets/private.pem, public.pem and symmetric.env. Import into deployment secrets; do not commit.'
} finally { $rsa.Dispose() }
