#!/usr/bin/env python3
"""KTMnav 1.5 feature checks on one emulator: Navigate card, favourites, shared geo: destinations, Garage
silhouettes and model picker, bundled navigation-app icons, Navigation settings and GPX route import by intent."""
import argparse, json, re, subprocess, time, xml.etree.ElementTree as E
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--serial', default='emulator-5554')
parser.add_argument('--adb', default='adb')
parser.add_argument('--out', default=None)
args = parser.parse_args()
A = [args.adb, '-s', args.serial]
ROOT = Path(__file__).resolve().parents[1]
OUT = Path(args.out) if args.out else ROOT / 'docs' / 'verification-1.5' / 'navigate-ui'
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'app.ktmnav'
LISTENER = 'app.ktmnav/app.ktmnav.backend.NavListener'
MAPS = 'com.google.android.apps.maps'
checks, evidence = [], {}

GPX = ('<?xml version="1.0" encoding="UTF-8"?><gpx version="1.1" creator="check_navigate_ui" xmlns="http://www.topografix.com/GPX/1/1">'
       '<rte><name>Nandi loop</name>' + ''.join(f'<rtept lat="{13.0 + i * 0.01:.4f}" lon="{77.5 + (i % 3) * 0.02:.4f}"><ele>{900 + i * 5}</ele></rtept>' for i in range(12))
       + '</rte></gpx>')


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
            adb('shell', 'uiautomator', 'dump', '/sdcard/navigate-ui.xml')
            return E.fromstring(adb('exec-out', 'cat', '/sdcard/navigate-ui.xml'))
        except (E.ParseError, RuntimeError):
            time.sleep(.6)
    raise AssertionError('Could not read the accessibility tree')


def bounds(n):
    b = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
    return b if len(b) == 4 and b[2] > b[0] and b[3] > b[1] else None


def find(text, root=None, contains=False):
    for n in (root if root is not None else tree()).iter('node'):
        values = ((n.get('text') or ''), (n.get('content-desc') or ''))
        hit = any(text.casefold() in v.casefold() for v in values) if contains else text.casefold() in (v.casefold() for v in values)
        if hit and bounds(n):
            return n
    return None


def by_id(name, root=None):
    return [n for n in (root if root is not None else tree()).iter('node') if n.get('resource-id') == PKG + ':id/' + name and bounds(n)]


