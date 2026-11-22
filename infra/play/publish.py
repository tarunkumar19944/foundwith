"""Publish WhoHas to Google Play through the Play Developer API (androidpublisher v3).

    GOOGLE_APPLICATION_CREDENTIALS=key.json python3 infra/play/publish.py check
    ... publish.py listing   [--email help@example.in] [--website https://whohas-app.web.app]
    ... publish.py graphics
    ... publish.py bundle    --aab whohas-full.aab --mapping mapping-full.txt --track internal [--status draft]
    ... publish.py status

The service account must be invited in Play Console (Users and permissions) with access to the app.
Nothing is changed unless the whole edit commits. A new app that was never published accepts only
"draft" releases; roll them out from Play Console once its forms are done.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

import google.auth
import google.auth.transport.requests
import requests

PACKAGE = "app.whohas"
LANG = "en-IN"
HERE = pathlib.Path(__file__).parent
API = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications"
UPLOAD = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications"
SCREENSHOTS = ["screenshot-1-caller.png", "screenshot-2-notification.png", "screenshot-3-updates.png",
               "screenshot-4-search.png", "screenshot-5-your-number.png"]


class Play:
    def __init__(self, package: str):
        creds, _ = google.auth.default(scopes=["https://www.googleapis.com/auth/androidpublisher"])
        creds.refresh(google.auth.transport.requests.Request())
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {creds.token}"
        self.pkg = package
        self.edit = None

    def _ok(self, r: requests.Response) -> dict:
        if r.status_code >= 400:
            sys.exit(f"Play API {r.request.method} {r.url.split('?')[0]} -> {r.status_code}: {r.text[:800]}")
        return r.json() if r.content else {}

    def begin(self) -> None:
        self.edit = self._ok(self.s.post(f"{API}/{self.pkg}/edits"))["id"]

    def url(self, path: str = "") -> str:
        return f"{API}/{self.pkg}/edits/{self.edit}{path}"

    def upload(self, path: str, file: pathlib.Path, mime: str) -> dict:
        with open(file, "rb") as f:
            return self._ok(self.s.post(f"{UPLOAD}/{self.pkg}/edits/{self.edit}{path}?uploadType=media",
                                        data=f, headers={"Content-Type": mime}))

    def commit(self) -> None:
        r = self.s.post(self.url(":commit"))
        if r.status_code == 400 and "changesNotSentForReview" in r.text:
            r = self.s.post(self.url(":commit") + "?changesNotSentForReview=true")
        self._ok(r)
        print("Committed.")


def check(play: Play, _args) -> None:
    play.begin()
    tracks = play._ok(play.s.get(play.url("/tracks"))).get("tracks", [])
    print(f"Access to {play.pkg} works. Tracks: {[t['track'] for t in tracks]}")
    play._ok(play.s.delete(play.url()))


def status(play: Play, _args) -> None:
    play.begin()
    for t in play._ok(play.s.get(play.url("/tracks"))).get("tracks", []):
        for rel in t.get("releases", []):
            print(f"{t['track']:12} {rel.get('status'):10} {rel.get('name', '')} {rel.get('versionCodes', [])}")
    play._ok(play.s.delete(play.url()))


def listing(play: Play, args) -> None:
    d = HERE / "listing" / LANG
    body = {"language": LANG, "title": (d / "title.txt").read_text().strip(),
            "shortDescription": (d / "short_description.txt").read_text().strip(),
            "fullDescription": (d / "full_description.txt").read_text().strip()}
    assert len(body["title"]) <= 30 and len(body["shortDescription"]) <= 80 and len(body["fullDescription"]) <= 4000
    play.begin()
    details = {"defaultLanguage": LANG}
    if args.email:
        details["contactEmail"] = args.email
    if args.website:
        details["contactWebsite"] = args.website
    play._ok(play.s.patch(play.url("/details"), json=details))
    play._ok(play.s.put(play.url(f"/listings/{LANG}"), json=body))
    play.commit()


def graphics(play: Play, _args) -> None:
    g = HERE / "graphics"
    play.begin()
    for kind, files in (("icon", ["icon-512.png"]), ("featureGraphic", ["feature-graphic-1024x500.png"]),
                        ("phoneScreenshots", SCREENSHOTS)):
        play._ok(play.s.delete(play.url(f"/listings/{LANG}/{kind}")))
        for name in files:
            play.upload(f"/listings/{LANG}/{kind}", g / name, "image/png")
            print(f"{kind}: {name}")
    play.commit()


def bundle(play: Play, args) -> None:
    play.begin()
    vc = play.upload("/bundles", pathlib.Path(args.aab), "application/octet-stream")["versionCode"]
    print(f"Uploaded {args.aab}: version code {vc}")
    if args.mapping:
        play.upload(f"/apks/{vc}/deobfuscationFiles/proguard", pathlib.Path(args.mapping), "application/octet-stream")
        print("Mapping file attached.")
    release = {"name": args.name or f"1.0.{vc}", "versionCodes": [str(vc)], "status": args.status}
    if args.notes:
        release["releaseNotes"] = [{"language": LANG, "text": args.notes}]
    play._ok(play.s.put(play.url(f"/tracks/{args.track}"), json={"track": args.track, "releases": [release]}))
    print(f"Track {args.track}: {release['name']} ({args.status})")
    play.commit()


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--package", default=PACKAGE)
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("check")
    sub.add_parser("status")
    l = sub.add_parser("listing")
    l.add_argument("--email")
    l.add_argument("--website")
    sub.add_parser("graphics")
    b = sub.add_parser("bundle")
    b.add_argument("--aab", required=True)
    b.add_argument("--mapping")
    b.add_argument("--track", default="internal", help="internal, alpha (closed testing), beta, production")
    b.add_argument("--status", default="draft", choices=["draft", "completed", "inProgress"])
    b.add_argument("--name")
    b.add_argument("--notes")
    args = p.parse_args()
    {"check": check, "status": status, "listing": listing, "graphics": graphics, "bundle": bundle}[args.cmd](Play(args.package), args)


if __name__ == "__main__":
    main()
