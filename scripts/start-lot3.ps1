if (-not $env:MOBILITY_ADMIN_PASSWORD -and -not $env:MOBILITY_BFF_SECRET) {
    throw 'Set MOBILITY_ADMIN_PASSWORD for the local Keycloak bootstrap, or supply your configured MOBILITY_BFF_SECRET.'
}

docker compose --profile app up -d --build api-gateway
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$provided = $env:MOBILITY_BFF_SECRET
try {
    if (-not $provided) {
        # Capture the development credential in memory without printing it.
        $env:MOBILITY_BFF_SECRET = python -c "import runpy; x=runpy.run_path('scripts/verify-lot2.py'); x['await_services'](); print(x['service_secret'](x['admin_token']()))"
        if ($LASTEXITCODE -ne 0 -or -not $env:MOBILITY_BFF_SECRET) {
            throw 'Unable to obtain the local search-bff client credential.'
        }
    }
    docker compose --profile app up -d --build search-bff
    exit $LASTEXITCODE
} finally {
    if (-not $provided) {
        Remove-Item Env:MOBILITY_BFF_SECRET -ErrorAction SilentlyContinue
    }
}
