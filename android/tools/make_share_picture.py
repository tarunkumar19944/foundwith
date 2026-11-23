"""The picture sent with "Share in group" (res/drawable-nodpi/share_whohas.png), Facebook-style light.

Needs cairosvg and the Atkinson Hyperlegible Next font installed. Run:
    python3 android/tools/make_share_picture.py
"""
import pathlib
import cairosvg

F = "Atkinson Hyperlegible Next"
PAGE, CARD, RULE, INK, MUTED = "#F0F2F5", "#FFFFFF", "#CED0D4", "#050505", "#65676B"
BLUE, BLUE_TINT, RED = "#0862F5", "#E7F3FF", "#D21F3C"
PHONE = "M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"
OUT = pathlib.Path(__file__).resolve().parents[1] / "app/src/main/res/drawable-nodpi/share_whohas.png"


def icon(x, y, size, col, extra):
    s = size / 24
    return (f'<g transform="translate({x},{y}) scale({s})" fill="none" stroke="{col}" stroke-width="2" '
            f'stroke-linecap="round" stroke-linejoin="round"><path d="{PHONE}"/><path d="{extra}"/></g>')


def tick(cx, cy):
    return (f'<circle cx="{cx}" cy="{cy}" r="30" fill="{BLUE_TINT}"/>'
            f'<path d="M{cx-13} {cy} l9 9 l17 -18" fill="none" stroke="{BLUE}" stroke-width="5" '
            f'stroke-linecap="round" stroke-linejoin="round"/>')


def av(cx, cy, bg, fg, t):
    return (f'<circle cx="{cx}" cy="{cy}" r="34" fill="{bg}" stroke="{CARD}" stroke-width="5"/>'
            f'<text x="{cx}" y="{cy+10}" text-anchor="middle" font-family="{F}" font-weight="bold" font-size="27" fill="{fg}">{t}</text>')


svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1698" viewBox="0 0 1080 1698">
<rect width="1080" height="1698" fill="{PAGE}"/>
<text x="70" y="190" font-family="{F}" font-weight="bold" font-size="112" fill="{INK}">Who<tspan fill="{BLUE}">Has</tspan></text>
<rect x="70" y="262" width="940" height="330" rx="24" fill="{CARD}" stroke="{RULE}" stroke-width="3"/>
{icon(118, 316, 56, RED, "M16 3l5 5M21 3l-5 5")}
<text x="200" y="352" font-family="{F}" font-weight="bold" font-size="60" fill="{INK}">+91 98450 77120</text>
<text x="200" y="410" font-family="{F}" font-weight="600" font-size="40" fill="{RED}">Missed call · 10:42 am</text>
{av(152, 516, "#E7F3FF", "#0450C2", "PS")}{av(206, 516, "#E6F4EA", "#0D652D", "M")}{av(260, 516, "#F3E8FD", "#6A1B9A", "AK")}
<text x="318" y="532" font-family="{F}" font-weight="bold" font-size="38" fill="{BLUE}">Known to Priya, Meena and 10 more</text>
<text x="70" y="765" font-family="{F}" font-weight="bold" font-size="94" fill="{INK}">Unknown number</text>
<text x="70" y="870" font-family="{F}" font-weight="bold" font-size="94" fill="{INK}">calling?</text>
<text x="70" y="960" font-family="{F}" font-size="52" fill="{MUTED}">See which of your people know it.</text>
{tick(100, 1082)}<text x="156" y="1098" font-family="{F}" font-weight="bold" font-size="48" fill="{INK}">Know your callers</text>
{tick(100, 1192)}<text x="156" y="1208" font-family="{F}" font-weight="bold" font-size="48" fill="{INK}">Check any number</text>
{tick(100, 1302)}<text x="156" y="1318" font-family="{F}" font-weight="bold" font-size="48" fill="{INK}">See who has your number</text>
<rect x="0" y="1450" width="1080" height="248" fill="{BLUE}"/>
<text x="540" y="1558" text-anchor="middle" font-family="{F}" font-weight="bold" font-size="70" fill="#FFFFFF">Get WhoHas</text>
<text x="540" y="1630" text-anchor="middle" font-family="{F}" font-weight="600" font-size="42" fill="#FFFFFF">Free on Android · link in the message</text>
</svg>'''

if __name__ == "__main__":
    cairosvg.svg2png(bytestring=svg.encode(), write_to=str(OUT), output_width=1080, output_height=1698)
    print(OUT)
