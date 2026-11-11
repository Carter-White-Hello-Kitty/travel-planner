"""Real Places + backend + database acceptance. No Maps calls, secret output or unrelated cleanup.
Run from the backend directory: python3 scripts/live/persistence-acceptance.py
"""
import copy,http.cookiejar,json,os,re,secrets,subprocess,urllib.error,urllib.request,uuid
from pathlib import Path
if os.environ.get('SMOKE_ALLOW_WRITES') != '1':raise SystemExit('Set SMOKE_ALLOW_WRITES=1 only for a disposable local environment')
QA_PASSWORD='Qa!'+secrets.token_hex(24)
BASE=os.environ.get('SMOKE_URL','http://localhost:8085')
if not re.fullmatch(r'http://localhost:\d+',BASE):raise SystemExit('Use a localhost HTTP backend')
rows=[];suffix=uuid.uuid4().hex[:12]
def record(name,status,ok,**info):
 row=dict(test=name,http_status=status,passed=bool(ok),**info);rows.append(row)
 print(json.dumps(row,ensure_ascii=False),flush=True)
class Session:
 def __init__(self):
  self.jar=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),urllib.request.HTTPCookieProcessor(self.jar));self.token=None;self.csrf=None;self.user=None;self.email=None
 def call(self,method,path,body=None,auth=True,retry=True,csrf=True):
  h={'Accept':'application/json','Origin':'http://localhost:3000','X-Requested-With':'XMLHttpRequest'}
  if auth and self.token:h['Authorization']='Bearer '+self.token
  if method not in ['GET','HEAD'] and csrf:
   if not self.csrf:self.csrf=self.call('GET','/auth/csrf',auth=False)[1]['data']['token']
   h['X-XSRF-TOKEN']=self.csrf
  if body is not None:h['Content-Type']='application/json'
  req=urllib.request.Request(BASE+path,method=method,headers=h,data=None if body is None else json.dumps(body).encode())
  try:
   with self.opener.open(req,timeout=60) as r:status=r.status;data=json.load(r)
  except urllib.error.HTTPError as e:
   status=e.code
   try:data=json.load(e)
   except ValueError:data={}
  if status==403 and data.get('error',{}).get('code')=='CSRF_REJECTED' and retry and csrf:
   self.csrf=None;return self.call(method,path,body,auth,False,csrf)
  return status,data
 def login(self):
  s,d=self.call('POST','/auth/login',{'email':self.email,'password':QA_PASSWORD},auth=False)
  if s==200:self.token=d['data']['accessToken'];self.user=d['data']['user']['id']
  return s
 def create(self,label):
  self.email=f'persistence-{suffix}-{label}@example.com'
  s,d=self.call('POST','/auth/register',{'email':self.email,'password':QA_PASSWORD},auth=False)
  record('register_'+label,s,s==201)
  s=self.login();record('login_'+label,s,s==200)
  if s!=200:raise RuntimeError('QA login failed')
 def logout(self):
  s,d=self.call('POST','/auth/logout',{});self.token=None;self.csrf=None;return s
 def refresh_page(self):
  fresh=Session()
  for c in self.jar:fresh.jar.set_cookie(copy.copy(c))
  s,d=fresh.call('POST','/auth/refresh',{},auth=False)
  if s==200:fresh.token=d['data']['accessToken'];fresh.email=self.email;fresh.user=self.user
  return s,fresh

def sql(query):
 # Container credentials stay in its environment; this helper only executes scoped SELECTs.
 if not query.lstrip().upper().startswith('SELECT '):raise RuntimeError('Read-only SQL only')
 cmd=['docker','compose',*os.environ.get('SMOKE_COMPOSE_ARGS','').split(),'exec','-T','mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -utravel --batch --skip-column-names "$2" -e "$1"','sh',query,os.environ.get('SMOKE_DB_NAME','travel_planner')]
 r=subprocess.run(cmd,capture_output=True,text=True,timeout=20)
 if r.returncode:raise RuntimeError('Read-only database verification unavailable')
 return r.stdout.strip()

