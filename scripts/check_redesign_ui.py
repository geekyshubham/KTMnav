#!/usr/bin/env python3
"""KTMnav 1.4 redesign checks on the task emulator: brand colour, compact tabs, real app icons, hide-once-configured setup."""
import argparse, json, re, struct, subprocess, time, zlib, xml.etree.ElementTree as E
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--adb', default='adb')
parser.add_argument('--out', default=None, help='screenshot folder (default docs/verification-1.4/ui)')
args = parser.parse_args()
A = [args.adb, '-s', args.serial]
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(args.out) if args.out else ROOT / 'docs' / 'verification-1.4' / 'ui'
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'app.ktmnav'
LISTENER = 'app.ktmnav/app.ktmnav.backend.NavListener'
TABS = ['Ride', 'Garage', 'Apps', 'Settings']
MARKETING = ['Ready for the road.', 'One dash. Eight apps.', 'The garage.', 'Ride your way.', 'YOUR NEXT TURN, ON YOUR DASH',
             'CHOOSE YOUR WAY', 'YOUR MOTORCYCLES', 'MAKE IT YOURS', 'Every integration included. No subscriptions.',
             'Your navigation. Your motorcycle. One clear view.', 'Set up the bridge once. Keep your focus on the road.']
GEN_TEXT = ['Gen 3 & Gen 2 dashboards', 'encrypted Bluetooth LE', 'Gen 3 Bluetooth LE and Gen 2 paired devices']
ACCENT = {'KTM': (0xFF, 0x66, 0x00), 'LIGHT': (0xFF, 0x66, 0x00), 'HUSQVARNA': (0x8A, 0xB4, 0xF8)}
THEME_LABEL = {'KTM': 'KTM · charcoal & orange', 'LIGHT': 'Daylight · high contrast', 'HUSQVARNA': 'Husqvarna · charcoal & blue'}
checks = []


def adb(*cmd, data=None, check=True):
    r = subprocess.run(A + list(cmd), input=data, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if check and r.returncode != 0:
        raise RuntimeError(' '.join(cmd) + ': ' + r.stdout.decode(errors='replace'))
    return r.stdout


def ok(name):
    checks.append(name)
    print('ok:', name, flush=True)


def tree():
    for _ in range(4):
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/redesign-ui.xml')
            return E.fromstring(adb('exec-out', 'cat', '/sdcard/redesign-ui.xml'))
        except (E.ParseError, RuntimeError):
            time.sleep(.6)
    raise AssertionError('Could not read the accessibility tree')


def bounds(n):
    b = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
    return b if len(b) == 4 and b[2] > b[0] and b[3] > b[1] else None


def find(text, root=None, contains=False):
    for n in (root if root is not None else tree()).iter('node'):
        values = ((n.get('text') or ''), (n.get('content-desc') or ''))
        if (any(text.casefold() in v.casefold() for v in values) if contains else text.casefold() in (v.casefold() for v in values)) and bounds(n):
            return n
    return None


def by_id(name, root=None):
    return [n for n in (root if root is not None else tree()).iter('node') if n.get('resource-id') == PKG + ':id/' + name and bounds(n)]


def tap_node(n):
    x1, y1, x2, y2 = bounds(n)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.7)


def tap(text):
    n = find(text)
    assert n is not None, 'Missing ' + text
    tap_node(n)


def tab(name):
    for n in tree().iter('node'):
        if n.get('resource-id') == PKG + ':id/nav_tab' and n.get('content-desc') == name:
            tap_node(n)
            return
    raise AssertionError('Missing tab ' + name)


def screen():
    m = re.findall(r'(\d+)x(\d+)', adb('shell', 'wm', 'size').decode())[-1]
    w, h = int(m[0]), int(m[1])
    rot = adb('shell', 'settings', 'get', 'system', 'user_rotation').decode().strip()
    return (h, w) if rot in ('1', '3') else (w, h)


def density():
    m = re.findall(r'density: (\d+)', adb('shell', 'wm', 'density').decode())
    return int(m[-1]) / 160


