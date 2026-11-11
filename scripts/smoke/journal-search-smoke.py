"""Local smoke: real HTTP + MySQL fixtures + automatic ES worker.
Uses the local development database by default; never targets production.
No direct ES writes or manual indexing are used during the test flow.
"""
import http.cookiejar
import json
import os
import subprocess
import time
import uuid
from urllib.request import Request, build_opener, HTTPCookieProcessor, urlopen
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit

BASE = os.environ.get('SMOKE_URL', 'http://localhost:8085')
url = urlsplit(BASE)
if url.scheme != 'http' or url.hostname != 'localhost' or not url.port:
    raise SystemExit('Only http://localhost:<port> is allowed')
DB = os.environ.get('SMOKE_DB_NAME', 'travel_planner')
if DB not in ('travel_planner', 'travel_planner_test'):
    raise SystemExit('SMOKE_DB_NAME must be travel_planner or travel_planner_test')
CONTAINER = os.environ.get('SMOKE_MYSQL_CONTAINER', 'travel-planner-mysql-1')
PASSWORD = os.environ.get('DB_PASSWORD', 'local-only-change-me')
ES_INDEX = os.environ.get('SMOKE_ES_INDEX', 'travel-posts-java')
if not ES_INDEX.replace('-', '').replace('_', '').isalnum():
    raise SystemExit('SMOKE_ES_INDEX must be a local Elasticsearch index name')
ES_URL = 'http://127.0.0.1:19200/' + ES_INDEX
RUN = uuid.uuid4().hex
USERS = []
POSTS = []
TRIPS = []