a=Session();b=Session();trips=[];favorite_ids=set()
try:
 a.create('a');b.create('b')
 places=[]
 for keyword in ['museums in Philadelphia Pennsylvania USA', 'Philadelphia Museum of Art', 'The Franklin Institute Philadelphia', 'Reading Terminal Market Philadelphia']:
  s,d=a.call('POST','/pois/search',{'keyword':keyword,'latitude':39.9526,'longitude':-75.1652,'radius':30000})
  found=(d.get('data') or {}).get('pois') or []
  record('real_search_'+keyword,s,s==200,count=len(found))
  for place in found:
   if place['googlePlaceId'] not in [p['googlePlaceId'] for p in places]:places.append(place)
  if len(places)>=3:break
 record('real_Philadelphia_POIs',s,s==200 and len(places)>=3,count=len(places))
 if len(places)<3:raise RuntimeError('Need at least three real search results')
 ids=[p['googlePlaceId'] for p in places[:3]]
 if not all(re.fullmatch(r'[A-Za-z0-9_-]{1,225}',pid) for pid in ids):raise RuntimeError('Unexpected provider ID format')
 record('selected_real_places',200,True,names=[p['name'] for p in places[:3]])
 invalid_id='this-place-does-not-exist-acceptance'
 s,d=a.call('GET','/pois/'+invalid_id);record('real_invalid_place_details',s,s==400 and d.get('error',{}).get('code')=='INVALID_REQUEST')
 s,d=a.call('POST','/wishlist',{'googlePlaceId':invalid_id});record('real_invalid_place_wishlist',s,s==400 and d.get('error',{}).get('code')=='INVALID_REQUEST')
 count=sql(f"SELECT COUNT(*) FROM wishlist WHERE user_id={int(a.user)} AND google_place_id='{invalid_id}'")
 record('invalid_wishlist_not_persisted',None,count=='0')
 pid=ids[0];favorite_ids.add(pid)
 s,d=a.call('POST','/wishlist',{'googlePlaceId':pid});record('wishlist_add_real_POI',s,s==201)
 count=sql(f"SELECT COUNT(*) FROM wishlist WHERE user_id={int(a.user)} AND google_place_id='{pid}'")
 record('wishlist_database_insert',None,count=='1',row_count=int(count))
 s,d=a.call('POST','/wishlist',{'googlePlaceId':pid});record('wishlist_duplicate_rejected',s,s==409 and d.get('error',{}).get('code')=='WISHLIST_ITEM_ALREADY_EXISTS')
 count=sql(f"SELECT COUNT(*) FROM wishlist WHERE user_id={int(a.user)} AND google_place_id='{pid}'")
 record('wishlist_database_unique',None,count=='1',row_count=int(count))
 s,d=a.call('GET','/wishlist');saved=d.get('data',{}).get('items',[])
 record('wishlist_own_list',s,s==200 and any(p['googlePlaceId']==pid for p in saved))
 s,d=a.call('GET','/wishlist/ids');record('wishlist_frontend_membership',s,s==200 and pid in d.get('data',[]))
 rs,fresh=a.refresh_page();s,d=fresh.call('GET','/wishlist');record('wishlist_fresh_session_reload',s,rs==200 and s==200 and any(p['googlePlaceId']==pid for p in d.get('data',{}).get('items',[])));a=fresh
 s=a.logout();ls=a.login();s,d=a.call('GET','/wishlist');record('wishlist_logout_login_persistence',s,ls==200 and any(p['googlePlaceId']==pid for p in d.get('data',{}).get('items',[])))
 s,d=b.call('GET','/wishlist');record('wishlist_user_isolation_list',s,s==200 and not d.get('data',{}).get('items'))
 s,d=b.call('DELETE','/wishlist/'+pid);record('wishlist_other_user_cannot_remove',s,s==404)
 s,d=b.call('POST','/wishlist',{'googlePlaceId':pid});record('wishlist_same_POI_independent_user',s,s==201)
 count=sql(f"SELECT COUNT(*) FROM wishlist WHERE user_id IN ({int(a.user)},{int(b.user)}) AND google_place_id='{pid}'")
 record('wishlist_database_two_owners',None,count=='2',row_count=int(count))
 for method,path,body in [('GET','/wishlist',None),('POST','/wishlist',{'googlePlaceId':pid}),('DELETE','/wishlist/'+pid,None)]:
  s,d=Session().call(method,path,body);record('wishlist_anonymous_'+method,s,s==401)
 s,d=a.call('POST','/wishlist',{'googlePlaceId':ids[1]},csrf=False);record('wishlist_missing_CSRF_rejected',s,s==403)
 s,d=a.call('GET','/wishlist');wish_id=d['data']['items'][0]['googlePlaceId']
 plan={'name':'Persistence QA '+suffix,'destination':'Philadelphia','startDate':'2026-12-01','days':[[wish_id,ids[1]], [ids[2]]]}
 s,d=a.call('POST','/trips/manual',plan);tid=d.get('data',{}).get('tripId');record('itinerary_create_from_wishlist_real_POIs',s,s==201 and bool(tid))
 if not tid:raise RuntimeError('Trip creation failed')
 trips.append(tid)
 count=sql(f'SELECT COUNT(*) FROM trips WHERE id={int(tid)} AND user_id={int(a.user)} AND destination=\'Philadelphia\' AND start_date=\'2026-12-01\' AND day_count=2')
 record('itinerary_database_metadata',None,count=='1')
 count=sql(f'SELECT COUNT(*) FROM trip_pois p JOIN trip_days d ON d.id=p.trip_day_id JOIN trips t ON t.id=d.trip_id WHERE t.id={int(tid)} AND t.user_id={int(a.user)}')
 record('itinerary_database_relationships',None,count=='3',poi_rows=int(count))
 s,d=a.call('GET',f'/trips/{tid}');trip=d['data'];record('itinerary_reload_metadata_days_order',s,s==200 and trip['name']==plan['name'] and trip['destination']=='Philadelphia' and trip['startDate']=='2026-12-01' and [[p['googlePlaceId'] for p in day['tripPois']] for day in trip['tripDays']]==plan['days'])
 stable={p['googlePlaceId']:p['tripPoiId'] for day in trip['tripDays'] for p in day['tripPois']}
 plan['days']=[[ids[1],wish_id],[ids[2]]]
 s,d=a.call('PUT',f'/trips/{tid}/plan',plan);s,d=a.call('GET',f'/trips/{tid}');record('itinerary_same_day_reorder',s,s==200 and [p['googlePlaceId'] for p in d['data']['tripDays'][0]['tripPois']]==plan['days'][0])
 plan['days']=[[ids[1]],[ids[2],wish_id]];plan['name']='Edited '+suffix
 s,d=a.call('PUT',f'/trips/{tid}/plan',plan);s,d=a.call('GET',f'/trips/{tid}');trip=d['data']
 record('itinerary_cross_day_move_edit',s,s==200 and [[p['googlePlaceId'] for p in day['tripPois']] for day in trip['tripDays']]==plan['days'])
 record('itinerary_POI_IDs_preserved',s,all(stable[p['googlePlaceId']]==p['tripPoiId'] for day in trip['tripDays'] for p in day['tripPois']))
 plan['days']=[[ids[1]],[wish_id]]
 s,d=a.call('PUT',f'/trips/{tid}/plan',plan);count=sql(f'SELECT COUNT(*) FROM trip_pois p JOIN trip_days d ON d.id=p.trip_day_id WHERE d.trip_id={int(tid)}')
 record('itinerary_delete_POI_database',s,s==200 and count=='2',remaining_POIs=int(count))
 rs,fresh=a.refresh_page();a=fresh;s,d=a.call('GET',f'/trips/{tid}');record('itinerary_fresh_session_reload',s,rs==200 and s==200 and [[p['googlePlaceId'] for p in day['tripPois']] for day in d['data']['tripDays']]==plan['days'])
 a.logout();ls=a.login();s,d=a.call('GET',f'/trips/{tid}');record('itinerary_logout_login_persistence',s,ls==200 and s==200 and d['data']['name']==plan['name'])
 s,d=b.call('GET','/trips');record('itinerary_user_isolation_list',s,s==200 and not d.get('data'))
 for method,path,body,expected in [('GET',f'/trips/{tid}',None,404),('PUT',f'/trips/{tid}/plan',plan,403),('DELETE',f'/trips/{tid}',None,403)]:
  s,d=b.call(method,path,body);record('itinerary_other_user_'+method,s,s==expected)
 for method,path,body in [('GET',f'/trips/{tid}',None),('POST','/trips/manual',plan),('PUT',f'/trips/{tid}/plan',plan)]:
  s,d=Session().call(method,path,body);record('itinerary_anonymous_'+method,s,s==401)
 for days in [1,15]:
  p={**plan,'days':[[] for _ in range(days)]};s,d=a.call('POST','/trips/manual',p);boundary=d.get('data',{}).get('tripId')
  if boundary:trips.append(boundary)
  record('itinerary_'+str(days)+'_days',s,s==201 and bool(boundary))
 for label,changes in [('zero_days',{'days':[]}),('sixteen_days',{'days':[[] for _ in range(16)]}),('duplicate_POI',{'days':[[pid],[pid]]}),('invalid_date',{'startDate':'2026-02-30'}),('blank_destination',{'destination':' '}),('too_many_POIs',{'days':[ids*4]})]:
  s,d=a.call('PUT',f'/trips/{tid}/plan',{**plan,**changes});record('itinerary_reject_'+label,s,s==400,error_code=d.get('error',{}).get('code'))
 for path,expected in [('/trips/999999999',404),('/trips/not-a-number',400),('/trips/-1',400)]:
  s,d=a.call('GET',path);record('itinerary_invalid_ID_'+path.rsplit('/',1)[-1],s,s==expected,error_code=d.get('error',{}).get('code'))
 s,d=a.call('PUT',f'/trips/{tid}/plan',plan,csrf=False);record('itinerary_missing_CSRF_rejected',s,s==403)
 s,d=a.call('PUT',f'/trips/{tid}/plan',{**plan,'name':'Must not persist','days':[[ids[1],invalid_id]]})
 record('real_invalid_place_plan_rejected',s,s==400 and d.get('error',{}).get('code')=='INVALID_REQUEST')
 s,d=a.call('GET',f'/trips/{tid}');record('itinerary_invalid_updates_leave_plan_intact',s,s==200 and d['data']['name']==plan['name'] and [[p['googlePlaceId'] for p in day['tripPois']] for day in d['data']['tripDays']]==plan['days'])
 count=sql(f"SELECT COUNT(*) FROM trip_pois p JOIN trip_days d ON d.id=p.trip_day_id WHERE d.trip_id={int(tid)} AND p.google_place_id='{invalid_id}'")
 record('invalid_itinerary_POI_not_persisted',None,count=='0')
 for sess,label in [(a,'a'),(b,'b')]:
  s,d=sess.call('DELETE','/wishlist/'+pid);record('wishlist_remove_'+label,s,s==200)
  count=sql(f"SELECT COUNT(*) FROM wishlist WHERE user_id={int(sess.user)} AND google_place_id='{pid}'");record('wishlist_database_removed_'+label,None,count=='0',row_count=int(count))
 s,d=a.call('GET','/wishlist/ids');record('wishlist_membership_after_remove',s,s==200 and pid not in d.get('data',[]))
except Exception as e:
 record('acceptance_execution_error',None,False,error_type=type(e).__name__)
finally:
 for tid in trips:
  s,d=a.call('DELETE',f'/trips/{tid}');record('cleanup_own_trip_'+str(tid),s,s==200)
 for sess in [a,b]:
  if sess.token:
   for pid in favorite_ids:sess.call('DELETE','/wishlist/'+pid)
   sess.logout()
 print(json.dumps({'summary':{'checks':len(rows),'passed':sum(r['passed'] for r in rows),'failed':sum(not r['passed'] for r in rows)},'maps':'explicitly skipped'},ensure_ascii=False),flush=True)
if any(not row['passed'] for row in rows):raise SystemExit(1)