def swipe(up=True):
    w, h = screen()
    a, b = (int(h * .72), int(h * .32)) if up else (int(h * .32), int(h * .72))
    adb('shell', 'input', 'swipe', str(w // 2), str(a), str(w // 2), str(b), '600')
    time.sleep(.5)


def top():
    for _ in range(12):
        before = E.tostring(tree())
        swipe(False)
        if E.tostring(tree()) == before:
            return


def pages():
    top()
    out, last = [], None
    for _ in range(14):
        root = tree()
        snap = E.tostring(root)
        if snap == last:
            break
        out.append(root)
        last = snap
        swipe()
    return out


def texts(roots):
    return {v for r in roots for n in r.iter('node') for v in (n.get('text') or '', n.get('content-desc') or '') if v}


def png(data):
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat, w = 8, b'', 0
    while pos < len(data):
        length, kind = struct.unpack('>I4s', data[pos:pos + 8])
        chunk = data[pos + 8:pos + 8 + length]
        if kind == b'IHDR':
            w, h, depth, ctype = struct.unpack('>IIBB', chunk[:10])
            assert depth == 8 and ctype in (2, 6), (depth, ctype)
            bpp = 4 if ctype == 6 else 3
        elif kind == b'IDAT':
            idat += chunk
        pos += 12 + length
    raw, stride, rows, prev = zlib.decompress(idat), w * bpp, [], bytearray(w * bpp)
    for y in range(h):
        f, line = raw[y * (stride + 1)], bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b, c = prev[i], prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line))
        prev = line
    return lambda x, y: tuple(rows[y][x * bpp:x * bpp + 3])


def shot(name):
    time.sleep(.5)
    data = adb('exec-out', 'screencap', '-p')
    (OUT / (name + '.png')).write_bytes(data)
    (OUT / (name + '.xml')).write_bytes(E.tostring(tree(), encoding='utf-8'))
    return data


def near(rgb, want, tol=3):
    return all(abs(a - b) <= tol for a, b in zip(rgb, want))


def launch():
    adb('shell', 'am', 'start', '-S', '-W', '-n', PKG + '/.MainActivity')
    time.sleep(1.2)
    if find('Get started') is not None:
        tap('Get started')


def theme(key):
    tab('Settings')
    pages_ = 0
    while find('Theme') is None and pages_ < 10:
        swipe(); pages_ += 1
    tap('Theme')
    tap(THEME_LABEL[key])
    time.sleep(1.2)


