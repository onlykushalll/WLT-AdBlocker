# Sign Go binaries — use CurrentUser store (no admin needed)
$ErrorActionPreference = 'Stop'

$cert = Get-ChildItem -Path Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.Subject -match 'WLT' } | Select-Object -First 1
if (-not $cert) {
    Write-Host 'Creating new code signing cert...'
    $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=WLT-Adblocker Code Signing' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 2048 -NotAfter (Get-Date).AddYears(5)
}
Write-Host "Using cert: $($cert.Subject)"

# Add to CurrentUser Trusted Root and Trusted Publishers (no admin needed)
$stores = @('Root', 'TrustedPublisher')
foreach ($storeName in $stores) {
    $store = New-Object System.Security.Cryptography.X509Certificates.X509Store($storeName, 'CurrentUser')
    $store.Open('ReadWrite')
    $existing = $store.Certificates | Where-Object { $_.Thumbprint -eq $cert.Thumbprint }
    if (-not $existing) {
        $store.Add($cert)
        Write-Host "  Added to CurrentUser\$storeName"
    } else {
        Write-Host "  Already in CurrentUser\$storeName"
    }
    $store.Close()
}

# Sign ALL Go binaries
$goBin = 'C:\Users\Default.L-HCG-9FVVGS3\go\bin'
$binaries = @('gomobile.exe', 'gobind.exe', 'go.exe', 'gofmt.exe')
foreach ($exe in $binaries) {
    $path = Join-Path $goBin $exe
    if (Test-Path $path) {
        $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -TimestampServer 'http://timestamp.digicert.com'
        Write-Host "Signed $exe : $($result.Status)"
    }
}

# Sign Go toolchain binaries
$goRoot = 'C:\Users\Default.L-HCG-9FVVGS3\go'
$toolPaths = @(
    "$goRoot\pkg\tool\windows_amd64\cgo.exe",
    "$goRoot\pkg\tool\windows_amd64\compile.exe",
    "$goRoot\pkg\tool\windows_amd64\link.exe",
    "$goRoot\pkg\tool\windows_amd64\asm.exe",
    "$goRoot\pkg\tool\windows_amd64\buildid.exe",
    "$goRoot\pkg\tool\windows_amd64\pack.exe"
)
foreach ($path in $toolPaths) {
    if (Test-Path $path) {
        $result = Set-AuthenticodeSignature -FilePath $path -Certificate $cert -TimestampServer 'http://timestamp.digicert.com'
        Write-Host "Signed $(Split-Path $path -Leaf) : $($result.Status)"
    }
}
Write-Host 'SIGN2_DONE'
