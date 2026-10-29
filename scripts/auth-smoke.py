"""Run against the local app and Mailpit only. Does not print credentials/tokens."""
import http.cookiejar
import json
import os
import re
import time
import urllib.request
import urllib.error
import uuid
base = os.environ.get("SMOKE_URL", "http://localhost:18086")
if not base.startswith("http://localhost:"):
    raise SystemExit("This smoke script is restricted to localhost")
jar = http.cookiejar.CookieJar()
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf_token = None
def call(path, body=None, token=None, expected=200):
    global csrf_token
    if body is not None:
        csrf_token = call('/auth/csrf')['data']['token']
    headers = {"Origin": "http://localhost:3000"}
    if body is not None:
        headers.update({"Content-Type": "application/json", "X-Requested-With": "XMLHttpRequest", "X-XSRF-TOKEN": csrf_token})
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        response = client.open(request)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, (path, response.status)
    return json.load(response)
for attempt in range(40):
    try:
        csrf_token = call("/auth/csrf")["data"]["token"]
        break
    except (OSError, AssertionError):
        time.sleep(0.5)
else:
    raise SystemExit("Local backend did not become ready")
email = "smoke-" + uuid.uuid4().hex + "@example.com"
password = "LocalSmokePassword2026!"
call("/auth/register", {"email": email, "password": password}, expected=201)
login = call("/auth/login", {"email": email, "password": password})["data"]
assert isinstance(login['user']['id'], int), 'Team user IDs must be numeric'
call("/auth/me", token=login["accessToken"])
call("/auth/refresh", {})
call("/auth/request-reset-pwd", {"email": email}, expected=202)
reset_token = None
for attempt in range(30):
    listing = json.load(urllib.request.urlopen("http://localhost:18025/api/v1/messages"))
    for message in listing["messages"]:
        if any(person.get("Address") == email for person in message.get("To", [])):
            detail = json.load(urllib.request.urlopen("http://localhost:18025/api/v1/message/" + message["ID"]))
            match = re.search(r"#token=([A-Za-z0-9_-]{43})", detail.get("Text", ""))
            if match:
                reset_token = match.group(1)
                break
    if reset_token:
        break
    time.sleep(0.5)
assert reset_token, "Reset email did not arrive in local Mailpit"
call("/auth/reset-pwd", {"token": reset_token, "newPassword": "NewLocalSmokePassword2026!"})
call("/auth/me", token=login["accessToken"], expected=401)
new_login = call("/auth/login", {"email": email, "password": "NewLocalSmokePassword2026!"})["data"]
call("/auth/logout", {})
call("/auth/me", token=new_login["accessToken"], expected=401)
print("PASS: real HTTP, CSRF, registration, login, refresh, SMTP/Mailpit, reset, revocation, logout")
