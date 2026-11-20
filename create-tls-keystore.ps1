$ErrorActionPreference = "Stop"

$output = Join-Path (Get-Location) "tls"
New-Item -ItemType Directory -Path $output -Force | Out-Null
$p12 = Join-Path $output "tls-chat.p12"
$password = "change-me-to-a-long-random-password"
$domain = if ($args.Count -gt 0) { $args[0] } else { "localhost" }

keytool -genkeypair -alias tls-chat -keyalg RSA -keysize 2048 -validity 365 `
    -storetype PKCS12 -keystore $p12 -storepass $password -keypass $password `
    -dname "CN=$domain, OU=Avengers Games, O=Avengers Games, C=KZ" `
    -ext "SAN=dns:$domain" -noprompt

Write-Host "Keystore: $p12"
Write-Host "Password: $password"
Write-Host "Replace the sample password and set:"
Write-Host "TLS_KEYSTORE=$p12"
Write-Host "TLS_PASSWORD=<the password you chose>"
