#!/usr/bin/env python3
"""KTMnav 1.3 feature screens: messages, calls, handlebar modes, ride tools, rides, OBD-II, symbol test, translations."""
import argparse, json, re, subprocess, time, xml.etree.ElementTree as E
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--adb', default='adb')
parser.add_argument('--skip-i18n', action='store_true')
parser.add_argument('--out', default=None, help='screenshot folder (default docs/verification-1.4/features-ui)')
args = parser.parse_args()
A = [args.adb, '-s', args.serial]
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(args.out) if args.out else ROOT / 'docs' / 'verification-1.4' / 'features-ui'
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'app.ktmnav'
checks = []

def adb(*cmd, data=None):
    return subprocess.run(A + list(cmd), input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout

def tree():
    for _ in range(4):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/features-ui.xml')
            return E.fromstring(adb('exec-out', 'cat', '/sdcard/features-ui.xml'))
        except (E.ParseError, subprocess.CalledProcessError):
            time.sleep(.6)
    raise AssertionError('Could not read the accessibility tree')

def bounds(n):
    b = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
    return b if len(b) == 4 and b[2] > b[0] and b[3] > b[1] else None

def nodes(text, root=None, contains=False):
    out = []
    for n in sorted((root if root is not None else tree()).iter('node'), key=lambda n: n.get('resource-id') != PKG + ':id/nav_tab'):
        values = ((n.get('text') or '').casefold(), (n.get('content-desc') or '').casefold())
        hit = any(text.casefold() in v for v in values) if contains else text.casefold() in values
        if hit and bounds(n):
            out.append(n)
    return out

def find(text, contains=False, root=None):
    found = nodes(text, root, contains)
    return next((n for n in found if n.get('checkable') == 'true'), found[0] if found else None)

def has(text, contains=False):
    return find(text, contains) is not None

def expect(text, contains=False, name=None):
    assert find(text, contains) is not None, 'Missing ' + text
    checks.append(name or text)
    print('ok:', name or text, flush=True)

def tap_node(n):
    x1, y1, x2, y2 = bounds(n)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.6)

def tap(text, contains=False):
    n = visible_node(text, contains)[0]
    if n is None:
        n = find(text, contains)
    assert n is not None, 'Missing ' + text
    tap_node(n)

def size():
    m = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size').decode())[-1]
    w, h = int(m[0]), int(m[1])
    rot = adb('shell', 'settings', 'get', 'system', 'user_rotation').decode().strip()
    return (h, w) if rot in ('1', '3') else (w, h)

