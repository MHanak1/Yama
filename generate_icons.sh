#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SVG="$SCRIPT_DIR/icon.svg"
BANNER_SVG="$SCRIPT_DIR/banner.svg"
ANDROID_RES="$SCRIPT_DIR/androidApp/src/main/res"
SHARED_RES="$SCRIPT_DIR/shared/src/androidMain/res"
DESKTOP_RES="$SCRIPT_DIR/desktopApp/src/main/resources"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for cmd in inkscape magick python3; do
    command -v "$cmd" >/dev/null || { echo "Error: '$cmd' not found"; exit 1; }
done

echo "Android legacy mipmap icons (pre-API 26 fallback)..."
declare -A LEGACY=([mdpi]=48 [hdpi]=72 [xhdpi]=96 [xxhdpi]=144 [xxxhdpi]=192)
for density in "${!LEGACY[@]}"; do
    size=${LEGACY[$density]}
    png="$TMP/legacy_${density}.png"
    inkscape "$SVG" --export-type=png --export-filename="$png" -w "$size" -h "$size" 2>/dev/null
    cp "$png" "$ANDROID_RES/mipmap-${density}/ic_launcher.png"
    cp "$png" "$ANDROID_RES/mipmap-${density}/ic_launcher_round.png"
    rm -f "$ANDROID_RES/mipmap-${density}/ic_launcher_foreground.png"
    echo "  mipmap-$density: ${size}x${size}"
done

echo "Android vector drawables..."
# ic_launcher_foreground.xml — gradient waveform, used by launcher adaptive icon and splash screen
# ic_launcher_monochrome.xml — white waveform, used by Android 13+ themed/monochrome icons
SVG="$SVG" \
FG_OUT="$ANDROID_RES/drawable-v24/ic_launcher_foreground.xml" \
MONO_OUT="$ANDROID_RES/drawable/ic_launcher_monochrome.xml" \
NOTIF_OUT="$SHARED_RES/drawable/ic_notification_waveform.xml" \
python3 << 'PYEOF'
import xml.etree.ElementTree as ET, os, re, sys

SVG_PATH  = os.environ['SVG']
FG_OUT    = os.environ['FG_OUT']
MONO_OUT  = os.environ['MONO_OUT']
NOTIF_OUT = os.environ['NOTIF_OUT']
NS        = 'http://www.w3.org/2000/svg'

tree = ET.parse(SVG_PATH)
root = tree.getroot()

path_elem = next(
    (e for e in root.iter(f'{{{NS}}}path')
     if 'linearGradient' in e.get('style', '')),
    None)
if path_elem is None:
    sys.exit("No gradient-stroke path found in SVG")

style     = path_elem.get('style', '')
path_data = path_elem.get('d', '')

def css(prop, default):
    m = re.search(rf'{prop}:([^;]+)', style)
    return m.group(1).strip() if m else default

stroke_width   = css('stroke-width', '5')
stroke_linecap = css('stroke-linecap', 'round')

grad_stops = grad_coords = None
for g in root.iter(f'{{{NS}}}linearGradient'):
    if g.findall(f'{{{NS}}}stop'):
        grad_stops = g
    if g.get('gradientUnits') == 'userSpaceOnUse':
        grad_coords = g

if grad_stops is None or grad_coords is None:
    sys.exit("Could not find gradient in SVG")

stop_lines = []
for stop in grad_stops.findall(f'{{{NS}}}stop'):
    s      = stop.get('style', '')
    offset = stop.get('offset', '0')
    color  = re.search(r'stop-color:#([0-9a-fA-F]+)', s)
    alpha  = re.search(r'stop-opacity:([\d.]+)', s)
    color  = color.group(1) if color else '000000'
    alpha  = round(float(alpha.group(1)) * 255) if alpha else 255
    stop_lines.append(
        f'            <item android:offset="{offset}" android:color="#{alpha:02x}{color}"/>')

vb = root.get('viewBox', '0 0 512 512').split()
vw, vh = vb[2], vb[3]
x1 = grad_coords.get('x1', '0')
y1 = grad_coords.get('y1', '0')
x2 = grad_coords.get('x2', vw)
y2 = grad_coords.get('y2', '0')
stops_str = '\n'.join(stop_lines)

cx, cy = float(vw) / 2, float(vh) / 2

# Launcher icons scale the waveform to 2/3 (the adaptive-icon safe zone) so it
# clears the circular launcher and splash-screen masks. The notification small
# icon has no mask, so it fills the full canvas (scale 1.0) to read larger in
# the status bar.
def header(scale):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
    <group
        android:pivotX="{cx:.3f}"
        android:pivotY="{cy:.3f}"
        android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}">'''

HEADER = header(2.0 / 3.0)

PATH_ATTRS = f'''        <path
            android:pathData="{path_data}"
            android:fillColor="#00000000"
            android:strokeWidth="{stroke_width}"
            android:strokeLineCap="{stroke_linecap}"'''

FOOTER = '''    </group>
</vector>'''

def write(path, xml):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        f.write(xml)
    print(f"  {path}")

write(FG_OUT, f'''{HEADER}
{PATH_ATTRS}>
            <aapt:attr name="android:strokeColor">
                <gradient
                    android:startX="{x1}"
                    android:startY="{y1}"
                    android:endX="{x2}"
                    android:endY="{y2}"
                    android:type="linear">
{stops_str}
                </gradient>
            </aapt:attr>
        </path>
{FOOTER}''')

write(MONO_OUT, f'''{HEADER}
{PATH_ATTRS}
            android:strokeColor="#FFFFFFFF" />
{FOOTER}''')

# Media3 playback-notification small icon (shared module): same white waveform,
# but full-canvas (no safe-zone shrink) and tinted by the system.
write(NOTIF_OUT, f'''{header(1.0)}
{PATH_ATTRS}
            android:strokeColor="#FFFFFFFF" />
{FOOTER}''')
PYEOF

echo "Android adaptive icon XML..."
ADAPTIVE_ICON='<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>'
for name in ic_launcher ic_launcher_round; do
    echo "$ADAPTIVE_ICON" > "$ANDROID_RES/mipmap-anydpi-v26/${name}.xml"
    echo "  mipmap-anydpi-v26/${name}.xml"
done

echo "Cleaning up stale files..."
rm -f "$ANDROID_RES/drawable/ic_splash_icon.xml"
echo "  removed ic_splash_icon.xml"

echo "Android TV banner..."
mkdir -p "$ANDROID_RES/drawable-xhdpi"
inkscape "$BANNER_SVG" --export-type=png \
    --export-filename="$ANDROID_RES/drawable-xhdpi/tv_banner.png" \
    -w 640 -h 360 2>/dev/null
echo "  drawable-xhdpi/tv_banner.png (640x360)"

echo "Desktop icons..."
mkdir -p "$DESKTOP_RES"
inkscape "$SVG" --export-type=png --export-filename="$DESKTOP_RES/icon.png" -w 512 -h 512 2>/dev/null
magick "$DESKTOP_RES/icon.png" \
    -define icon:auto-resize=256,128,64,48,32,16 \
    "$DESKTOP_RES/icon.ico"
echo "  icon.png (512x512), icon.ico (multi-size)"

echo "Done."
