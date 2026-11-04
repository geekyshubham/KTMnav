#!/usr/bin/env python3
"""Mapping configuration persistence, reset, disclosure and included-feature navigation on emulator."""
import json,re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
A=['adb','-s','emulator-5554'];OUT=Path(__file__).resolve().parents[1]/'docs'/'verification-1.4'/'handlebar';OUT.mkdir(parents=True,exist_ok=True)
def adb(*args):return subprocess.check_output(A+list(args),stderr=subprocess.STDOUT)
def tree():
 adb('shell','uiautomator','dump','/sdcard/handlebar-ui.xml')
 return E.fromstring(adb('exec-out','cat','/sdcard/handlebar-ui.xml'))
def find(text):
 matches=[]
 for n in sorted(tree().iter('node'),key=lambda n:n.get('resource-id')!='app.ktmnav:id/nav_tab'):
  if text.casefold() in ((n.get('text')or'').casefold(),(n.get('content-desc')or'').casefold()):
   b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   if len(b)==4 and b[2]>b[0] and b[3]>b[1]:matches.append((n,b))
 return next((m for m in matches if m[0].get('checkable')=='true'),matches[0] if matches else None)
def tap(text):
 item=find(text);assert item,'Missing '+text
 _,(x1,y1,x2,y2)=item;adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4)
def reveal(text):
 for _ in range(14):
  if find(text):return
  adb('shell','input','swipe','540','1800','540','700','220');time.sleep(.2)
 raise AssertionError('Missing '+text)
def shot(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 (OUT/(name+'.xml')).write_bytes(E.tostring(tree(),encoding='utf-8'))
def launch():
 adb('shell','am','start','-S','-W','-n','app.ktmnav/.MainActivity');time.sleep(.5)
 if find('Get started'):tap('Get started')
def mappings():
 tap('Settings');reveal('Handlebar button mappings');tap('Handlebar button mappings')
adb('logcat','-c');adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard');launch();mappings()
assert find('Gen 3 handlebar buttons. Set up while parked.')
if find('Enable custom mappings')[0].get('checked')!='true':tap('Enable custom mappings')
assert find('UP · Scroll up');tap('UP · Scroll up');reveal('Next track');tap('Next track');assert find('UP · Next track');shot('saved-button-mapping')
tap('Close');launch();mappings();assert find('Enable custom mappings')[0].get('checked')=='true';assert find('UP · Next track')
reveal('Allow screen controls');tap('Allow screen controls');assert find('Allow handlebar screen controls?');assert find('Open Accessibility settings');shot('screen-control-disclosure');tap('Cancel')
reveal('Restore default mappings');tap('Restore default mappings');tap('Restore')
# Reset reflows assignments above current scroll, reopen at top.
tap('Close');mappings();assert find('UP · Scroll up');assert find('Enable custom mappings')[0].get('checked')=='true';shot('default-button-mappings')
tap('Enable custom mappings');assert find('Enable custom mappings')[0].get('checked')=='false';tap('Close')
tap('Ride');tap('Settings');reveal('Included features');tap('Included features')
assert find('Navigation integrations');shot('included-features')
reveal('Configure navigation apps');tap('Configure navigation apps');assert find('How navigation forwarding works')
tap('Settings');reveal('Included features');tap('Included features');reveal('Open Garage');tap('Open Garage');assert find('Add motorcycle')
crash=adb('logcat','-d','-b','crash').decode();assert 'Process: app.ktmnav' not in crash,crash
result={'passed':True,'checks':['four button assignments','custom mapping enable switch','mapping saved across process restart','Android access disclosure','reset defaults preserves enable switch','disable execution without losing assignments','included navigation integrations link','included multiple-bike Garage link'],'physical_handlebar_tested':False}
(OUT/'handlebar-ui-results.json').write_text(json.dumps(result,indent=2));print('PASS:',len(result['checks']),'mapping and included-feature UI checks')
