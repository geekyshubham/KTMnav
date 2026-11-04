#!/usr/bin/env python3
"""Drive real emulator SMS, calls and GPS while the instrumentation checks KTMnav's handling. Task emulator only."""
import subprocess
import time
from pathlib import Path

A = ['adb', '-s', 'emulator-5554']
OUT = Path(__file__).resolve().parents[1] / 'docs' / 'verification-1.4'
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'app.ktmnav'
LISTENER = 'app.ktmnav/app.ktmnav.backend.NavListener'
PERMS = ['android.permission.READ_PHONE_STATE', 'android.permission.ANSWER_PHONE_CALLS',
         'android.permission.ACCESS_FINE_LOCATION', 'android.permission.ACCESS_COARSE_LOCATION',
         'android.permission.POST_NOTIFICATIONS']
NUMBER = '5551234'


def adb(*args, check=True):
    r = subprocess.run(A + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and r.returncode != 0:
        raise RuntimeError(' '.join(args) + ': ' + r.stdout.decode())
    return r.stdout.decode()


assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == '1', 'Only the task emulator may be configured by this script'
old_sms = adb('shell', 'cmd', 'role', 'get-role-holders', 'android.app.role.SMS').strip()
old_listeners = adb('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners').strip()
for p in PERMS:
    adb('shell', 'pm', 'grant', PKG, p, check=False)
adb('shell', 'cmd', 'role', 'add-role-holder', 'android.app.role.SMS', 'com.google.android.apps.messaging', check=False)
adb('shell', 'pm', 'grant', 'com.google.android.apps.messaging', 'android.permission.POST_NOTIFICATIONS', check=False)
adb('shell', 'cmd', 'notification', 'allow_listener', LISTENER)
adb('shell', 'cmd', 'location', 'set-location-enabled', 'true', check=False)
adb('shell', 'appops', 'set', PKG, 'android:mock_location', 'allow')
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
time.sleep(2)

log = []
proc = subprocess.Popen(A + ['shell', 'am', 'instrument', '-w', '-e', 'features_only', 'true',
                             'app.ktmnav.test/app.ktmnav.SmokeInstrumentation'],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
try:
    for line in proc.stdout:
        log.append(line)
        stage = line.strip().split('stage=', 1)[1] if 'INSTRUMENTATION_STATUS: stage=' in line else None
        if stage == 'rebind_listener':
            adb('shell', 'cmd', 'notification', 'disallow_listener', LISTENER, check=False)
            time.sleep(1)
            adb('shell', 'cmd', 'notification', 'allow_listener', LISTENER)
        elif stage == 'sms':
            time.sleep(1)
            adb('emu', 'sms', 'send', NUMBER, 'Hello rider from the KTMnav test')
        elif stage == 'sms_hidden':
            time.sleep(1)
            adb('emu', 'sms', 'send', NUMBER, 'Second secret message')
        elif stage == 'sms_disabled':
            time.sleep(1)
            adb('emu', 'sms', 'send', NUMBER, 'Third message while disabled')
        elif stage == 'call':
            time.sleep(1)
            adb('emu', 'gsm', 'call', NUMBER)
        elif stage == 'call_end':
            adb('emu', 'gsm', 'cancel', NUMBER, check=False)
    proc.wait(timeout=30)
finally:
    adb('emu', 'gsm', 'cancel', NUMBER, check=False)
    if old_sms and old_sms != 'com.google.android.apps.messaging':
        adb('shell', 'cmd', 'role', 'add-role-holder', 'android.app.role.SMS', old_sms, check=False)
    if LISTENER not in old_listeners:
        adb('shell', 'cmd', 'notification', 'disallow_listener', LISTENER, check=False)
    adb('shell', 'appops', 'set', PKG, 'android:mock_location', 'deny', check=False)
    for p in PERMS[:4]:
        adb('shell', 'pm', 'revoke', PKG, p, check=False)

result = ''.join(log)
(OUT / 'feature-runtime.txt').write_text(result)
assert 'PASS: feature runtime checks' in result and 'FAIL:' not in result, result[-4000:]
print(result[result.index('PASS: feature runtime checks'):])
