param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')

function Get-Endpoint([string]$Path) {
    return Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $Path)
}

$health = Get-Endpoint "/health"
if ($health.StatusCode -ne 200 -or $health.Content -notmatch '"status"\s*:\s*"ok"') {
    throw "Health check failed: HTTP $($health.StatusCode)"
}

$manifest = Get-Endpoint "/manifest.webmanifest"
if ($manifest.StatusCode -ne 200 -or $manifest.Content -notmatch "TLS Chat") {
    throw "Manifest check failed: HTTP $($manifest.StatusCode)"
}

$serviceWorker = Get-Endpoint "/sw.js"
if ($serviceWorker.StatusCode -ne 200 -or $serviceWorker.Content -notmatch "tls-chat-shell-v2") {
    throw "Service worker check failed: HTTP $($serviceWorker.StatusCode)"
}

$icon = Get-Endpoint "/icons/tls-chat.svg"
if ($icon.StatusCode -ne 200 -or $icon.Headers["Content-Type"] -notmatch "image/svg\+xml") {
    throw "Icon check failed: HTTP $($icon.StatusCode)"
}

try {
    Get-Endpoint "/photos/marsel/unknown.png" | Out-Null
    throw "Private photo endpoint unexpectedly allowed anonymous access"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
}

Write-Output "TLS-Chat smoke test passed for $BaseUrl"