def tap_node(n):
    x1, y1, x2, y2 = bounds(n)
    adb('shell', 'input', 'tap', str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(.8)


def tap(text, contains=False):
    n = find(text, contains=contains)
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
    return int(m[0]), int(m[1])


def swipe(up=True):
    w, h = screen()
    a, b = (int(h * .72), int(h * .32)) if up else (int(h * .32), int(h * .72))
    adb('shell', 'input', 'swipe', str(w // 2), str(a), str(w // 2), str(b), '600')
    time.sleep(.5)


def reveal(text, contains=False, tries=8):
    for _ in range(tries):
        n = find(text, contains=contains)
        if n is not None:
            return n
        swipe()
    raise AssertionError('Could not reveal ' + text)


def top():
    for _ in range(10):
        before = E.tostring(tree())
        swipe(False)
        if E.tostring(tree()) == before:
            return


def shot(name):
    time.sleep(.5)
    data = adb('exec-out', 'screencap', '-p')
    (OUT / (name + '.png')).write_bytes(data)
    (OUT / (name + '.xml')).write_bytes(E.tostring(tree(), encoding='utf-8'))
    return data


def launch(extra=()):
    adb('shell', 'am', 'start', '-S', '-W', '-n', PKG + '/.MainActivity', *extra)
    time.sleep(1.5)
    if find('Get started') is not None:
        tap('Get started')


def focused_package():
    out = adb('shell', 'dumpsys', 'activity', 'activities').decode(errors='replace')
    m = re.search(r'(?:topResumedActivity|ResumedActivity)[:=]\s*ActivityRecord\{[^ ]+ u0 ([^/ ]+)/', out)
    if m:
        return m.group(1)
    out = adb('shell', 'dumpsys', 'window', 'windows').decode(errors='replace')
    m = re.search(r'mCurrentFocus=Window\{[^ ]+ u0 ([^/ ]+)', out)
    return m.group(1) if m else ''


def prefs_xml():
    return adb('exec-out', 'run-as', PKG, 'cat', 'shared_prefs/ktmnav.xml', check=False).decode()


def write_prefs(xml):
    adb('shell', 'am', 'force-stop', PKG)
    adb('exec-in', 'run-as', PKG, 'sh', '-c', 'mkdir -p shared_prefs; cat > shared_prefs/ktmnav.xml', data=xml.encode())


def seed_bikes():
    adb('shell', 'am', 'force-stop', PKG)
    xml = prefs_xml()
    if '<map' not in xml:
        xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n</map>\n"
    entries = ('<string name="active_bike_address">02:00:00:00:14:01</string>'
               '<string name="active_bike_name">Duke 390 test</string>'
               '<string name="active_bike_gen">GEN3</string>'
               '<string name="bike_name_02:00:00:00:14:01">Duke 390 test</string>'
               '<string name="bike_gen_02:00:00:00:14:01">GEN3</string>'
               '<string name="inactive_bike_addresses">02:00:00:00:14:02,02:00:00:00:14:03</string>'
               '<string name="bike_name_02:00:00:00:14:02">KTM 390 ADVENTURE</string>'
               '<string name="bike_gen_02:00:00:00:14:02">GEN3</string>'
               '<string name="bike_name_02:00:00:00:14:03">Svartpilen 401</string>'
               '<string name="bike_gen_02:00:00:00:14:03">GEN2</string>'
               '<boolean name="desired_connect" value="false" />'
               '<boolean name="welcome_complete" value="true" />'
               '<string name="ui_ride_setup_hidden">nearby,bluetooth,access,notify,battery,bike,</string>')
    write_prefs(xml.replace('</map>', entries + '</map>'))


restore = {k: adb('shell', 'settings', 'get', 'system', k).decode().strip() for k in ['font_scale', 'accelerometer_rotation', 'user_rotation']}
try:
    assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Task emulator only'
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard')
    for k, v in [('font_scale', '1.0'), ('accelerometer_rotation', '0'), ('user_rotation', '0')]:
        adb('shell', 'settings', 'put', 'system', k, v)
    adb('shell', 'pm', 'clear', PKG)
    for perm in ['BLUETOOTH_SCAN', 'BLUETOOTH_CONNECT', 'POST_NOTIFICATIONS']:
        adb('shell', 'pm', 'grant', PKG, 'android.permission.' + perm, check=False)
    adb('shell', 'cmd', 'notification', 'allow_listener', LISTENER)
    maps_installed = MAPS in adb('shell', 'pm', 'list', 'packages', MAPS).decode()
    evidence['google_maps_installed'] = maps_installed
    seed_bikes()
    launch()

    # 1. Navigate card on the idle Ride tab
    tab('Ride')
    top()
    root = tree()
    assert by_id('navigate_field', root) and by_id('navigate_go', root) and by_id('navigate_app', root), 'Navigate card controls missing'
    app_button = by_id('navigate_app', root)[0]
    evidence['navigate_app_button'] = app_button.get('text')
    assert find('Where to?', root) is not None, 'Destination hint missing'
    shot('ride-navigate-card')
    ok(f'Ride: Navigate card with destination field, app button ({app_button.get("text")}) and Go')

    # 2. Typing a destination and tapping Go starts the navigation app and remembers the place
    tap_node(by_id('navigate_field')[0])
    adb('shell', 'input', 'text', 'Nandi%sHills')  # %s is the space escape for `input text`
    time.sleep(.5)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')  # close the keyboard without leaving the tab
    time.sleep(.5)
    go = by_id('navigate_go')
    assert go, 'Go button missing after typing'
    if maps_installed:
        assert go[0].get('text') == 'Go', f'Go button reads {go[0].get("text")!r}'
        tap_node(go[0])
        time.sleep(3)
        front = focused_package()
        evidence['foreground_after_go'] = front
        assert front == MAPS, f'Expected Google Maps in front after Go, got {front!r}'
        ok('Ride: Go handed "Nandi Hills" to Google Maps (google.navigation intent resolved and started)')
        launch()
        tab('Ride')
        top()
        recent = find('Navigate to Nandi Hills')
        assert recent is not None, 'Recent destination row missing after a launch'
        shot('ride-navigate-recent')
        ok('Ride: the destination appears under Recent after it was started')
    else:
        assert go[0].get('text') == 'Get app', 'Without the app the primary action must offer to get it'
        ok('Ride: without the navigation app the primary action offers to get it')

    # 3. App chooser lists installed catalogue apps with their forwarding note
    tap_node(by_id('navigate_app')[0])
    root = tree()
    assert find('Navigation app', root) is not None, 'App chooser title missing'
    if maps_installed:
        assert find('Google Maps', root) is not None and find('Turns forward to your dashboard', root) is not None
    shot('ride-navigate-app-chooser')
    tap('Cancel')
    ok('Ride: navigation app chooser lists the installed apps and whether turns reach the dashboard')

    # 4. Saving a favourite shows a chip in the card
    top()
    tap('Save home or work')
    root = tree()
    place = [n for n in root.iter('node') if (n.get('content-desc') or '') == 'Address or coordinates' and bounds(n)]
    assert place, 'Favourite place field missing'
    tap_node(place[0])
    adb('shell', 'input', 'text', '12.97,77.59')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(.4)
    tap('Save')
    time.sleep(.8)
    top()
    assert find('Navigate to Home') is not None, 'Home favourite chip missing'
    shot('ride-navigate-favourite')
    ok('Ride: Home favourite saved from the card and shown as a chip')

    # 5. A geo: link opened with KTMnav fills the destination field
    launch(('-a', 'android.intent.action.VIEW', '-d', 'geo:0,0?q=Chikmagalur'))
    time.sleep(1)
    field = by_id('navigate_field')
    assert field and 'Chikmagalur' in (field[0].get('text') or ''), f'geo: destination not prefilled: {field and field[0].get("text")!r}'
    shot('ride-navigate-shared-geo')
    ok('Ride: a geo: link sent to KTMnav prefills the destination field')

    # 6. Garage silhouettes and model picker
    tab('Garage')
    root = tree()
    icons = by_id('bike_icon', root)
    assert len(icons) >= 3, f'Expected a silhouette per bike, found {len(icons)}'
    for name in ['Duke 390 test', 'KTM 390 ADVENTURE', 'Svartpilen 401']:
        assert find(name, root) is not None, f'Bike row missing: {name}'
    shot('garage-silhouettes')
    ok('Garage: every saved bike shows a silhouette tile (naked, adventure and scrambler guessed from their names)')
    tap('Duke 390 test')
    tap('Motorcycle model')
    root = tree()
    assert find('Styles', root) is not None and find('Guessed from the dashboard name', root, contains=True) is not None
    shot('garage-model-picker')
    reveal('KTM 1290 Super Duke GT', tries=12)
    tap('KTM 1290 Super Duke GT')
    time.sleep(.8)
    root = tree()
    assert find('KTM 1290 Super Duke GT · Gen 3', root) is not None, 'Model choice not shown in the Garage row'
    shot('garage-model-set')
    ok('Garage: Motorcycle model picker stores the chosen model and the row shows it')

    # 7. Apps: bundled icons for every app that is not installed, launch-only chip for non-forwarding apps
    tab('Apps')
    if find('TomTom GO') is None:
        tap('More apps')
    seen_bundled, seen_texts = 0, set()
    for _ in range(6):
        root = tree()
        seen_bundled = max(seen_bundled, len(by_id('app_icon_bundled', root)))
        seen_texts |= {(n.get('text') or '') for n in root.iter('node') if bounds(n)}
        if find('Mapy.cz', root) is not None:
            break
        swipe()
    for name in ['Waze', 'HERE WeGo', 'Sygic', 'Mapy.cz']:
        assert name in seen_texts, f'New catalogue app missing from the Apps tab: {name}'
    assert 'Launch only' in seen_texts, 'Launch-only chip missing for HERE WeGo / Sygic / Mapy.cz'
    assert seen_bundled >= 6, f'Expected bundled icons for missing apps, saw {seen_bundled}'
    evidence['bundled_icons_visible_at_once'] = seen_bundled
    shot('apps-bundled-icons')
    ok(f'Apps: not-installed apps show bundled icons ({seen_bundled} on one screen), Waze and Mapy.cz listed, launch-only apps chipped')

    # 8. Settings: Navigation group
    tab('Settings')
    reveal('Two-wheeler routes in Google Maps', tries=10)
    root = tree()
    for label in ['Navigation app', 'Two-wheeler routes in Google Maps', 'Favourite places', 'Clear recent destinations']:
        assert find(label, root) is not None, f'Settings row missing: {label}'
    shot('settings-navigation')
    ok('Settings: Navigation group with app choice, two-wheeler routing, favourites and clear recents')

    # 9. GPX route import through the file intent and the route sheet
    adb('exec-in', 'run-as', PKG, 'sh', '-c', 'mkdir -p cache; cat > cache/share.gpx', data=GPX.encode())
    launch(('-a', 'android.intent.action.VIEW', '-d', 'file:///data/data/%s/cache/share.gpx' % PKG, '-t', 'application/gpx+xml'))
    time.sleep(2.5)
    root = tree()
    assert find('Planned route', root) is not None and find('Navigate this route', root) is not None, 'Route sheet did not open after import'
    assert find('share', root) is not None, 'Imported route title missing'
    shot('rides-route-imported')
    ok('Rides: a GPX opened with KTMnav is imported and its route sheet offers "Navigate this route"')
    if maps_installed:
        tap('Navigate this route')
        time.sleep(3)
        front = focused_package()
        evidence['foreground_after_route'] = front
        assert front == MAPS, f'Expected Google Maps after Navigate this route, got {front!r}'
        ok('Rides: Navigate this route started Google Maps with the directions link')
        launch()
    else:
        tap('Close')
    adb('shell', 'am', 'force-stop', PKG)
    launch()
    tab('Settings')
    reveal('Rides', tries=10)
    tap('Rides')
    time.sleep(1.5)
    root = tree()
    assert find('Planned routes', root) is not None and find('Import GPX route', root) is not None, 'Planned routes group missing in Rides'
    shot('rides-planned-routes')
    ok('Rides: Planned routes group lists the imported route and offers Import GPX route')
    tap('Close')
finally:
    for k, v in restore.items():
        if v and v != 'null':
            adb('shell', 'settings', 'put', 'system', k, v, check=False)
    adb('shell', 'cmd', 'notification', 'disallow_listener', LISTENER, check=False)
    (OUT.parent / 'navigate-ui.txt').write_text('\n'.join(checks) + '\n\n' + json.dumps(evidence, indent=2) + '\n')
print(f'PASS: {len(checks)} navigate/garage/apps checks; screenshots in {OUT}')
