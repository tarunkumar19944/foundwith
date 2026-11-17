"""Writes the cases the app's number rules are tested against (android/app/src/test/resources/
number_parity.jsonl): every line is [as saved, what the server makes of it, is it a mobile].

    cd backend && python scripts/gen_number_parity.py

Run it after any change to foundwith/numbers.py, then run the app's unit tests.
"""
import json
import pathlib
import random
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "backend"))
from foundwith.numbers import is_indian_mobile, normalise  # noqa: E402

rnd = random.Random(20261117)
SCRIPTS = ["0123456789", "०१२३४५६७८९", "೦೧೨೩೪೫೬೭೮೯", "٠١٢٣٤٥٦٧٨٩", "০১২৩৪৫৬৭৮৯", "０１２３４５６７８９"]
SEPARATORS = [" ", "-", ".", " ", "‑", "–", "​", "", "", ""]
PREFIXES = ["", "", "0", "91", "091", "+91", "+91 ", "+91-", "0091", "00 91 ", "+91 0", "(+91) ", "+91 91 ", "+ 91 ", "＋91"]
LABELS = ["", "", "", "tel:", "Mob: ", "Home ", "‪"]
TAILS = ["", "", "", " (home)", ",204", ";204", " ext 204", " x204", " / 98450 31208", ", 9900112233", "‬", "#", " 2"]
FOREIGN = ["+971 50 123 4567", "00971501234567", "+1 415 555 0100", "+44 20 7946 0958", "+65 8123 4567", "971501234567"]


def national():
    kind = rnd.random()
    if kind < 0.55:
        return rnd.choice("6789") + "".join(rnd.choice("0123456789") for _ in range(9))   # mobile-shaped
    if kind < 0.75:
        std = rnd.choice(["80", "79", "11", "22", "33", "44", "674", "821", "712", "40", "20", "484"])
        return std + "".join(rnd.choice("0123456789") for _ in range(10 - len(std)))      # landline with STD code
    if kind < 0.82:
        return rnd.choice(["1800", "1860"]) + "".join(rnd.choice("0123456789") for _ in range(rnd.choice([6, 7])))
    return "".join(rnd.choice("0123456789") for _ in range(rnd.randint(3, 13)))            # anything


def spell(digits):
    script = rnd.choice(SCRIPTS) if rnd.random() < 0.15 else SCRIPTS[0]
    out = []
    for i, d in enumerate(digits):
        out.append(script[int(d)])
        if i in (4, 2, 6) and rnd.random() < 0.5:
            out.append(rnd.choice(SEPARATORS))
    return "".join(out)


def realistic():
    return rnd.choice(LABELS) + rnd.choice(PREFIXES) + spell(national()) + rnd.choice(TAILS)


NOISE = "0123456789+ -().,/;#*axtel: ​‪‬–९೯"


def noise():
    return "".join(rnd.choice(NOISE) for _ in range(rnd.randint(0, 20)))


cases = ["9845031207", "080 4123 5566", "080-4123/4124", "98450/31207", "+91 91 98450 31207", "tel:+919845031207",
         "080 4123 5566,204", "98450 31207 / 98450 31208", "+91+9845031207", "*121#", "-1", "", "9" * 65] + FOREIGN
cases += [realistic() for _ in range(2600)] + [noise() for _ in range(600)]
seen, lines = set(), []
for raw in cases:
    if raw in seen:
        continue
    seen.add(raw)
    e164 = normalise(raw)
    lines.append(json.dumps([raw, e164, is_indian_mobile(e164)], ensure_ascii=True))
out = ROOT / "android/app/src/test/resources/number_parity.jsonl"
out.write_text("\n".join(lines) + "\n")
print(len(lines), "cases,", sum('"+91' in l for l in lines), "matched")
