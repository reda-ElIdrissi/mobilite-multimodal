$provided = $env:MOBILITY_PRICING_SECRET
try {
    if (-not $provided) {
        if (-not $env:MOBILITY_ADMIN_PASSWORD) {
            throw 'Set MOBILITY_ADMIN_PASSWORD or supply your configured MOBILITY_PRICING_SECRET.'
        }
        docker compose --profile app up -d keycloak
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $ready = $false
        for ($attempt = 0; $attempt -lt 40; $attempt++) {
            try {
                $null = Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 -Uri 'http://127.0.0.1:8088/realms/mobility/.well-known/openid-configuration'
                $ready = $true
                break
            } catch {
                Start-Sleep -Seconds 2
            }
        }
        if (-not $ready) { throw 'The local Keycloak realm did not become ready.' }
        python scripts/configure-lot4.py
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
        $env:MOBILITY_PRICING_SECRET = python -c "import runpy; x=runpy.run_path('scripts/verify-lot2.py'); print(x['service_secret'](x['admin_token'](),'pricing-service'))"
        if ($LASTEXITCODE -ne 0 -or -not $env:MOBILITY_PRICING_SECRET) {
            throw 'Unable to obtain the local pricing-service credential.'
        }
    }

    & "$PSScriptRoot/start-lot6.ps1"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    docker compose --profile app up -d --build notification
    exit $LASTEXITCODE
} finally {
    if (-not $provided) {
        Remove-Item Env:MOBILITY_PRICING_SECRET -ErrorAction SilentlyContinue
    }
}
