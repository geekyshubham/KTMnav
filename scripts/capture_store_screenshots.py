#!/usr/bin/env python3
"""Capture real app screens on the task emulator for fastlane frameit."""
from pathlib import Path
import json
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
helper = ROOT / 'scripts/check_redesign_ui.py'
ui = {'__file__': str(helper)}
# Reuse the existing emulator UI helpers without running their regression suite.
exec(compile(helper.read_text().partition('\nrestore = ')[0], str(helper), 'exec'), ui)
OUT = ROOT / 'fastlane/screenshots/raw'
OUT.mkdir(parents=True, exist_ok=True)
ui['OUT'] = OUT
adb, launch, tab, tap = [ui[n] for n in ('adb', 'launch', 'tab', 'tap')]
assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Emulator only'

original_size = adb('shell', 'wm', 'size').decode()
original_prefs = ui['prefs_xml']()
old_listeners = adb('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners').decode().strip()
whitelisted = 'app.ktmnav' in adb('shell', 'dumpsys', 'deviceidle', 'whitelist').decode()
try:
    # Google Pixel 5 dimensions match frameit's Android frame without stretching.
    adb('shell', 'wm', 'size', '1080x2340')
    adb('shell', 'cmd', 'overlay', 'enable', 'com.android.internal.display.cutout.emulation.hole')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'settings', 'put', 'global', 'sysui_demo_allowed', '1')
    for command in [('clock', 'hhmm', '0941'), ('battery', 'level', '100', 'plugged', 'false'), ('notifications', 'visible', 'false')]:
        args = ['shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '--es', 'command', command[0]]
        for key, val in zip(command[1::2], command[2::2]): args += ['--es', key, val]
        adb(*args)
    for permission in ('BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS'):
        adb('shell', 'pm', 'grant', 'app.ktmnav', 'android.permission.' + permission, check=False)
    adb('shell', 'cmd', 'notification', 'allow_listener', ui['LISTENER'])
    adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+app.ktmnav')
    launch()
    prefs = ET.Element('map')
    values = {
        'welcome_complete': True, 'theme': 'KTM', 'app_language': 'en',
        'desired_connect': False, 'handlebar_mappings_enabled': False,
        'active_bike_address': '02:00:00:00:14:01', 'active_bike_name': 'Duke 390',
        'active_bike_gen': 'GEN3', 'bike_name_02:00:00:00:14:01': 'Duke 390',
        'bike_gen_02:00:00:00:14:01': 'GEN3', 'inactive_bike_addresses': '02:00:00:00:14:02',
        'bike_name_02:00:00:00:14:02': 'Adventure 390', 'bike_gen_02:00:00:00:14:02': 'GEN2',
    }
    for key, value in values.items():
        if isinstance(value, bool): ET.SubElement(prefs, 'boolean', name=key, value=str(value).lower())
        else: ET.SubElement(prefs, 'string', name=key).text = value
    ui['write_prefs'](ET.tostring(prefs, encoding='unicode'))
    launch()
    tab('Ride'); tap('Preview dashboard')
    assert ui['find']('Preview') is not None
    ui['shot']('01-navigation')
    tab('Garage'); ui['shot']('02-garage')
    tab('Apps'); tap('More apps'); ui['shot']('03-navigation-apps')
    tab('Settings')
    for _ in range(10):
        if ui['find']('Handlebar button mappings') is not None: break
        ui['swipe']()
    tap('Handlebar button mappings'); ui['shot']('04-handlebar')
    (OUT / 'capture.json').write_text(json.dumps({
        'package': 'app.ktmnav', 'version': '1.4.1', 'dimensions': [1080, 2340],
        'source': 'Android emulator screen capture',
        'sample_data': 'Two saved demo motorcycles; dashboard is explicitly in Preview mode.',
        'screens': sorted(p.name for p in OUT.glob('*.png') if '_framed' not in p.name),
    }, indent=2) + '\n')
    print('Captured four real app screens for fastlane frameit.', flush=True)
finally:
    if original_prefs.strip().startswith('<?xml') or original_prefs.strip().startswith('<map'):
        ui['write_prefs'](original_prefs)
    adb('shell', 'settings', 'put', 'secure', 'enabled_notification_listeners', old_listeners)
    if not whitelisted: adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '-app.ktmnav', check=False)
    adb('shell', 'am', 'broadcast', '-a', 'com.android.systemui.demo', '--es', 'command', 'exit')
    import re
    override = re.search(r'Override size: (\d+x\d+)', original_size)
    adb('shell', 'wm', 'size', override.group(1) if override else 'reset')
    adb('shell', 'cmd', 'overlay', 'disable', 'com.android.internal.display.cutout.emulation.hole')
