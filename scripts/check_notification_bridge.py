#!/usr/bin/env python3
"""Exercise a synthetic Kurviger-format notification on a task-owned Android emulator.
Build/install :navfixture:assembleDebug first. The fixture is explicitly labeled and is not a vendor app.
"""
import json,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
A=['adb','-s','emulator-5554']; OUT=Path(__file__).resolve().parents[1]/'docs'/'verification-1.4'/'notification'
OUT.mkdir(parents=True,exist_ok=True)
def call(*args):return subprocess.check_output(A+list(args),stderr=subprocess.STDOUT)
assert call('shell','getprop','ro.kernel.qemu').strip()==b'1', 'Synthetic vendor-package fixture is for the task emulator only'
def tree():
 call('shell','uiautomator','dump','/sdcard/notification-ui.xml')
 return E.fromstring(call('exec-out','cat','/sdcard/notification-ui.xml'))
def find(label,root=None):
 matches=[]
 for n in sorted((root if root is not None else tree()).iter('node'),key=lambda n:n.get('resource-id')!='app.ktmnav:id/nav_tab'):
  if label.casefold() in ((n.get('text') or '').casefold(), (n.get('content-desc') or '').casefold()):
   b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   if len(b)==4 and b[2]>b[0] and b[3]>b[1]:matches.append(n)
 return next((n for n in matches if n.get('checkable')=='true'),matches[0] if matches else None)
def tap(label):
 n=find(label);assert n is not None,label
 b=list(map(int,re.findall(r'\d+',n.get('bounds'))));call('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));time.sleep(.5)
def reveal(label):
 for _ in range(9):
  if find(label) is not None:return
  call('shell','input','swipe','540','1850','540','650','300');time.sleep(.3)
 raise AssertionError('Missing '+label)
def post(clear=False):
 cmd=['shell','am','start','-W','-n','com.kurviger.app/app.ktmnav.fixture.FixtureActivity']
 if clear:cmd+=['--es','action','clear']
 call(*cmd);time.sleep(.7);call('shell','am','start','-W','-n','app.ktmnav/.MainActivity');time.sleep(.6)
def route():
 wanted={'250 m','Lakeside Road','10:42','12.4 km','Kurviger','Navigation received · connect your motorcycle'};seen=set()
 call('shell','input','swipe','540','650','540','1900','300');time.sleep(.3)
 for _ in range(6):
  r=tree();seen.update(n.get('text','') for n in r.iter('node'))
  if find('Lakeside Road',r) is not None:
   (OUT/'notification-route.png').write_bytes(call('exec-out','screencap','-p'))
   (OUT/'notification-route.xml').write_bytes(E.tostring(r,encoding='utf-8'))
  if wanted<=seen:return sorted(wanted)
  call('shell','input','swipe','540','1850','540','650','300');time.sleep(.3)
 raise AssertionError({'missing':sorted(wanted-seen),'seen':sorted(seen)})
def empty():
 reveal('No active route')
 assert find('No active route') is not None and find('250 m') is None
call('shell','input','keyevent','KEYCODE_WAKEUP');call('shell','wm','dismiss-keyguard')
call('shell','cmd','notification','allow_listener','app.ktmnav/app.ktmnav.backend.NavListener')
call('shell','pm','grant','com.kurviger.app','android.permission.POST_NOTIFICATIONS')
call('shell','am','start','-S','-W','-n','app.ktmnav/.MainActivity');time.sleep(.8)
if find('Get started') is not None: tap('Get started')
tap('Settings'); reveal('Capture maneuver arrows')
if find('Capture maneuver arrows').get('checked') != 'true': tap('Capture maneuver arrows')
tap('Ride')
post(); values=route()
tap('Apps'); reveal('Forward navigation from Kurviger');toggle=find('Forward navigation from Kurviger')
assert toggle.get('checked')=='true';tap('Forward navigation from Kurviger');tap('Ride');empty()
post();empty()  # Disabled source must remain clear even when a new notification arrives.
tap('Apps');reveal('Forward navigation from Kurviger');tap('Forward navigation from Kurviger');tap('Ride')
post();route();post(clear=True);empty()
captures=call('shell','run-as','app.ktmnav','ls','files/nav_capture').decode().splitlines()
assert any(name.endswith('.png') for name in captures), captures
assert len(captures) <= 100, captures
tap('Settings'); reveal('Capture maneuver arrows')
if find('Capture maneuver arrows').get('checked') == 'true': tap('Capture maneuver arrows')
tap('Ride')
crash=call('logcat','-d','-b','crash').decode();assert 'Process: app.ktmnav' not in crash,crash
result={'passed':True,'fixture':'synthetic Kurviger-format notification; not vendor runtime traffic','received_fields':values,'disabled_source_clears_existing_route':True,'disabled_source_ignores_new_updates':True,'reenabled_source_receives_update':True,'notification_removal_clears_route':True,'physical_motorcycle_tested':False,'private_maneuver_capture_png_count':len(captures)}
(OUT/'notification-results.json').write_text(json.dumps(result,indent=2))
print('PASS: notification listener -> parser -> UI, user source toggle, re-enable and notification removal; no fake motorcycle connection')
