$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$java = "java.exe"
if ($env:JAVA_HOME) {
    $javaHomeJava = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path -LiteralPath $javaHomeJava) {
        $java = $javaHomeJava
    }
}

$sqliteJar = Join-Path $env:USERPROFILE ".m2\repository\org\xerial\sqlite-jdbc\3.49.1.0\sqlite-jdbc-3.49.1.0.jar"
$classpath = Join-Path $projectRoot "target\classes"
$classpath = "$classpath;$sqliteJar"

Set-Location -LiteralPath $projectRoot
while ($true) {
    & $java "-cp" $classpath "kz.zacklayr.tlschat.ChatServer"
    if ($LASTEXITCODE -eq 0) {
        break
    }
    Write-Host "Сервер завершился с кодом $LASTEXITCODE. Перезапуск через 5 секунд..."
    Start-Sleep -Seconds 5
}
