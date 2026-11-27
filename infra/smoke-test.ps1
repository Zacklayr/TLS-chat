param(
    [Parameter(Mandatory = $true)]
    [string]$BaseUrl
)

$ErrorActionPreference = "Stop"
$BaseUrl = $BaseUrl.TrimEnd('/')

function Get-Endpoint([string]$Path) {
    return Invoke-WebRequest -UseBasicParsing -Uri ($BaseUrl + $Path)
}

function Get-ResponseText($Response) {
    if ($Response.Content -is [byte[]]) {
        return [Text.Encoding]::UTF8.GetString($Response.Content)
    }
    return [string]$Response.Content
}

$health = Get-Endpoint "/health"
if ($health.StatusCode -ne 200 -or $health.Content -notmatch '"status"\s*:\s*"ok"') {
    throw "Health check failed: HTTP $($health.StatusCode)"
}

$manifest = Get-Endpoint "/manifest.webmanifest"
$manifestText = Get-ResponseText $manifest
if ($manifest.StatusCode -ne 200 -or $manifestText -notmatch "TLS Chat") {
    throw "Manifest check failed: HTTP $($manifest.StatusCode)"
}

$serviceWorker = Get-Endpoint "/sw.js"
$serviceWorkerText = Get-ResponseText $serviceWorker
if ($serviceWorker.StatusCode -ne 200 -or $serviceWorkerText -notmatch "tls-chat-shell-v2") {
    throw "Service worker check failed: HTTP $($serviceWorker.StatusCode)"
}

$icon = Get-Endpoint "/icons/tls-chat.svg"
if ($icon.StatusCode -ne 200 -or $icon.Headers["Content-Type"] -notmatch "image/svg\+xml") {
    throw "Icon check failed: HTTP $($icon.StatusCode)"
}

$root = Get-Endpoint "/"
$rootText = Get-ResponseText $root
$clientFeaturesOk = ($rootText -match "RTCPeerConnection") -and ($rootText -match "/api/calls") -and ($rootText -match "navigator\.serviceWorker") -and ($rootText -match "typing")
if (-not $clientFeaturesOk) {
    throw "Client feature check failed: WebRTC/PWA/typing code is missing from the published page"
}
$securityHeadersOk = ($root.StatusCode -eq 200) -and ($root.Headers["X-Content-Type-Options"] -eq "nosniff") -and ($root.Headers["X-Frame-Options"] -eq "DENY") -and ($root.Headers["Content-Security-Policy"] -match "frame-ancestors 'none'")
if (-not $securityHeadersOk) {
    throw "Security header check failed: HTTP $($root.StatusCode)"
}

try {
    Get-Endpoint "/api/chats" | Out-Null
    throw "Protected chats endpoint unexpectedly allowed anonymous access"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
}

try {
    Get-Endpoint "/api/users" | Out-Null
    throw "Protected user directory unexpectedly allowed anonymous access"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
}

try {
    Get-Endpoint "/api/calls/ice-servers" | Out-Null
    throw "Protected ICE endpoint unexpectedly allowed anonymous access"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
}

$admin = Get-Endpoint "/admin"
$adminText = Get-ResponseText $admin
if ($admin.StatusCode -ne 200 -or $adminText -notmatch "TLS Chat") {
    throw "Admin page check failed: HTTP $($admin.StatusCode)"
}

try {
    Get-Endpoint "/photos/marsel/unknown.png" | Out-Null
    throw "Private photo endpoint unexpectedly allowed anonymous access"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 401) { throw }
}

Write-Output "TLS-Chat smoke test passed for $BaseUrl"
