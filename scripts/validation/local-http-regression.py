"""Keyless HTTP checks against ONLY run-isolated.py's disposable Docker project.
QA fixture data is retained in that project's stopped volume. No shared data cleanup.
"""
import http.cookiejar
import json
import os
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

base = os.environ.get('SMOKE_URL', '')
project = os.environ.get('VALIDATION_COMPOSE_PROJECT', '')
assert re.fullmatch(r'http://localhost:\d+', base)
assert re.fullmatch(r'tp-backend-validation-[a-f0-9]+', project)
assert os.environ.get('SMOKE_ALLOW_WRITES') == '1'
compose = ['docker', 'compose', '-p', project, '-f', os.environ['VALIDATION_COMPOSE_FILE']]
checks = []
password = 'Qa!' + secrets.token_hex(24)
suffix = uuid.uuid4().hex

class Session:
    def __init__(self):
        self.jar = http.cookiejar.CookieJar()
        self.client = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar))
        self.token = None
        self.csrf = None
    def call(self, method, path, data=None, retry=True, use_csrf=True):
        headers = {'Origin':'http://localhost:3000', 'Accept':'application/json', 'X-Requested-With':'XMLHttpRequest'}
        if self.token: headers['Authorization'] = 'Bearer ' + self.token
        if method != 'GET' and use_csrf:
            if not self.csrf: self.csrf = self.call('GET','/auth/csrf')[1]['data']['token']
            headers['X-XSRF-TOKEN'] = self.csrf
        if data is not None: headers['Content-Type'] = 'application/json'
        req = urllib.request.Request(base+path, method=method, headers=headers, data=None if data is None else json.dumps(data).encode())
        try:
            with self.client.open(req,timeout=20) as response: status, result = response.status, json.load(response)
        except urllib.error.HTTPError as error: status, result = error.code, json.load(error)
        if status == 403 and result.get('error',{}).get('code') == 'CSRF_REJECTED' and retry and use_csrf:
            self.csrf = None
            return self.call(method,path,data,False,use_csrf)
        return status,result
    def account(self, label):
        email = 'integration-'+suffix+'-'+label+'@example.com'
        status,_ = self.call('POST','/auth/register',{'email':email,'password':password})
        assert status == 201
        status,result = self.call('POST','/auth/login',{'email':email,'password':password})
        assert status == 200
        self.token = result['data']['accessToken']
        return result['data']['user']['id']

def check(name, passed):
    checks.append({'check':name,'passed':bool(passed)})
    assert passed, name

def sql(query):
    output = subprocess.run(compose+['exec','-T','mysql','sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --skip-column-names travel_planner_test'],
        input=query,text=True,capture_output=True,check=True)
    return output.stdout.strip()

def trip(owner, status, name):
    assert isinstance(owner,int) and status in ['Completed','Cancelled','Ongoing']
    return int(sql(f"INSERT INTO trips(user_id,name,status,day_count) VALUES({owner},'qa-{suffix}-{name}','{status}',1); SELECT LAST_INSERT_ID();"))

a,b = Session(),Session()
try:
    owner = a.account('a'); other = b.account('b')
    completed = trip(owner,'Completed','completed'); cancelled = trip(owner,'Cancelled','cancelled')
    ongoing = trip(owner,'Ongoing','ongoing'); foreign = trip(other,'Completed','foreign')
    for session,tid,content in [(a,completed,'海边散步'),(a,cancelled,'海边日落'),(b,foreign,'海边旅行')]:
        status,_ = session.call('POST','/travel-posts',{'trip_id':tid,'content':content+' QA '+suffix,'photos':[]})
        check('Publish '+content,status==201)
    check('Ongoing publish rejected',a.call('POST','/travel-posts',{'trip_id':ongoing,'content':'Reject'})[0]==409)
    check('Foreign publish rejected',b.call('POST','/travel-posts',{'trip_id':completed,'content':'Reject'})[0]==403)
    check('Missing CSRF publish rejected',a.call('POST','/travel-posts',{'trip_id':completed},use_csrf=False)[0]==403)
    result = None
    for _ in range(120):
        status,result = b.call('GET','/travel-posts?keyword='+urllib.parse.quote('海边'))
        if status == 200 and len(result['data']['items']) == 3: break
        assert status in [200,503]
        time.sleep(0.5)
    check('Real ES Chinese search and cross-author visibility',status==200 and len(result['data']['items'])==3)
    status,result = b.call('GET','/travel-posts')
    ids = [post['id'] for post in result['data']['items']]
    check('Blank list and fixed ordering',status==200 and len(ids)==3 and ids==sorted(ids,reverse=True))
    p1 = b.call('GET','/travel-posts?pageSize=1')[1]['data']; p2 = b.call('GET','/travel-posts?page=2&pageSize=1')[1]['data']
    check('Pagination',p1['pagination']['totalPages']==3 and p1['items'][0]['id']!=p2['items'][0]['id'])
    check('No results',b.call('GET','/travel-posts?keyword='+uuid.uuid4().hex)[1]['data']['items']==[])
    for path in ['/travel-posts?page=abc','/travel-posts?page=0','/travel-posts?pageSize=101']:
        check('Invalid pagination '+path,b.call('GET',path)[0]==400)
    check('Anonymous search rejected',Session().call('GET','/travel-posts')[0]==401)
    check('Wishlist list',a.call('GET','/wishlist')[1]['data']['items']==[])
    check('Wishlist IDs',a.call('GET','/wishlist/ids')[1]['data']==[])
    check('Places rejects invalid input without provider',a.call('POST','/pois/search',{'keyword':'','latitude':0,'longitude':0,'radius':100})[0]==400)
    check('Maps rejects invalid input without provider',a.call('POST','/pois/map',{'latitude':90,'longitude':0,'zoom':99})[0]==400)
    check('Photo resource rejects invalid input without provider',a.call('POST','/pois/photo',{'name':'../invalid'})[0]==400)
    print(json.dumps({'checks':len(checks),'passed':sum(c['passed'] for c in checks),'results':checks,'real_paid_providers':'not called','fixture_cleanup':'Retained only in disposable stopped validation volume'},ensure_ascii=False))
finally:
    for session in [a,b]:
        if session.token: session.call('POST','/auth/logout',{})
