param(
    [string]$SourceDir = (Get-Location).Path,
    [string]$OutputDir = (Join-Path (Get-Location).Path "backups"),
    [switch]$UsePostgresDump
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:BACKUP_ENCRYPTION_KEY)) {
    throw "Set BACKUP_ENCRYPTION_KEY to a base64-encoded 32-byte key before creating a backup."
}

$env:BACKUP_SOURCE_DIR = $SourceDir
$env:BACKUP_OUTPUT_DIR = $OutputDir
$classpath = "target/classes;target/dependency/*"

if ($UsePostgresDump) {
    if ([string]::IsNullOrWhiteSpace($env:DATABASE_URL)) {
        throw "Set DATABASE_URL when using -UsePostgresDump."
    }
    & pg_dump $env:DATABASE_URL | java -cp $classpath kz.zacklayr.tlschat.EncryptedBackupApp --stdin
} else {
    java -cp $classpath kz.zacklayr.tlschat.EncryptedBackupApp
}
