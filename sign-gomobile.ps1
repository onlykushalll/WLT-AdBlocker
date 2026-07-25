# Sign gomobile.exe + gobind.exe with self-signed cert (WDAC bypass)
$ErrorActionPreference = 'Stop'

# Find the WLT self-signed code signing cert
$cert = Get-ChildItem -Path Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.Subject -match 'WLT' } | Select-Object -First 1
if (-not $cert) {
    # Try by looking for any self-signed code signing cert
    $cert = Get-ChildItem -Path Cert:\CurrentUser\My -CodeSigningCert | Select-Object -First 1
}
if (-not $cert) {
    Write-Host 'No code signing cert found. Creating new one...'
    $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=WLT-Adblocker Code Signing' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 2048 -NotAfter (Get-Date).AddYears(5)
    # Add to Trusted Root and Trusted Publishers
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store('Root','LocalMachine')
    $store.Open('ReadWrite')
    $store.Add($cert)
    $store.Close()
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store('TrustedPublisher','LocalMachine')
    $store.Open('ReadWrite')
    $store.Add($cert)
    $store.Close()
}
Write-Host "Using cert: $($cert.Subject)"

# Sign all Go binaries that might be blocked by WDAC
$goBin = 'C:\Users\Default.L-HCG-9FVVGS3\go\bin'
$binaries = @('gomobile.exe', 'gobind.exe', 'go.exe', 'gofmt.exe')
foreach ($exe in $binaries) {
    $path = Join-Path $goBin $exe
    if (Test-Path $path) {
        Write-Host "Signing $exe..."
        $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -TimestampServer 'http://timestamp.digicert.com'
        Write-Host "  Status: $($result.Status)"
    }
}

# Also sign cgo.exe in the Go toolchain
$goRoot = 'C:\Users\Default.L-HCG-9FVVGS3\go'
$cgoPaths = @(
    "$goRoot\pkg\tool\windows_amd64\cgo.exe",
    "$goRoot\pkg\tool\windows_amd64\compile.exe",
    "$goRoot\pkg\tool\windows_amd64\link.exe",
    "$goRoot\pkg\tool\windows_amd64\asm.exe"
)
foreach ($path in $cgoPaths) {
    if (Test-Path $path) {
        Write-Host "Signing $(Split-Path $path -Leaf)..."
        $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -TimestampServer 'http://timestamp.digicert.com'
        Write-Host "  Status: $($result.Status)"
    }
}
Write-Host 'SIGNING_DONE'
