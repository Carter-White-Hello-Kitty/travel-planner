$ErrorActionPreference = 'Stop'
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    docker compose -p flag-auth-v020 -f compose.auth.yaml up -d --wait
    if ($LASTEXITCODE -ne 0) { throw 'Docker startup failed' }
    Get-Content -LiteralPath 'scripts/auth-init-test-db.sql' | docker compose -p flag-auth-v020 -f compose.auth.yaml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
    if ($LASTEXITCODE -ne 0) { throw 'Test database preparation failed' }
    Write-Host 'Dedicated travel_planner_auth_test database is ready.'
} finally { Pop-Location }