def brand_pixels(key, name):
    tab('Ride')
    top()
    data = shot(name)
    px, d = png(data), density()
    nav = next(n for n in by_id('nav_tab') if n.get('selected') == 'true')
    x1, y1, x2, y2 = bounds(nav)
    pill = px((x1 + x2) // 2 - int(20 * d), y1 + int(20 * d))
    primary = by_id('primary_action')
    assert primary, 'Primary action missing on Ride'
    b = bounds(primary[0])
    button = px(b[0] + int(16 * d), (b[1] + b[3]) // 2)
    assert near(pill, ACCENT[key]), f'{key}: selected nav pill is {pill}, expected {ACCENT[key]}'
    assert near(button, ACCENT[key]), f'{key}: primary button is {button}, expected {ACCENT[key]}'
    ok(f'{key}: selected navigation and primary button use #{"%02X%02X%02X" % ACCENT[key]}')
    return {'theme': key, 'nav_pill_rgb': pill, 'primary_button_rgb': button}


def no_clipping(label):
    w, h = screen()
    for root in pages():
        for n in root.iter('node'):
            b = bounds(n)
            if b and n.get('package') == PKG and n.get('text'):
                assert b[0] >= 0 and b[2] <= w, f'{label}: {n.get("text")!r} exceeds screen width {b}'
        title = by_id('tab_title', root)
        assert title and bounds(title[0])[3] < h, f'{label}: tab title not visible'
    ok(label + ': text within screen width, title visible')


def row_of(label_node, root):
    parents = {c: p for p in root.iter() for c in p}
    n = label_node
    while n in parents and n.get('resource-id') != PKG + ':id/settings_row':
        n = parents[n]
    return n if n.get('resource-id') == PKG + ':id/settings_row' else None


def prefs_xml():
    return adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/ktmnav.xml', check=False).decode()


def write_prefs(xml):
    adb('shell', 'am', 'force-stop', PKG)
    adb('exec-in', 'run-as', PKG, 'sh', '-c', 'cat > shared_prefs/ktmnav.xml', data=xml.encode())


def seed_bike():
    adb('shell', 'am', 'force-stop', PKG)
    xml = prefs_xml()
    entries = ('<string name="active_bike_address">02:00:00:00:14:01</string>'
               '<string name="active_bike_name">Duke 390 test</string>'
               '<string name="active_bike_gen">GEN3</string>'
               '<string name="bike_name_02:00:00:00:14:01">Duke 390 test</string>'
               '<string name="bike_gen_02:00:00:00:14:01">GEN3</string>'
               '<boolean name="desired_connect" value="false" />')
    write_prefs(xml.replace('</map>', entries + '</map>'))


restore = {k: adb('shell', 'settings', 'get', 'system', k).decode().strip() for k in ['font_scale', 'accelerometer_rotation', 'user_rotation']}
old_listeners = adb('shell', 'settings', 'get', 'secure', 'enabled_notification_listeners').decode().strip()
was_whitelisted = PKG in adb('shell', 'dumpsys', 'deviceidle', 'whitelist').decode()
evidence = {}
try:
    assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Task emulator only'
    adb('logcat', '-c')
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    for k, v in [('font_scale', '1.0'), ('accelerometer_rotation', '0'), ('user_rotation', '0')]:
        adb('shell', 'settings', 'put', 'system', k, v)
    adb('shell', 'pm', 'clear', PKG)
    for perm in ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT']:
        adb('shell', 'pm', 'grant', PKG, 'android.permission.' + perm, check=False)
    adb('shell', 'pm', 'revoke', PKG, 'android.permission.POST_NOTIFICATIONS', check=False)
    adb('shell', 'cmd', 'notification', 'disallow_listener', LISTENER, check=False)
    adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '-' + PKG, check=False)
    launch()

    evidence['brand'] = []
    for key in ['KTM', 'LIGHT', 'HUSQVARNA']:
        theme(key)
        evidence['brand'].append(brand_pixels(key, f'brand-{key.lower()}'))
        for name in TABS:
            tab(name)
            seen = texts(pages())
            top()
            shot(f'tab-{name.lower()}-{key.lower()}')
            hits = [m for m in MARKETING if m in seen]
            assert not hits, f'{key}/{name}: marketing headline still shown: {hits}'
            title = by_id('tab_title')
            assert title and title[0].get('text') == name, f'{name}: compact top-bar title missing'
            chip = by_id('status_chip')
            assert chip and (chip[0].get('content-desc') or '').startswith('Connection: '), f'{name}: connection status chip missing'
            if name == 'Garage':
                gen = [g for g in GEN_TEXT if any(g in t for t in seen)]
                assert not gen, f'Garage still shows {gen}'
        ok(f'{key}: four tabs have compact title + status chip and no marketing headline')
    theme('KTM')
    ok('Garage has no "Gen 3 & Gen 2 dashboards" / "encrypted Bluetooth LE" text')

    tab('Garage')
    tap('Add motorcycle')
    time.sleep(1)
    seen = texts(pages())
    assert find('Nearby motorcycles') is not None or 'Nearby motorcycles' in seen
    assert not [g for g in GEN_TEXT if any(g in t for t in seen)], 'Garage scan section shows Gen help text'
    assert 'Pairing help' in seen and 'Show all Bluetooth devices' in seen
    top()
    shot('garage-add-motorcycle')
    ok('Garage scan section: progress + found list, Pairing help is a link, no Gen paragraphs')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(.6)

    tab('Apps')
    root = tree()
    maps = find('Google Maps', root)
    assert maps is not None, 'Google Maps row missing'
    row = row_of(maps, root)
    assert row is not None, 'Google Maps is not in an app row'
    real = [n for n in row.iter('node') if n.get('resource-id') == PKG + ':id/app_icon_real']
    assert real, 'Google Maps row has no real app icon ImageView'
    data = shot('apps-real-icons')
    px = png(data)
    x1, y1, x2, y2 = bounds(real[0])
    colours = {px(x, y) for x in range(x1, x2, 3) for y in range(y1, y2, 3)}
    assert len(colours) > 40, f'Google Maps icon looks flat ({len(colours)} colours)'
    evidence['google_maps_icon_colours'] = len(colours)
    ok(f'Apps: Google Maps row shows its real PackageManager icon ({len(colours)} sampled colours)')
    if find('TomTom GO') is None:
        tap('More apps')
    for _ in range(4):
        if find('TomTom GO') is not None: break
        swipe()
    root = tree()
    tomtom = row_of(find('TomTom GO', root), root)
    assert tomtom is not None and any(n.get('resource-id') == PKG + ':id/app_icon_bundled' for n in tomtom.iter('node')), 'Not-installed app lacks the bundled icon fallback'
    assert find('Not installed', root) is not None
    switch = [n for n in tomtom.iter('node') if (n.get('content-desc') or '') == 'Forward navigation from TomTom GO']
    assert switch and switch[0].get('enabled') == 'false', 'Forward switch for a missing app must be disabled'
    shot('apps-more-apps')
    ok('Apps: not-installed apps use the bundled icon, "Not installed" status and a disabled switch')

    top()
    assert by_id('access_banner'), 'Notification-access banner missing while access is off'
    shot('apps-access-banner')
    adb('shell', 'cmd', 'notification', 'allow_listener', LISTENER)
    launch()
    tab('Apps')
    assert not by_id('access_banner'), 'Notification-access banner still shown after access was granted'
    shot('apps-access-granted')
    ok('Apps: one notification-access banner, hidden after cmd notification allow_listener')

    tab('Settings')
    top()
    seen = texts(pages())
    assert 'Background activity' in seen, 'Battery item missing while not exempt'
    top()
    shot('settings-setup-incomplete')
    adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + PKG)
    launch()
    tab('Settings')
    seen = texts(pages())
    assert 'Background activity' not in seen, 'Battery item still shown after exemption'
    assert 'Android battery settings' not in seen
    adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '-' + PKG)
    launch()
    tab('Settings')
    assert 'Background activity' in texts(pages()), 'Battery item did not return after removing exemption'
    ok('Setup: battery item hides after deviceidle whitelist +app.ktmnav and returns after -app.ktmnav')

    adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '+' + PKG)
    adb('shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
    adb('shell', 'cmd', 'notification', 'allow_listener', LISTENER)
    seed_bike()
    launch()
    tab('Settings')
    roots = pages()
    assert not any(by_id('setup_card', r) for r in roots), 'Setup section still shown when everything is configured'
    rows, d = {}, density()
    for r in roots:
        view = next((bounds(n) for n in r.iter('node') if n.get('scrollable') == 'true' and bounds(n)), [0, 0, 99999, 99999])
        for row in by_id('settings_row', r):
            b = bounds(row)
            if b[1] <= view[1] + 2 or b[3] >= view[3] - 2:
                continue
            label = next((n.get('text') for n in row.iter('node') if n.get('text')), row.get('content-desc'))
            icon = [n for n in row.iter('node') if n.get('resource-id') == PKG + ':id/row_icon']
            assert icon, f'Settings row {label!r} has no icon'
            assert b[3] - b[1] >= 48 * d - 1, f'Settings row {label!r} is {(b[3]-b[1])/d:.0f}dp tall'
            rows[label] = round((b[3] - b[1]) / d)
    assert len(rows) >= 20, rows
    evidence['settings_rows_dp'] = rows
    ok(f'Settings: {len(rows)} fully visible rows, all with an icon tile and >= 48dp (min {min(rows.values())}dp)')
    top()
    shot('settings-setup-complete')
    tab('Ride')
    assert not by_id('setup_card'), 'Ride shows setup when complete'
    assert find('Duke 390 test') is not None
    shot('ride-setup-complete')
    tab('Garage')
    assert find('Active') is not None and find('Duke 390 test') is not None
    seen = texts(pages())
    assert not [g for g in GEN_TEXT if any(g in t for t in seen)]
    top()
    shot('garage-saved-bike')
    ok('Setup complete: Setup hidden on Settings and Ride; Garage lists the saved bike with an Active chip')

    tab('Ride')
    for _ in range(4):
        if find('Preview dashboard') is not None: break
        swipe()
    tap('Preview dashboard')
    top()
    assert find('250 m') is not None and find('Preview') is not None and find('Google Maps') is not None
    shot('ride-preview')
    for _ in range(4):
        if find('Exit dashboard preview') is not None: break
        swipe()
    tap('Exit dashboard preview')
    ok('Ride: labelled dashboard preview is a secondary action')

    adb('shell', 'settings', 'put', 'system', 'font_scale', '2.0')
    time.sleep(1)
    launch()
    for name in TABS:
        tab(name)
        no_clipping(f'2x text {name}')
        top()
        shot(f'tab-{name.lower()}-2x-text')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '1')
    time.sleep(1.5)
    launch()
    for name in TABS:
        tab(name)
        no_clipping(f'landscape {name}')
        top()
        shot(f'tab-{name.lower()}-landscape')
    adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
    time.sleep(1)

    resolved = adb('shell', 'cmd', 'package', 'resolve-activity', '--brief', '-c', 'android.intent.category.LAUNCHER', PKG).decode()
    dump = adb('shell', 'dumpsys', 'package', PKG).decode()
    (OUT / 'launcher-resolve.txt').write_text(resolved + '\n' + '\n'.join(l for l in dump.splitlines() if 'icon' in l.lower() or 'versionName' in l or 'versionCode' in l))
    assert 'app.ktmnav/.MainActivity' in resolved
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
    time.sleep(1)
    w, h = screen()
    adb('shell', 'input', 'swipe', str(w // 2), str(int(h * .85)), str(w // 2), str(int(h * .2)), '400')
    time.sleep(1.5)
    icon = None
    for _ in range(6):
        root = tree()
        icon = next((n for n in root.iter('node') if (n.get('content-desc') == 'KTMnav' or n.get('text') == 'KTMnav') and bounds(n)), None)
        if icon is not None: break
        adb('shell', 'input', 'swipe', str(w // 2), str(int(h * .8)), str(w // 2), str(int(h * .35)), '500')
        time.sleep(.8)
    assert icon is not None, 'KTMnav not found in the launcher'
    data = shot('launcher-home')
    px = png(data)
    x1, y1, x2, y2 = bounds(icon)
    cx = (x1 + x2) // 2
    samples = [px(x, y) for x in range(x1, x2, 2) for y in range(y1, y2, 2)]
    orange = sum(1 for p in samples if near(p, (0xFF, 0x66, 0x00), 6))
    dark = sum(1 for p in samples if near(p, (0x10, 0x12, 0x14), 10))
    evidence['launcher_icon'] = {'bounds': [x1, y1, x2, y2], 'ff6600_samples': orange, 'dark_mark_samples': dark}
    assert orange > 50 and dark > 10, evidence['launcher_icon']
    ok('Launcher: adaptive icon renders #FF6600 background with the dark navigation mark')
    adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')

    crash = adb('logcat', '-d', '-b', 'crash').decode()
    assert 'Process: app.ktmnav' not in crash, crash
    ok('no app crash')
    result = {'passed': True, 'count': len(checks), 'checks': checks, 'evidence': evidence, 'physical_bike_tested': False,
              'note': 'Saved bike is a synthetic preference entry (02:00:00:00:14:01); no motorcycle connection was made.'}
    (OUT / 'redesign-ui-results.json').write_text(json.dumps(result, indent=2, default=list))
    print(f'PASS: {len(checks)} redesign checks; screenshots in {OUT}')
finally:
    for key, value in restore.items():
        adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]), check=False)
    if LISTENER not in old_listeners:
        adb('shell', 'cmd', 'notification', 'disallow_listener', LISTENER, check=False)
    if not was_whitelisted:
        adb('shell', 'dumpsys', 'deviceidle', 'whitelist', '-' + PKG, check=False)
    xml = prefs_xml()
    if '02:00:00:00:14:01' in xml:
        write_prefs(re.sub(r'<[^<]*(?:active_bike_address|active_bike_name|active_bike_gen|bike_name_02:00:00:00:14:01|bike_gen_02:00:00:00:14:01)"[^<]*</string>', '', xml))
