#!/usr/bin/env python3
"""Native Android UI smoke checks against the installed debug APK on one ADB device."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--out', default=None, help='screenshot folder (default docs/verification-1.4/android-ui)')
args = parser.parse_args()
ADB = ['adb', '-s', args.serial]
OUT = Path(args.out) if args.out else Path(__file__).resolve().parents[1] / 'docs' / 'verification-1.4' / 'android-ui'
OUT.mkdir(parents=True, exist_ok=True)
results = []

def adb(*cmd):
    return subprocess.check_output(ADB + list(cmd), stderr=subprocess.STDOUT)

def tree():
    for attempt in range(3):
        adb('shell', 'uiautomator', 'dump', '/sdcard/ktmnav-window.xml')
        xml = adb('exec-out', 'cat', '/sdcard/ktmnav-window.xml')
        try:
            return ET.fromstring(xml)
        except ET.ParseError:
            time.sleep(.5)
    raise AssertionError('Could not read the accessibility tree')

def find(label, root=None):
    root = tree() if root is None else root
    for n in sorted(root.iter('node'), key=lambda n: n.get('resource-id') != 'app.ktmnav:id/nav_tab'):
        if label.casefold() in ((n.get('text') or '').casefold(), (n.get('content-desc') or '').casefold()):
            bounds = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
            if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                return n
    return None

def click(label):
    node = find(label)
    assert node is not None, f'Missing control: {label}'
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(bounds) == 4, f'Invalid bounds: {label}'
    x1,y1,x2,y2 = bounds
    assert x2 > x1 and y2 > y1, f'Empty touch target: {label}'
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
    time.sleep(.6)

def expect(label):
    assert find(label) is not None, f'Missing expected text: {label}'
    results.append(label)

def reveal(label):
    for _ in range(8):
        if find(label) is not None:
            return
        adb('shell','input','swipe','540','1850','540','1050','900')
        time.sleep(.4)
    raise AssertionError(f'Could not reveal {label}')

def theme_control():
    reveal('Theme')
    return 'Theme'

def status():
    for n in tree().iter('node'):
        if n.get('resource-id') == 'app.ktmnav:id/status_chip':
            desc = n.get('content-desc') or ''
            assert desc.startswith('Connection: ') and 'Connected' not in desc.split(': ',1)[1].split(' ·')[0:1], desc
            results.append(desc)
            return desc
    raise AssertionError('Connection status chip missing')

def capture(name):
    (OUT / f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))
    adb('shell','uiautomator','dump','/sdcard/ktmnav-window.xml')
    (OUT / f'{name}.xml').write_bytes(adb('exec-out','cat','/sdcard/ktmnav-window.xml'))

def launch():
    adb('shell','input','keyevent','KEYCODE_WAKEUP')
    adb('shell','wm','dismiss-keyguard')
    adb('shell','am','start','-S','-W','-n','app.ktmnav/.MainActivity')
    time.sleep(.8)

font = adb('shell','settings','get','system','font_scale').decode().strip()
accelerometer = adb('shell','settings','get','system','accelerometer_rotation').decode().strip()
rotation = adb('shell','settings','get','system','user_rotation').decode().strip()
size_override = re.search(r'Override size: (\S+)', adb('shell','wm','size').decode())
density_override = re.search(r'Override density: (\S+)', adb('shell','wm','density').decode())
motion = {key: adb('shell','settings','get','global',key).decode().strip() for key in ['animator_duration_scale','transition_animation_scale','window_animation_scale']}
try:
    adb('logcat','-c')
    adb('shell','wm','size','1125x2436')
    adb('shell','wm','density','480')  # 375dp compact phone
    launch()
    if find('Get started') is not None: click('Get started')
    click('Settings'); click(theme_control()); click('KTM · charcoal & orange'); click('Ride')
    expect('No active route')
    status()
    capture('ride-compact')
    click('Garage'); expect('Add motorcycle'); capture('garage')
    click('Apps'); expect('Google Maps'); capture('apps'); reveal('How navigation forwarding works'); expect('How navigation forwarding works')
    click('Settings'); expect('Keep bridge in background'); capture('settings')
    reveal('Theme'); click('Theme'); click('Daylight · high contrast')
    click('Ride'); expect('No active route'); capture('ride-light')
    click('Settings'); reveal('Theme'); click('Theme'); click('Husqvarna · charcoal & blue')
    click('Ride'); expect('No active route'); capture('ride-husqvarna')
    click('Settings'); reveal('Theme'); click('Theme'); click('KTM · charcoal & orange')
    click('Ride')
    # Scroll to the explicit preview action without assuming device screen coordinates.
    for _ in range(5):
        if find('Preview dashboard') is not None:
            break
        adb('shell','input','swipe','540','1850','540','600','300'); time.sleep(.4)
    click('Preview dashboard')
    adb('shell','input','swipe','540','600','540','1900','300'); time.sleep(.4)
    expect('Google Maps'); expect('Preview'); expect('250 m'); expect('Preview only · no data sent to your bike'); status(); capture('preview')
    click('Garage'); click('Ride')
    reveal('Exit dashboard preview'); click('Exit dashboard preview')
    adb('shell','settings','put','system','font_scale','2.0')
    for key in motion: adb('shell','settings','put','global',key,'0')
    time.sleep(1)
    launch(); expect('No active route'); capture('ride-large-text')
    for label in ['Ride','Garage','Apps','Settings']: expect(label)
    click('Settings'); adb('shell','input','keyevent','KEYCODE_BACK'); time.sleep(.5)
    expect('No active route')
    adb('shell','settings','put','system','font_scale','1.0')
    adb('shell','settings','put','system','accelerometer_rotation','0')
    adb('shell','settings','put','system','user_rotation','1')
    time.sleep(1)
    expect('Ride'); expect('Garage'); capture('landscape')
    adb('shell','settings','put','system','user_rotation','0')
    adb('shell','wm','size','1600x2560')
    adb('shell','wm','density','320')
    time.sleep(1)
    launch(); expect('No active route'); expect('Settings'); capture('tablet')
    log = adb('logcat','-d','-b','crash').decode()
    assert 'FATAL EXCEPTION' not in log, log
    (OUT / 'crash-log.txt').write_text(log)
    (OUT / 'ui-results.json').write_text(json.dumps({'passed': True, 'assertions': results}, indent=2))
    print(f'PASS: {len(results)} UI assertions; screenshots in {OUT}')
finally:
    for key,value in motion.items():
        adb('shell','settings','delete' if value == 'null' else 'put','global',key,*([] if value == 'null' else [value]))
    for key,value in [('font_scale',font),('accelerometer_rotation',accelerometer),('user_rotation',rotation)]:
        adb('shell','settings','delete' if value == 'null' else 'put','system',key,*([] if value == 'null' else [value]))
    adb('shell','wm','size',size_override.group(1) if size_override else 'reset')
    adb('shell','wm','density',density_override.group(1) if density_override else 'reset')
