$ErrorActionPreference = "Stop"

mvn package -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed"
}

$output = Join-Path (Get-Location) "dist-admin"
$input = Join-Path (Get-Location) "target\admin-app"
if (Test-Path $output) {
    Remove-Item $output -Recurse -Force
}
New-Item -ItemType Directory -Path $input -Force | Out-Null
Copy-Item "target\tls-chat-registrar-jar-with-dependencies.jar" "$input\TLS-Chat-Admin.jar"

jpackage `
    --type app-image `
    --input $input `
    --main-jar "TLS-Chat-Admin.jar" `
    --main-class "kz.zacklayr.tlschat.AdminApp" `
    --name "TLS-Chat-Admin" `
    --dest $output `
    --vendor "Avengers Games" `
    --description "TLS Chat administrator desktop application"

Write-Host "Built: $output\TLS-Chat-Admin\TLS-Chat-Admin.exe"
