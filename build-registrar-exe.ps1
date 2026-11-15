$ErrorActionPreference = "Stop"

mvn package -DskipTests
if ($LASTEXITCODE -ne 0) {
    throw "Maven build failed"
}

$output = Join-Path (Get-Location) "dist"
$input = Join-Path (Get-Location) "target\registrar-app"
if (Test-Path $output) {
    Remove-Item $output -Recurse -Force
}
New-Item -ItemType Directory -Path $input -Force | Out-Null
Copy-Item "target\tls-chat-registrar-jar-with-dependencies.jar" "$input\TLS-Chat-Registrar.jar"

jpackage `
    --type app-image `
    --input $input `
    --main-jar "TLS-Chat-Registrar.jar" `
    --main-class "kz.zacklayr.tlschat.RegistrarApp" `
    --name "TLS-Chat-Registrar" `
    --dest $output `
    --vendor "Avengers Games" `
    --description "TLS Chat registrar desktop application"

Write-Host "Built: $output\TLS-Chat-Registrar\TLS-Chat-Registrar.exe"
