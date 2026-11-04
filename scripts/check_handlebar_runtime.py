#!/usr/bin/env python3
"""Exercise actual phone action APIs and encrypted synthetic input on the task emulator only."""
import subprocess,time
from pathlib import Path
A=['adb','-s','emulator-5554'];OUT=Path(__file__).resolve().parents[1]/'docs'/'verification-1.4';OUT.mkdir(parents=True,exist_ok=True)
service='app.ktmnav/app.ktmnav.backend.HandlebarAccessibilityService';listener='app.ktmnav/app.ktmnav.backend.NavListener'
def adb(*args):return subprocess.check_output(A+list(args),stderr=subprocess.STDOUT).decode()
assert adb('shell','getprop','ro.kernel.qemu').strip()=='1','Only the task emulator may be configured by this script'
old_services=adb('shell','settings','get','secure','enabled_accessibility_services').strip();old_enabled=adb('shell','settings','get','secure','accessibility_enabled').strip()
old_listeners=adb('shell','settings','get','secure','enabled_notification_listeners').strip()
def restore(key,value):adb('shell','settings','delete' if value=='null' else 'put','secure',key,*([]if value=='null'else[value]))
def instrument(denied=False):
 args=['shell','am','instrument','-w','-e','remapping_only','true']
 if denied:args+=['-e','remapping_denied','true']
 if denied:result=adb(*args,'app.ktmnav.test/app.ktmnav.SmokeInstrumentation')
 else:
  # am instrument restarts the target process; rebind only this task emulator's service afterward.
  process=subprocess.Popen(A+args+['app.ktmnav.test/app.ktmnav.SmokeInstrumentation'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  time.sleep(1)
  adb('shell','settings','put','secure','enabled_accessibility_services','null')
  adb('shell','settings','put','secure','enabled_accessibility_services',service)
  adb('shell','settings','put','secure','accessibility_enabled','1')
  result=process.communicate(timeout=90)[0].decode()
 (OUT/('handlebar-denied.txt' if denied else 'handlebar-runtime.txt')).write_text(result)
 assert 'PASS:' in result and 'FAIL:' not in result and 'INSTRUMENTATION_STATUS_CODE: -2' not in result,result
 print(result)
try:
 adb('shell','settings','put','secure','enabled_accessibility_services','null');adb('shell','settings','put','secure','accessibility_enabled','0')
 adb('shell','cmd','notification','disallow_listener',listener);time.sleep(1)
 instrument(denied=True)
 adb('shell','input','keyevent','KEYCODE_WAKEUP');adb('shell','wm','dismiss-keyguard')
 adb('shell','settings','put','secure','enabled_accessibility_services',service);adb('shell','settings','put','secure','accessibility_enabled','1')
 adb('shell','cmd','notification','allow_listener',listener);time.sleep(1)
 instrument()
finally:
 restore('enabled_accessibility_services',old_services);restore('accessibility_enabled',old_enabled)
 if listener not in old_listeners:adb('shell','cmd','notification','disallow_listener',listener)
