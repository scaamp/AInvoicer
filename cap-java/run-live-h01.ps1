# Uruchamia test LIVE SAP H01 (CAP RemoteService). Dane czyta z cap-java\.env.live (poza gitem).
# Format .env.live:  H01_URL=https://<host-origin>   H01_USER=...   H01_PASSWORD=...
$envFile = Join-Path $PSScriptRoot '.env.live'
if (-not (Test-Path $envFile)) { throw "Brak $envFile (H01_URL, H01_USER, H01_PASSWORD)" }
$cfg = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*[^#=\s]+=' } | ForEach-Object {
    $k, $v = $_ -split '=', 2
    $cfg[$k.Trim()] = $v.Trim().Trim('"')
}
foreach ($k in 'H01_URL', 'H01_USER', 'H01_PASSWORD') { if (-not $cfg[$k]) { throw "Brak $k w .env.live" } }

$dest = @{ name = 'AINV_H01'; url = $cfg.H01_URL; username = $cfg.H01_USER; password = $cfg.H01_PASSWORD } | ConvertTo-Json -Compress
$env:destinations  = "[$dest]"
$env:AINV_H01_LIVE = 'true'
try {
    Push-Location $PSScriptRoot
    mvn -pl srv -am verify '-Dcds.cds.skipVersionCheck=true' '-Dainv.excludedGroups=none' '-Dtest=SapInvoiceRemoteAdapterLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
} finally {
    Pop-Location
    Remove-Item Env:destinations, Env:AINV_H01_LIVE -ErrorAction SilentlyContinue
}
