"""Run migration checks in a fresh Docker project. No Google keys, shared data or secret output.

Requires Docker and Java 17+. Execute from the repository root.
Logs and a sanitized summary are written to ignored .validation/.
Each invocation creates its own volume; containers are stopped in finally, volumes retained.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[2]
os.chdir(root)
if (root / '.env').exists():
    raise SystemExit('Run in a delivery checkout without .env; real credentials must not be loaded')
out = root / '.validation'
out.mkdir(exist_ok=True)
# Deliberately do not inherit credential, provider, database or proxy settings.
env = {key: os.environ[key] for key in ['PATH', 'HOME', 'JAVA_HOME', 'TMPDIR', 'LANG'] if key in os.environ}
project = 'tp-backend-validation-' + uuid.uuid4().hex[:10]
env.update(VALIDATION_DB_PASSWORD=secrets.token_hex(24), VALIDATION_ROOT_PASSWORD=secrets.token_hex(24),
           DB_HOST='localhost', DB_PORT='54316', DB_USERNAME='travel', REDIS_HOST='localhost',
           REDIS_PORT='57389', REDIS_DATABASE='1', REDIS_TEST_DATABASE='1', SMTP_HOST='localhost',
           SMTP_PORT='11135', AUTH_DEV_KEYS='true', COOKIE_SECURE='false',
           SMOKE_URL='http://localhost:8087', MAILPIT_URL='http://localhost:18135', SMOKE_ALLOW_WRITES='1')
env['DB_PASSWORD'] = env['VALIDATION_DB_PASSWORD']
env['SPRING_DATASOURCE_URL'] = 'jdbc:mysql://localhost:54316/travel_planner_test?serverTimezone=UTC&characterEncoding=UTF-8'
env['SPRING_CONFIG_IMPORT'] = 'optional:classpath:/validation-only.properties'
env.update(JOURNAL_ES_URL='http://127.0.0.1:19216', JOURNAL_ES_INDEX='validation-'+uuid.uuid4().hex[:10],
           JOURNAL_SEARCH_WORKER_ENABLED='false', JOURNAL_SEARCH_SYNC_DELAY_MS='500')
env['VALIDATION_COMPOSE_PROJECT'] = project
env['VALIDATION_COMPOSE_FILE'] = str(root / 'compose.validation.yaml')
compose = ['docker', 'compose', '-p', project, '-f', 'compose.validation.yaml']
summary = {'project': project, 'checks': [], 'real_google': 'not executed; no keys supplied',
           'maps_real': 'pending API enablement', 'containers': 'stopped in finally; isolated volume retained'}
app = None

def run(name, command, input_text=None):
    with (out / (name + '.log')).open('w') as log:
        result = subprocess.run(command, env=env, text=True, input=input_text, stdout=log, stderr=subprocess.STDOUT)
    summary['checks'].append({'name': name, 'exit_code': result.returncode})
    print(f'{name}: ' + ('PASS' if result.returncode == 0 else 'FAIL (see ignored local log)'), flush=True)
    if result.returncode:
        raise RuntimeError(name + ' failed')

def sql(name, text):
    run(name, compose + ['exec', '-T', 'mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names'], text)

try:
    run('docker-start', compose + ['up', '-d', '--wait'])
    schema = (root / 'src/main/resources/schema.sql').read_text()
    legacy = schema.split('-- Additive, repeatable upgrade')[0].replace('    destination VARCHAR(255) NULL,\n', '')
    sql('schema-new', 'CREATE DATABASE validation_new; USE validation_new;\n' + schema + '\n' + schema)
    sql('schema-legacy', 'CREATE DATABASE validation_legacy; USE validation_legacy;\n' + legacy +
        "\nINSERT INTO users (email,password_hash) VALUES ('schema-check@example.com','not-a-login-hash');\n"
        "INSERT INTO trips (user_id,name,status,day_count,start_date) VALUES (LAST_INSERT_ID(),'Keep existing trip','Planning',1,'2026-12-01');\n" +
        schema + '\n' + schema +
        "\nSELECT COUNT(*) FROM trips WHERE name='Keep existing trip' AND destination IS NULL;\n"
        "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trips' AND COLUMN_NAME='destination';\n")
    assert (out / 'schema-legacy.log').read_text().splitlines()[-2:] == ['1', '1'], 'Legacy rows or column not preserved'
    run('maven-clean-verify', ['./mvnw', '--batch-mode', 'clean', 'verify',
                               '-Djournal.integration.es-url=http://127.0.0.1:19216'])
    suites = [ET.parse(p).getroot() for p in (root / 'target/surefire-reports').glob('TEST-*.xml')]
    summary['maven'] = {key: sum(int(s.get(key, 0)) for s in suites) for key in ['tests', 'failures', 'errors', 'skipped']}
    jars = [p for p in (root / 'target').glob('*.jar')]
    assert len(jars) == 1
    env['JOURNAL_SEARCH_WORKER_ENABLED'] = 'true'
    server_log = (out / 'http-server.log').open('w')
    app = subprocess.Popen(['java', '-jar', str(jars[0]), '--server.port=8087'], env=env,
                           stdout=server_log, stderr=subprocess.STDOUT)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(120):
        if app.poll() is not None:
            raise RuntimeError('Isolated HTTP server exited')
        try:
            with opener.open('http://localhost:8087/auth/csrf', timeout=1):
                break
        except OSError:
            time.sleep(0.5)
    else:
        raise RuntimeError('Isolated HTTP server not ready')
    run('auth-http-smoke', [sys.executable, 'scripts/run-smokes.py', '--ci'])
    run('manual-http-smoke', [sys.executable, 'scripts/live/manual-plan-smoke.py'])
    run('journal-http-regression', [sys.executable, 'scripts/validation/local-http-regression.py'])
except Exception as error:
    summary['error_type'] = type(error).__name__
    print('Validation incomplete; inspect .validation/ locally. No credentials printed.', flush=True)
finally:
    if app is not None:
        app.terminate()
        try: app.wait(timeout=20)
        except subprocess.TimeoutExpired:
            app.kill(); app.wait()
    with (out / 'docker-stop.log').open('w') as log:
        stopped = subprocess.run(compose + ['stop'], env=env, stdout=log, stderr=subprocess.STDOUT)
    summary['stop_exit_code'] = stopped.returncode
    (out / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
if 'error_type' in summary or summary['stop_exit_code']:
    raise SystemExit(1)
