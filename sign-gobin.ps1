# Sign all exe files in the gobin directory
$ErrorActionPreference = 'Stop'
$cert = Get-ChildItem -Path Cert:\CurrentUser\My -CodeSigningCert | Where-Object { $_.Subject -match 'WLT' } | Select-Object -First 1
if (-not $cert) {
    $cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject 'CN=WLT-Adblocker Code Signing' -CertStoreLocation 'Cert:\CurrentUser\My' -KeyUsage DigitalSignature -KeyAlgorithm RSA -KeyLength 2048 -NotAfter (Get-Date).AddYears(5)
}
Write-Host "Using cert: $($cert.Subject)"
$gobin = 'C:\Users\Default.L-HCG-9FVVGS3\OneDrive\Desktop\Adblocker\gobin'
Get-ChildItem $gobin -Filter *.exe | ForEach-Object {
    $result = Set-AuthenticodeSignature -FilePath $_.FullName -Certificate $cert -TimestampServer 'http://timestamp.digicert.com'
    Write-Host "Signed $($_.Name) : $($result.Status)"
}
Write-Host 'SIGN_GOBIN_DONE'