def swipe(up=True):
    w, h = size()
    x = str(w // 2)
    a, b = (str(int(h * .75)), str(int(h * .35))) if up else (str(int(h * .35)), str(int(h * .75)))
    adb('shell', 'input', 'swipe', x, a, x, b, '900')
    time.sleep(.4)

def viewport(root, target):
    parents = {c: p for p in root.iter() for c in p}
    n = target
    while n in parents:
        n = parents[n]
        if n.get('scrollable') == 'true' and bounds(n):
            return bounds(n)
    w, h = size()
    return [0, 0, w, int(h * .9)]

def visible_node(text, contains=False):
    root = tree()
    found = nodes(text, root, contains)
    pick = next((n for n in found if n.get('checkable') == 'true'), found[0] if found else None)
    if pick is None:
        return None, root
    b, v = bounds(pick), viewport(root, pick)
    return (pick if b[1] >= v[1] + 4 and b[3] <= v[3] - 24 else None), root

def reveal(text, contains=False, limit=25):
    last = None
    for _ in range(limit):
        n, root = visible_node(text, contains)
        if n is not None:
            return n
        snapshot = E.tostring(root)
        if snapshot == last:
            break
        last = snapshot
        swipe()
    raise AssertionError('Could not reveal ' + text)

def top(limit=30):
    w, h = size()
    for _ in range(limit):
        before = E.tostring(tree())
        adb('shell', 'input', 'swipe', str(w // 2), str(int(h * .3)), str(w // 2), str(int(h * .8)), '150')
        time.sleep(.6)
        if E.tostring(tree()) == before:
            return

def enabled(text, contains=False):
    n = find(text, contains)
    assert n is not None, 'Missing ' + text
    return n.get('enabled') == 'true'

def checked(text):
    n = find(text)
    assert n is not None, 'Missing ' + text
    return n.get('checked') == 'true'

def shot(name):
    time.sleep(.4)
    (OUT / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p'))
    (OUT / (name + '.xml')).write_bytes(E.tostring(tree(), encoding='utf-8'))

def no_clipping(name):
    w, _ = size()
    for n in tree().iter('node'):
        b = bounds(n)
        if b and n.get('package') == PKG and (n.get('text') or n.get('content-desc')):
            assert b[0] >= 0 and b[2] <= w, f'{name}: {n.get("text")!r} exceeds screen width {b}'
    checks.append(name + ' text within screen width')

def field(desc):
    for _ in range(40):
        for n in tree().iter('node'):
            if n.get('class') == 'android.widget.EditText' and (n.get('content-desc') or '') == desc and bounds(n) and bounds(n)[3] < size()[1] * .85:
                return n
        swipe()
    raise AssertionError('Missing field ' + desc)

def type_into(field_desc, value):
    hide_keyboard()
    n = field(field_desc)
    tap_node(n)
    adb('shell', 'input', 'keyevent', 'KEYCODE_MOVE_END')
    adb('shell', 'input', 'keyevent', *(['KEYCODE_DEL'] * 60))
    if value:
        adb('shell', "input text '" + value.replace(' ', '%s') + "'")
    time.sleep(.3)

def hide_keyboard_impl():
    if 'mInputShown=true' in adb('shell', 'dumpsys', 'input_method').decode():
        adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
        time.sleep(.4)

def hide_keyboard():
    hide_keyboard_impl()

def close():
    hide_keyboard()
    tap('Close')

def launch():
    adb('shell', 'am', 'start', '-S', '-W', '-n', PKG + '/.MainActivity')
    time.sleep(1)
    if has('Get started'):
        tap('Get started')

def settings_entry(label):
    tap('Settings')
    top()
    reveal(label)
    tap(label)
    time.sleep(.4)

def dismiss_permission(allow=False):
    for _ in range(10):
        root = tree()
        ids = {n.get('resource-id') for n in root.iter('node')}
        deny = [i for i in ids if i and i.endswith('permission_deny_button')]
        allow_ids = [i for i in ids if i and (i.endswith('permission_allow_foreground_only_button') or i.endswith('permission_allow_button'))]
        if deny or allow_ids:
            target = (allow_ids if allow else deny)[0]
            n = next(x for x in root.iter('node') if x.get('resource-id') == target)
            tap_node(n)
            return True
        time.sleep(.5)
    return False

def seed_ride():
    start = time.time() - 26 * 3600
    stamp = time.strftime('%Y%m%d-%H%M%S', time.localtime(start))
    pts = []
    for i in range(240):
        lat = 47.2692 + i * 0.00022
        lon = 11.4041 + 0.004 * __import__('math').sin(i / 18)
        speed = [22, 48, 75, 104][(i // 60) % 4]
        t = time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime(start + i * 5))
        pts.append(f'<trkpt lat="{lat:.7f}" lon="{lon:.7f}"><ele>{574 + i * .4:.1f}</ele><time>{t}</time><extensions><kn:speed>{speed:.1f}</kn:speed></extensions></trkpt>')
    gpx = ('<?xml version="1.0" encoding="UTF-8"?>\n<gpx version="1.1" creator="KTMnav" xmlns="http://www.topografix.com/GPX/1/1" xmlns:kn="urn:ktmnav:gpx:1">\n'
           '<trk><name>Synthetic test ride</name><trkseg>\n' + '\n'.join(pts) + '\n</trkseg></trk>\n</gpx>\n')
    name = f'ride-{stamp}.gpx'
    adb('shell', 'run-as', PKG, 'mkdir', '-p', 'files/rides')
    adb('exec-in', 'run-as', PKG, 'sh', '-c', f'cat > files/rides/{name}', data=gpx.encode())
    return name

def i18n_parity():
    base = ROOT / 'app' / 'src' / 'main' / 'assets' / 'i18n'
    dicts = {p.stem: json.loads(p.read_text(encoding='utf-8')) for p in base.glob('*.json')}
    assert set(dicts) == {'en', 'de', 'es', 'fr', 'it', 'pt-BR', 'pt-PT'}, sorted(dicts)
    keys = set(dicts['en'])
    for tag, d in dicts.items():
        assert set(d) == keys, f'{tag} key mismatch: {sorted(keys ^ set(d))[:10]}'
        empty = [k for k, v in d.items() if not str(v).strip()]
        assert not empty, f'{tag} empty values: {empty[:5]}'
        same = [k for k, v in dicts['en'].items() if k != v]
        assert not same, f'en must map keys to themselves: {same[:5]}'
    checks.append(f'seven dictionaries share {len(keys)} keys with non-empty values')
    return dicts

restore = {
    'font_scale': adb('shell', 'settings', 'get', 'system', 'font_scale').decode().strip(),
    'accelerometer_rotation': adb('shell', 'settings', 'get', 'system', 'accelerometer_rotation').decode().strip(),
    'user_rotation': adb('shell', 'settings', 'get', 'system', 'user_rotation').decode().strip(),
}
try:
    dicts = None if args.skip_i18n else i18n_parity()
    adb('logcat', '-c')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    adb('shell', 'pm', 'clear', PKG)
    for perm in ['ACCESS_FINE_LOCATION', 'ACCESS_COARSE_LOCATION', 'READ_PHONE_STATE', 'ANSWER_PHONE_CALLS']:
        subprocess.run(A + ['shell', 'pm', 'revoke', PKG, 'android.permission.' + perm], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for perm in ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS']:
        subprocess.run(A + ['shell', 'pm', 'grant', PKG, 'android.permission.' + perm], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    launch()

    tap('Settings')
    reveal('Riding features')
    for label in ['Messages on dashboard', 'Calls', 'Ride tools', 'OBD-II adapter (read-only)', 'Dashboard symbol test & calibration']:
        reveal(label)
        expect(label, name='Settings entry: ' + label)
    shot('settings-riding-features')

    settings_entry('Messages on dashboard')
    expect('People nearby can read them. Processed on this phone only.', name='concise messages privacy disclosure at the opt-in switch')
    assert not checked('Show messages on dashboard'), 'Messages must be off by default'
    tap('Show messages on dashboard')
    expect('Show messages on your dashboard?', name='message privacy opt-in confirmation')
    tap('Cancel')
    assert not checked('Show messages on dashboard'), 'Cancelled opt-in left messages on'
    tap('Show messages on dashboard')
    tap('Turn on')
    assert checked('Show messages on dashboard'), 'Opt-in did not enable messages'
    checks.append('messages master opt-in persists only after confirmation')
    expect('Notification access needed')
    shot('messages-top')
    reveal('WhatsApp\nNot installed')
    expect('WhatsApp\nNot installed', name='per-app allowlist shows install state')
    tap('WhatsApp\nNot installed')
    assert not checked('WhatsApp\nNot installed'), 'Allowlist checkbox did not toggle'
    tap('WhatsApp\nNot installed')
    reveal('Hide message text')
    tap('Hide message text')
    assert checked('Hide message text')
    reveal('Mute 30 min')
    expect('Not muted')
    tap('Mute 30 min')
    time.sleep(1.2)
    expect('Muted · 30 min left', name='quick mute shows remaining time')
    assert enabled('Unmute')
    tap('Unmute')
    time.sleep(1.2)
    expect('Not muted', name='unmute clears quiet time')
    reveal('Send test message')
    assert not enabled('Send test message'), 'Test message must be disabled while disconnected'
    expect('Connect your motorcycle to send a test message.', name='send test disabled with reason while disconnected')
    reveal('Track changes on dashboard')
    assert checked('Track changes on dashboard'), 'Track banner defaults on'
    shot('messages-bottom')
    close()

    settings_entry('Calls')
    expect('SET answers, BACK silences.')
    expect('Read phone state: Not allowed', contains=True, name='phone permission status shown')
    shot('calls')
    reveal('Allow phone access')
    tap('Allow phone access')
    expect('Allow phone access?', name='phone permission disclosure precedes Android prompt')
    tap('Continue')
    assert dismiss_permission(False), 'Android phone permission dialog did not appear'
    checks.append('native phone permission dialog shown and denied')
    time.sleep(1.2)
    if has('Permission not granted'):
        tap('Later')
    expect('Answer calls: Not allowed', contains=True, name='denied phone permission keeps status')
    close()

    settings_entry('Handlebar button mappings')
    expect('Button mode: Custom')
    expect('UP · Scroll up', name='single-press row keeps legacy label')
    reveal('UP long press · Change button mode (fixed)')
    assert not enabled('UP long press · Change button mode (fixed)')
    checks.append('reserved long-press UP shown fixed and disabled')
    reveal('SET double tap · No action')
    tap('SET double tap · No action')
    reveal('Play / pause')
    tap('Play / pause')
    reveal('SET double tap · Play / pause')
    checks.append('double-tap assignment saved in Custom mode')
    reveal('SET long press · Same as tap')
    expect('SET long press · Same as tap', name='unassigned long press falls back to tap')
    shot('handlebar-gestures')
    top()
    reveal('Button mode: Custom')
    tap('Button mode: Custom')
    tap('Music', contains=True)
    expect('Button mode: Music', name='mode selector switches to Music preset')
    expect('Assignments in this mode')
    expect('UP · Volume up')
    shot('handlebar-music-mode')
    tap('Button mode: Music')
    tap('Custom', contains=True)
    expect('Button mode: Custom')
    reveal('Navigation app: Google Maps')
    tap('Navigation app: Google Maps')
    expect('Navigation app for long-press DOWN', name='preferred navigation app chooser')
    tap('Google Maps')
    reveal('Allow screen controls')
    tap('Allow screen controls')
    expect('long-presses its position on the screen', contains=True, name='screen-control disclosure mentions long press and touch fallback')
    tap('Cancel')
    reveal('Restore default mappings')
    tap('Restore default mappings')
    tap('Restore')
    close()

    settings_entry('Ride tools')
    expect('Location: Not allowed', name='location permission status shown')
    expect('Allow location access')
    shot('ride-tools-top')
    reveal('Beep before turns')
    tap('Beep before turns')
    assert checked('Beep before turns')
    reveal('Beeper volume: 60%')
    assert any(n.get('class') == 'android.widget.SeekBar' for n in tree().iter('node')), 'Volume SeekBar missing'
    checks.append('beeper volume SeekBar with value label')
    type_into('Speed limit (km/h)', '10')
    hide_keyboard()
    reveal('Save speed limit')
    tap('Save speed limit')
    hide_keyboard()
    reveal('Enter a whole number from 20 to 250 km/h.')
    checks.append('speed limit validation error near field')
    type_into('Speed limit (km/h)', '120')
    hide_keyboard()
    reveal('Save speed limit')
    tap('Save speed limit')
    hide_keyboard()
    assert find('Enter a whole number from 20 to 250 km/h.') is None, 'Valid limit kept the error'
    checks.append('valid speed limit clears error')
    reveal('Recording: Off')
    reveal('Start recording')
    assert not enabled('Start recording'), 'Manual recording must be disabled with recording Off'
    tap('Recording: Off')
    tap('Manual')
    expect('Allow location access?', name='location disclosure before Android prompt')
    tap('Continue')
    assert dismiss_permission(False), 'Android location permission dialog did not appear'
    time.sleep(1.2)
    if has('Permission not granted'):
        tap('Later')
    reveal('Recording: Manual')
    assert not enabled('Start recording'), 'Manual recording must stay disabled without location'
    checks.append('manual recording disabled without location permission')
    type_into('Coordinates or Google Maps link', 'not a place')
    hide_keyboard()
    reveal('Save waypoint')
    tap('Save waypoint')
    hide_keyboard()
    reveal('Not a location', contains=True)
    checks.append('waypoint validation error near field')
    shot('ride-tools-waypoint-error')
    type_into('Coordinates or Google Maps link', 'https://www.google.com/maps/place/Innsbruck/@47.2692,11.4041,14z')
    type_into('Waypoint name (optional)', 'Innsbruck')
    hide_keyboard()
    reveal('Save waypoint')
    tap('Save waypoint')
    hide_keyboard()
    time.sleep(1.2)
    reveal('Saved waypoint: Innsbruck · 47.269200,11.404100')
    checks.append('Google Maps link accepted as waypoint')
    assert enabled('Navigate to waypoint')
    tap('Clear waypoint')
    time.sleep(1.2)
    reveal('No waypoint saved.')
    assert not enabled('Navigate to waypoint')
    checks.append('waypoint clear disables navigation')
    reveal('View rides')
    tap('View rides')
    time.sleep(1)
    expect('No rides yet', name='rides empty state with guidance')
    shot('rides-empty')
    tap('Close')
    close()

    adb('shell', 'am', 'force-stop', PKG)
    seed_ride()
    launch()
    settings_entry('Ride tools')
    reveal('View rides')
    tap('View rides')
    time.sleep(1.2)
    row = find('km/h', contains=True)
    assert row is not None, 'Seeded ride missing'
    tap('Max', contains=True)
    time.sleep(1.5)
    expect('Track colours', name='ride detail legend')
    expect('Under 30 km/h')
    expect('90 km/h and faster')
    assert find('Ride track,', contains=True) is not None, 'Ride track view lacks accessible description'
    shot('ride-detail')
    reveal('Delete ride')
    tap('Delete ride')
    expect('Delete this ride?', name='delete ride confirmation')
    tap('Delete')
    time.sleep(1.5)
    expect('No rides yet', name='ride deleted after confirmation')
    tap('Close')
    close()

    settings_entry('OBD-II adapter (read-only)')
    expect('6-pin', contains=True, name='OBD explains 6-pin lead and ELM327')
    expect('It never clears fault codes', contains=True, name='OBD read-only statement')
    assert not checked('Use an OBD-II adapter')
    expect('Turn on the OBD-II adapter switch to scan, connect and read data.')
    reveal('Connect')
    assert not enabled('Connect'), 'Connect must be disabled while OBD is off'
    reveal('Scan for adapters')
    assert not enabled('Scan for adapters'), 'Scan must be disabled while OBD is off'
    checks.append('OBD controls disabled while off')
    shot('obd-disabled')
    top()
    tap('Use an OBD-II adapter')
    reveal('Scan for adapters')
    assert enabled('Scan for adapters')
    tap('Scan for adapters')
    expect('Stop scan', name='scan shows loading state')
    for _ in range(30):
        if has('No adapters found', contains=True) or has('Scan stopped', contains=True) or has('Scan complete', contains=True):
            break
        time.sleep(1)
    assert has('No adapters found', contains=True) or has('Scan stopped', contains=True) or has('Scan complete', contains=True), 'Scan never finished'
    checks.append('scan reports result after loading')
    reveal('Pause live data')
    tap('Pause live data')
    expect('Resume live data', name='live data pause/resume')
    tap('Resume live data')
    reveal('(estimated)', contains=True)
    checks.append('gear labelled (estimated)')
    reveal('Read fault codes')
    assert not enabled('Read fault codes'), 'Fault-code read requires a connection'
    reveal('Gear profile: Automatic (learned when ready)')
    tap('Gear profile: Automatic (learned when ready)')
    learned = find('Learned from your rides', contains=True)
    assert learned is not None and learned.get('enabled') == 'false', 'Learned profile must be disabled until learned'
    expect('unverified', contains=True, name='unverified gear profile marked')
    checks.append('learned gear profile disabled until ready')
    shot('obd-gear-profile')
    tap('Cancel')
    reveal('Learning gears: 0 / 400 steady samples')
    checks.append('gear learning progress shown')
    reveal('Show speed & gear on dash')
    shot('obd-enabled')
    top()
    tap('Use an OBD-II adapter')
    close()

    settings_entry('Dashboard symbol test & calibration')
    expect('Connect a Gen 3 motorcycle to show symbols on the dashboard.')
    assert not enabled('End symbol test')
    tap('Test turn symbols')
    expect('GO_STRAIGHT')
    expect('ID 2')
    assert not enabled('Show GO_STRAIGHT on dash'), 'Symbol test must be disabled while disconnected'
    checks.append('symbol test buttons disabled when disconnected')
    shot('symbol-test')
    tap('Close')
    tap('Test banner icons')
    expect('INFORMATION')
    assert not enabled('Show INFORMATION on dash')
    tap('Close')
    tap('Arrow calibration')
    expect('No unrecognised arrows yet', contains=True, name='calibration empty state')
    expect('No saved calibrations.')
    assert not enabled('Clear all calibrations')
    shot('arrow-calibration')
    tap('Close')
    close()

    settings_entry('Dashboard data & controls')
    reveal('Vehicle profile')
    reveal('Not identified yet')
    reveal('Telemetry status')
    checks.append('dashboard data shows vehicle profile and telemetry status')
    shot('dashboard-data')
    close()

    settings_entry('Included features')
    reveal('New in 1.3 · all free')
    reveal('Button modes & gestures', contains=True)
    checks.append('included features list 1.3 additions')
    shot('included-features')
    close()

    settings_entry('About KTMnav')
    expect('KTMnav 1.4', name='About shows version 1.4')
    tap('Close')

    tap('Settings')
    top()
    reveal('Theme')
    tap('Theme')
    tap('Daylight · high contrast')
    time.sleep(1)
    settings_entry('Ride tools')
    shot('ride-tools-daylight')
    close()
    settings_entry('Messages on dashboard')
    shot('messages-daylight')
    close()
    tap('Settings')
    top()
    reveal('Theme')
    tap('Theme')
    tap('KTM · charcoal & orange')
    time.sleep(1)

    adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    time.sleep(1)
    launch()
    settings_entry('Ride tools')
    no_clipping('ride tools at 2x text')
    shot('ride-tools-2x-text')
    reveal('Save speed limit')
    shot('ride-tools-2x-text-limit')
    close()
    settings_entry('OBD-II adapter (read-only)')
    no_clipping('OBD at 2x text')
    shot('obd-2x-text')
    close()
    settings_entry('Dashboard symbol test & calibration')
    tap('Test turn symbols')
    no_clipping('symbol list at 2x text')
    shot('symbol-test-2x-text')
    tap('Close')
    close()
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')

    adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    time.sleep(1.5)
    launch()
    settings_entry('Messages on dashboard')
    no_clipping('messages landscape')
    shot('messages-landscape')
    close()
    settings_entry('Handlebar button mappings')
    no_clipping('handlebar landscape')
    shot('handlebar-landscape')
    close()
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    time.sleep(1)

    if dicts is None:
        raise SystemExit(0)
    launch()
    tap('Settings')
    top()
    reveal('App language')
    tap('App language')
    tap('Deutsch')
    time.sleep(1)
    entry = dicts['de']['Messages on dashboard']
    tap('Einstellungen')
    top()
    reveal(entry)
    tap(entry)
    expect(dicts['de']['Show messages on dashboard'], name='German translation shown in Messages screen')
    shot('messages-german')
    tap(dicts['de']['Close'])
    top()
    reveal(dicts['de']['App language'])
    tap(dicts['de']['App language'])
    tap('English')
    time.sleep(1)

    crash = adb('logcat', '-d', '-b', 'crash').decode()
    assert 'Process: app.ktmnav' not in crash, crash
    checks.append('no app crash')
    result = {'passed': True, 'count': len(checks), 'checks': checks, 'physical_bike_tested': False, 'obd_adapter_tested': False}
    (OUT / 'features-ui-results.json').write_text(json.dumps(result, indent=2))
    print(f'PASS: {len(checks)} feature UI checks; screenshots in {OUT}')
finally:
    for key, value in restore.items():
        adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))