def sql(statement):
    command = ['docker', 'exec', '-i', '-e', 'MYSQL_PWD=' + PASSWORD,
               CONTAINER, 'mysql', '-u', 'travel', '-N', '-B', DB]
    result = subprocess.run(command, input=statement, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError('Test fixture SQL failed (details not printed to avoid credentials)')
    return result.stdout.strip()


class Client:
    def __init__(self):
        self.http = build_opener(HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.token = None

    def request(self, method, path, data=None, auth=True):
        headers = {'Accept': 'application/json'}
        if auth and self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if method != 'GET':
            _, csrf = self.request('GET', '/auth/csrf', auth=False)
            headers.update({'X-XSRF-TOKEN': csrf['data']['token'],
                            'Origin': 'http://localhost:3000',
                            'X-Requested-With': 'XMLHttpRequest',
                            'Content-Type': 'application/json'})
        req = Request(BASE + path, method=method, headers=headers,
                      data=None if data is None else json.dumps(data).encode())
        try:
            response = self.http.open(req, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.load(response)


def expect(status, result, expected):
    if status != expected:
        raise AssertionError(f'Expected {expected}, got {status}: {result.get("message")}')


def account():
    client = Client()
    email = 'journal-smoke-' + uuid.uuid4().hex + '@example.com'
    credentials = {'email': email, 'password': 'JournalSmoke!2026'}
    status, body = client.request('POST', '/auth/register', credentials, auth=False)
    expect(status, body, 201)
    user = body['data']['user']['id']
    # Refuse fixture/cleanup operations if the HTTP app points at a different database.
    stored = sql(f"SELECT id FROM users WHERE email='{email}';")
    if stored != str(user):
        raise RuntimeError(
            f'Backend at {BASE} is not connected to {DB}; check its spring.datasource.url '
            f'or set SMOKE_DB_NAME to the matching local database'
        )
    USERS.append(int(user))
    status, body = client.request('POST', '/auth/login', credentials, auth=False)
    expect(status, body, 200)
    client.token = body['data']['accessToken']
    return client, int(user)


def trip(owner, status):
    # owner/status are controlled test values, never arbitrary user input.
    assert status in ('Planning', 'Ongoing', 'Completed', 'Cancelled')
    name = 'journal-smoke-' + uuid.uuid4().hex
    trip_id = int(sql(f"INSERT INTO trips(user_id,name,status,day_count) VALUES({int(owner)},'{name}','{status}',1); SELECT LAST_INSERT_ID();"))
    TRIPS.append(trip_id)
    return trip_id


def wait_for_post(client, marker, post_id):
    start = time.monotonic()
    while time.monotonic() - start < 60:
        status, body = client.request('GET', '/travel-posts?' + urlencode({'keyword': marker}))
        if status == 503:
            print('RETRY transient search 503 while index becomes ready', flush=True)
            time.sleep(1)
            continue
        expect(status, body, 200)
        matches = [p for p in body['data']['items'] if p['id'] == post_id]
        if matches:
            return matches[0], time.monotonic() - start
        time.sleep(1)
    raise AssertionError('Post not searchable within 60s: check worker enabled and logs')


def main():
    assert sql('SELECT DATABASE();') == DB
    deadline = time.monotonic() + 60
    while True:
        try:
            status, body = Client().request('GET', '/auth/csrf', auth=False)
            expect(status, body, 200)
            break
        except URLError:
            if time.monotonic() > deadline:
                raise
            time.sleep(1)
    first, owner = account()
    second, other = account()
    print('PASS register/login: two real users', flush=True)
    completed = trip(owner, 'Completed')
    cancelled = trip(owner, 'Cancelled')
    ongoing = trip(owner, 'Ongoing')
    planning = trip(owner, 'Planning')
    other_trip = trip(other, 'Completed')
    marker = 'smoke' + RUN
    for trip_id in (completed, cancelled):
        status, body = first.request('POST', '/travel-posts', {
            'trip_id': trip_id, 'content': '海边散步 ' + marker,
            'photos': ['https://example.com/smoke-beach.jpg']})
        expect(status, body, 201)
        post_id = body['data']['id']
        POSTS.append(post_id)
        found, seconds = wait_for_post(first, marker, post_id)
        assert found['trip_id'] == trip_id and found['photos'] == ['https://example.com/smoke-beach.jpg']
        print(f'PASS publish -> AUTOMATIC worker -> search ({seconds:.1f}s)', flush=True)
    status, body = first.request('GET', '/travel-posts?' + urlencode({'keyword': marker, 'pageSize': 1}))
    expect(status, body, 200)
    assert len(body['data']['items']) == 1 and body['data']['pagination']['totalItems'] == 2
    status, body = second.request('GET', '/travel-posts?' + urlencode({'keyword': marker}))
    expect(status, body, 200)
    assert {p['id'] for p in body['data']['items']} == set(POSTS)
    # Listing with no keyword is shared too; test index contains only this run's fixtures.
    status, body = second.request('GET', '/travel-posts')
    expect(status, body, 200)
    assert set(POSTS) <= {p['id'] for p in body['data']['items']}
    status, body = first.request('GET', '/travel-posts?keyword=absent' + RUN)
    expect(status, body, 200)
    assert body['data']['items'] == []
    for path in ('/travel-posts?page=0', '/travel-posts?page=abc'):
        status, body = first.request('GET', path)
        expect(status, body, 400)
    status, body = Client().request('GET', '/travel-posts')
    expect(status, body, 401)
    for trip_id, expected in ((ongoing, 409), (planning, 409), (other_trip, 403)):
        status, body = first.request('POST', '/travel-posts', {'trip_id': trip_id})
        expect(status, body, expected)
    print('PASS pagination, empty results, shared visibility, publish ownership, 400/401/403/409', flush=True)
    print('PASS real HTTP journal smoke; no manual index synchronization', flush=True)


def cleanup():
    # Remove only records created by this execution; retain real users and unrelated rows.
    if not USERS:
        return
    for user in USERS:
        sql(f'''DELETE j FROM journal_search_jobs j JOIN travel_posts p ON p.id=j.post_id
          JOIN trips t ON t.id=p.trip_id WHERE t.user_id={user};
          DELETE p FROM travel_posts p JOIN trips t ON t.id=p.trip_id WHERE t.user_id={user};
          DELETE FROM trips WHERE user_id={user};
          DELETE FROM auth_refresh_tokens WHERE session_id IN (SELECT id FROM auth_sessions WHERE user_id={user});
          DELETE FROM auth_sessions WHERE user_id={user};
          DELETE FROM users WHERE id={user};''')
    # The worker has no deletion event for fixture SQL cleanup. Remove only our
    # published post IDs from the local index after their database rows are gone.
    for post_id in POSTS:
        request = Request(ES_URL + '/_doc/' + str(post_id), method='DELETE')
        try:
            with urlopen(request, timeout=5) as response:
                if response.status not in (200, 202):
                    raise RuntimeError('ES fixture cleanup failed')
        except HTTPError as error:
            if error.code != 404:
                raise RuntimeError('ES fixture cleanup failed') from error
    print('CLEANUP smoke accounts, trips, posts, queue rows and ES documents', flush=True)


if __name__ == '__main__':
    try:
        main()
    finally:
        cleanup()
