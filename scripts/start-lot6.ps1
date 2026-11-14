& "$PSScriptRoot/start-lot3.ps1"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$provided = $env:MOBILITY_RESERVATION_SECRET
try {
    if (-not $provided) {
        $env:MOBILITY_RESERVATION_SECRET = python -c "import runpy; x=runpy.run_path('scripts/verify-lot2.py'); x['await_services'](); print(x['service_secret'](x['admin_token'](), 'reservation-service'))"
        if ($LASTEXITCODE -ne 0 -or -not $env:MOBILITY_RESERVATION_SECRET) {
            throw 'Unable to obtain the local reservation-service credential.'
        }
    }
    docker compose --profile app up -d --build reservation
    exit $LASTEXITCODE
} finally {
    if (-not $provided) {
        Remove-Item Env:MOBILITY_RESERVATION_SECRET -ErrorAction SilentlyContinue
    }
}
