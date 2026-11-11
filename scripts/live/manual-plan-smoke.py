import urllib.request, urllib.error, http.cookiejar, json, time, os, re, secrets, uuid
if os.environ.get('SMOKE_ALLOW_WRITES') != '1':raise SystemExit('Set SMOKE_ALLOW_WRITES=1 only for a disposable local environment')
base=os.environ.get('SMOKE_URL', 'http://localhost:8085')
if not re.fullmatch(r'http://localhost:\d+', base): raise SystemExit('SMOKE_URL must be http://localhost:<port>')
jar=http.cookiejar.CookieJar()
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
csrf=None
token=None
results=[]
def call(method,path,body=None,auth=True,retry=True):
 global csrf
 headers={'Accept':'application/json','Origin':'http://localhost:3000','X-Requested-With':'XMLHttpRequest'}
 if token and auth: headers['Authorization']='Bearer '+token
 if method not in ['GET','HEAD']:
  if not csrf:
   _,r=call('GET','/auth/csrf',auth=False);csrf=r['data']['token']
  headers['X-XSRF-TOKEN']=csrf
 data=None if body is None else json.dumps(body).encode()
 if data is not None:headers['Content-Type']='application/json'
 req=urllib.request.Request(base+path,data=data,headers=headers,method=method)
 try:
  with opener.open(req,timeout=25) as res:return res.status,json.load(res)
 except urllib.error.HTTPError as e:
  r=json.load(e)
  if e.code==403 and r.get('error',{}).get('code')=='CSRF_REJECTED' and retry:
   csrf=None
   return call(method,path,body,auth,False)
  return e.code,r
def check(label,condition):
 results.append({'check':label,'passed':bool(condition)})
 if not condition: raise AssertionError(label)
email='delivery-qa-'+uuid.uuid4().hex+'@example.com'
password='Qa!'+secrets.token_hex(24)
id=None
try:
 status,r=call('POST','/auth/register',{'email':email,'password':password},False)
 check('Real HTTP registration',status==201)
 status,r=call('POST','/auth/login',{'email':email,'password':password},False)
 check('Real HTTP login',status==200)
 token=r['data']['accessToken']
 plan={'name':'Delivery QA 15-day plan','destination':'San Francisco','startDate':'2026-12-01','days':[[] for _ in range(15)]}
 status,r=call('POST','/trips/manual',plan)
 check('Create persisted 15-day trip',status==201)
 id=r['data']['tripId']
 status,r=call('GET','/trips/'+str(id))
 check('Reload saved destination/date/15 days',status==200 and r['data']['destination']=='San Francisco' and len(r['data']['tripDays'])==15 and r['data']['startDate']=='2026-12-01')
 plan.update(name='Delivery QA updated',destination='New York',days=[[],[]])
 status,r=call('PUT','/trips/'+str(id)+'/plan',plan)
 check('Update trip over HTTP: '+str(status)+' '+str(r.get('message'))+' '+str(r.get('error',{}).get('code')),status==200)
 status,r=call('GET','/trips/'+str(id))
 check('Reload updated trip',status==200 and r['data']['name']=='Delivery QA updated' and r['data']['dayCount']==2 and r['data']['destination']=='New York')
 status,r=call('POST','/auth/refresh',{},False)
 check('Refresh session with HttpOnly cookie',status==200)
 token=r['data']['accessToken']
 status,r=call('GET','/trips/'+str(id))
 check('Reload trip after token restoration',status==200 and r['data']['name']=='Delivery QA updated')
 invalid={**plan,'days':[[] for _ in range(16)]}
 status,r=call('PUT','/trips/'+str(id)+'/plan',invalid)
 check('Reject 16-day plan',status==400)
 status,r=call('GET','/wishlist')
 check('Real persisted wishlist list',status==200 and isinstance(r['data']['items'],list))
 status,r=call('GET','/wishlist/ids')
 check('Wishlist membership API',status==200 and isinstance(r['data'],list))
 results.append({'check':'Real Google and Maps calls explicitly skipped','skipped':True})
 status,r=call('GET','/trips/'+str(id),auth=False)
 check('Anonymous trip access denied',status==401)
 status,r=call('DELETE','/trips/'+str(id))
 check('Delete own QA trip',status==200)
 status,r=call('GET','/trips/'+str(id))
 check('Deleted trip is unavailable',status==404)
finally:
 if token:
  if id is not None:
   status,_=call('DELETE','/trips/'+str(id))
   if status not in (200,404):raise RuntimeError('Own QA trip cleanup failed')
  status,_=call('POST','/auth/logout',{})
  if status!=200:raise RuntimeError('QA logout failed')
print(json.dumps({'base':base,'account':email,'results':results},ensure_ascii=False,indent=2))
