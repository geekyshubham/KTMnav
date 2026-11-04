#!/usr/bin/env python3
"""Exercise Gen 3 controls and app-language selection on an installed KTMnav build."""
import json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
ADB=['adb','-s','emulator-5554'];OUT=Path(__file__).resolve().parents[1]/'docs'/'verification-1.4'/'gen3'
OUT.mkdir(parents=True,exist_ok=True)
def adb(*cmd):return subprocess.check_output(ADB+list(cmd),stderr=subprocess.STDOUT)
def tree():
 adb('shell','uiautomator','dump','/sdcard/gen3-ui.xml')
 return ET.fromstring(adb('exec-out','cat','/sdcard/gen3-ui.xml'))
def node(text):
 for n in sorted(tree().iter('node'),key=lambda n:n.get('resource-id')!='app.ktmnav:id/nav_tab'):
  if text.casefold() in ((n.get('text') or '').casefold(), (n.get('content-desc') or '').casefold()):
   b=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
   if len(b)==4 and b[2]>b[0] and b[3]>b[1]:return n,b
 return None
def click(text):
 found=node(text);assert found is not None,'Missing '+text
 _,(x,y,w,h)=found;adb('shell','input','tap',str((x+w)//2),str((y+h)//2));time.sleep(.5)
def reveal(text):
 for _ in range(12):
  if node(text):return
  adb('shell','input','swipe','540','1850','540','1050','900');time.sleep(.3)
 raise AssertionError('Missing '+text)
def capture(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 adb('shell','uiautomator','dump','/sdcard/gen3-ui.xml');(OUT/(name+'.xml')).write_bytes(adb('exec-out','cat','/sdcard/gen3-ui.xml'))
adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
adb('shell','am','start','-S','-W','-n','app.ktmnav/.MainActivity');time.sleep(.8)
if node('Get started'):click('Get started')
click('Garage');assert not node('Gen 3 & Gen 2 dashboards');assert node('Add motorcycle');capture('garage-gen3')
click('Settings');reveal('Dashboard data & controls');click('Dashboard data & controls')
assert node('Values appear only when the motorcycle reports them.')
assert node('Unavailable')
capture('dashboard-data-disconnected')
reveal('No telemetry received.');assert node('No telemetry received.')
capture('dashboard-data-empty-telemetry');click('Close')
reveal('Compatibility & navigation help');click('Compatibility & navigation help');assert node('Gen 3: first pairing');capture('gen3-pairing-help');click('Close')
click('Ride');click('Settings');reveal('Gen 3 button lab');click('Gen 3 button lab')
assert node('PHONE ONLY · SIMULATION');assert node('Duke 390 · Gen 3')
reveal('DOWN');click('DOWN');assert node('Media')
click('SET');assert node('Simulated button: SET · Selected: Media')
capture('button-lab-local-preview')
click('UP');assert node('Navigation');click('BACK');assert node('Navigation')
click('Close');click('Ride');assert node('No motorcycle') or node('Not connected')
# A fresh Settings view starts at the top; appearance is farther down.
click('Ride');click('Settings');reveal('App language');click('App language');click('Deutsch');time.sleep(.7)
text=' '.join(n.get('text','') for n in tree().iter('node'))
assert 'Einstellungen' in text,text
capture('german-settings')
# Find the localized language label without changing the device-wide language.
label=next((n.get('text') for n in tree().iter('node') if n.get('text') in ['App-Sprache','App Sprache','Sprache der App','Sprache']),None)
if label is None:
 for _ in range(8):
  adb('shell','input','swipe','540','1850','540','1050','900');time.sleep(.3)
  label=next((n.get('text') for n in tree().iter('node') if n.get('text') in ['App-Sprache','App Sprache','Sprache der App','Sprache']),None)
  if label:break
assert label,'Localized language picker missing';click(label);click('English');time.sleep(.7)
assert node('Settings');capture('english-restored')
crash=adb('logcat','-d','-b','crash').decode();assert 'Process: app.ktmnav' not in crash,crash
(OUT/'ui-gen3-results.json').write_text(json.dumps({'passed':True,'checks':['Garage without Gen 3/Gen 2 explanation card','disconnected telemetry empty','Gen3 pairing help','local button lab UP/DOWN/SET/BACK','lab keeps actual bridge disconnected','German UI selection','English UI restoration'],'physical_bike_tested':False,'vehicle_cruise_control':False},indent=2))
print('PASS: Gen3 controls, actual-data-only telemetry view, pairing help and app language selection')
