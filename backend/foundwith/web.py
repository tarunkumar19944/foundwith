"""The two web pages WhoHas needs, served by the API itself so there is no website to run:

  /i/{code}  an invite link. On Android: a button to Google Play that carries the invite code
             (Play Install Referrer). Elsewhere: "WhoHas is on Android for now".
  /out       take your number out of WhoHas, for anyone, with or without the app. The person
             proves the number with an SMS code (Identity Platform's web SDK), then the page calls
             /v1/optout. If the number has an account, the page says that it will be deleted too.
"""

from __future__ import annotations

import html
import json
import re
import secrets
from urllib.parse import quote

from starlette.requests import Request
from starlette.responses import HTMLResponse

from . import repo

_INVITE_PATH_CODE = re.compile(r"^[A-HJ-NP-Za-hj-np-z2-9]{8}$")
FIREBASE_JS = "https://www.gstatic.com/firebasejs/10.14.1"

_STYLE = """
:root{--bg:#14151A;--card:#1D1F26;--text:#E7E8EC;--muted:#A3A6B1;--accent:#D9B65C;--accent-bg:#3A3220;
--on-accent:#14151A;--danger:#F2867E;--ok:#7CCB98;--border:#2C2F38;color-scheme:dark}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);
font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif}
main{max-width:420px;margin:0 auto;padding:32px 16px}
.logo{width:48px;height:48px;border-radius:12px;background:var(--accent-bg);color:var(--accent);
display:flex;align-items:center;justify-content:center;font-weight:700;font-size:20px}
h1{font-size:24px;line-height:1.25;margin:16px 0 8px}p{margin:8px 0;color:var(--muted)}
.btn{display:block;width:100%;margin-top:20px;padding:14px;border-radius:26px;border:0;font:inherit;
font-weight:700;text-align:center;text-decoration:none;cursor:pointer;background:var(--accent);color:var(--on-accent)}
.btn.ghost{background:transparent;color:var(--accent);border:1px solid var(--border)}
.btn.danger{background:var(--danger);color:var(--on-accent)}
.btn[disabled]{opacity:.5;cursor:default}
.row{display:flex;gap:8px;margin-top:16px}
input{font:inherit;font-size:17px;padding:13px 14px;border-radius:14px;border:1.5px solid var(--border);background:#272A33;
color:var(--text);width:100%}
.cc{flex:none;width:64px;text-align:center;color:var(--muted)}
.err{color:var(--danger);font-size:14px;margin-top:8px;min-height:1.2em}
a{color:var(--accent);font-weight:700;text-decoration:none}
.done{color:var(--ok);font-weight:700}
.note{margin-top:20px;padding-top:14px;border-top:1px solid var(--border);font-size:14px;line-height:1.6}
.note b{color:var(--text)}
h2{font-size:18px;margin:24px 0 6px}ul{margin:8px 0;padding-left:20px;color:var(--muted)}li{margin:6px 0}li b,p b{color:var(--text)}
[hidden]{display:none!important}
small{display:block;margin-top:24px;color:var(--muted);font-size:13px}
"""


def _page(title: str, body: str, *, nonce: str = "", extra_script: str = "", csp_extra: dict[str, str] | None = None) -> HTMLResponse:
    csp = {
        "default-src": "'none'",
        "style-src": "'unsafe-inline'",
        "img-src": "'self' data:",
        "base-uri": "'none'",
        "form-action": "'self'",
        "frame-ancestors": "'none'",
    }
    for key, value in (csp_extra or {}).items():
        csp[key] = value
    doc = f"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>{html.escape(title)}</title><style>{_STYLE}</style></head><body><main>{body}</main>{extra_script}</body></html>"""
    headers = {"Content-Security-Policy": "; ".join(f"{k} {v}" for k, v in csp.items()),
               "Referrer-Policy": "no-referrer"}
    return HTMLResponse(doc, headers=headers)


def _first_word(name: str | None) -> str | None:
    word = (name or "").strip().split(" ")[0] if name else ""
    return word or None


async def invite_page(request: Request) -> HTMLResponse:
    settings = request.app.state.settings
    code = request.path_params["code"]
    store = settings.play_store_url
    inviter: str | None = None
    if _INVITE_PATH_CODE.match(code):
        store += "&referrer=" + quote("invite=" + code.upper(), safe="")
        async with request.app.state.db.acquire() as conn:
            row = await repo.user_by_invite_code(conn, code.upper())
        if row is not None and row["suspended_at"] is None:
            inviter = _first_word(row["display_name"])
    agent = request.headers.get("user-agent", "")
    ios = bool(re.search(r"iPhone|iPad|iPod", agent))
    title = f"{html.escape(inviter)} invited you to WhoHas" if inviter else "You're invited to WhoHas"
    lead = "See who has any number, even when someone unknown calls."
    if ios:
        action = "<p>WhoHas is on Android for now.</p>"
    else:
        action = f'<a class="btn" href="{html.escape(store)}">Get it on Google Play</a>'
    body = f"""<div class="logo" aria-hidden="true">W</div><h1>{title}</h1><p>{lead}</p>{action}
<small>WhoHas never messages or calls anyone.</small>"""
    return _page("WhoHas", body)


_OUT_BODY = """
<div class="logo" aria-hidden="true">W</div>
<section id="s-number">
  <h1>Remove your number from WhoHas</h1>
  <p>Works even if you don't use the app. People on WhoHas won't see who has your number.</p>
  <div class="row"><input class="cc" value="+91" disabled aria-label="Country code">
    <input id="number" inputmode="numeric" autocomplete="tel-national" maxlength="14" placeholder="98450 12345" aria-label="Mobile number"></div>
  <div class="err" id="number-err" role="alert"></div>
  <button class="btn" id="send">Send code</button>
</section>
<section id="s-code" hidden>
  <h1>Enter the 6-digit code</h1>
  <p>Sent to <span id="sent-to"></span> · <a href="#" id="change">Change number</a></p>
  <div class="row"><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6-digit code" aria-label="Code"></div>
  <div class="err" id="code-err" role="alert"></div>
  <button class="btn" id="verify">Continue</button>
</section>
<section id="s-account" hidden>
  <h1>This number has a WhoHas account</h1>
  <p>Removing it also deletes the account and everything in it.</p>
  <button class="btn danger" id="delete">Remove and delete</button>
  <button class="btn ghost" id="cancel">Cancel</button>
  <div class="err" id="account-err" role="alert"></div>
</section>
<section id="s-done" hidden>
  <h1 class="done">Done. Your number is removed.</h1>
  <p>People on WhoHas won't see who has it, now or later.</p>
</section>
<section id="s-off" hidden><h1>This page isn't ready yet</h1><p>Write to us at the contact address on our Google Play listing to remove your number.</p></section>
<div id="recaptcha"></div>
"""

_OUT_SCRIPT = """
<script nonce="{nonce}" src="{fb}/firebase-app-compat.js"></script>
<script nonce="{nonce}" src="{fb}/firebase-auth-compat.js"></script>
<script nonce="{nonce}">
(function () {{
  var config = {config};
  var $ = function (id) {{ return document.getElementById(id); }};
  function show(id) {{ ["s-number", "s-code", "s-account", "s-done", "s-off"].forEach(function (s) {{ $(s).hidden = s !== id; }}); }}
  if (!config.apiKey || !window.firebase) {{ show("s-off"); return; }}
  firebase.initializeApp(config);
  var auth = firebase.auth(), confirmation = null, token = null, phone = null;
  var verifier = new firebase.auth.RecaptchaVerifier("send", {{ size: "invisible" }});
  function digits(s) {{ return (s || "").replace(/[^0-9]/g, ""); }}
  function mobile(raw) {{
    var d = digits(raw);
    if (d.length === 12 && d.indexOf("91") === 0) d = d.slice(2);
    if (d.length === 11 && d[0] === "0") d = d.slice(1);
    return /^[6-9][0-9]{{9}}$/.test(d) ? "+91" + d : null;
  }}
  function problem(e) {{
    var code = (e && e.code) || "";
    if (code.indexOf("too-many-requests") >= 0) return "Too many tries. For safety, wait a while and try again.";
    if (code.indexOf("invalid-verification-code") >= 0) return "That code doesn't match. Check the SMS.";
    if (code.indexOf("network") >= 0 || !navigator.onLine) return "No internet. Connect and try again.";
    return "Something went wrong. Try again.";
  }}
  function api(method, body) {{
    return fetch("/v1/optout", {{ method: method, headers: {{ "Authorization": "Bearer " + token,
      "Content-Type": "application/json" }}, body: body ? JSON.stringify(body) : undefined }})
      .then(function (r) {{ return r.json().then(function (j) {{ return {{ status: r.status, body: j }}; }}); }});
  }}
  function takeOut(withAccount) {{
    return api("PUT", {{ delete_account: !!withAccount }}).then(function (r) {{
      if (r.status === 200) {{ auth.signOut(); show("s-done"); return; }}
      if (r.body && r.body.error && r.body.error.code === "account_exists") {{ show("s-account"); return; }}
      throw new Error("failed");
    }});
  }}
  $("send").addEventListener("click", function () {{
    phone = mobile($("number").value);
    $("number-err").textContent = phone ? "" : "10 digits, starting with 6, 7, 8 or 9.";
    if (!phone) return;
    $("send").disabled = true;
    auth.signInWithPhoneNumber(phone, verifier).then(function (c) {{
      confirmation = c; $("sent-to").textContent = phone.slice(0, 3) + " " + phone.slice(3, 8) + " " + phone.slice(8); show("s-code"); $("code").focus();
    }}).catch(function (e) {{ $("number-err").textContent = problem(e); }})
      .then(function () {{ $("send").disabled = false; }});
  }});
  $("change").addEventListener("click", function (e) {{ e.preventDefault(); show("s-number"); }});
  $("verify").addEventListener("click", function () {{
    var code = digits($("code").value);
    if (code.length !== 6) {{ $("code-err").textContent = "Enter the 6-digit code."; return; }}
    $("verify").disabled = true;
    confirmation.confirm(code).then(function (result) {{ return result.user.getIdToken(); }})
      .then(function (t) {{ token = t; return api("GET"); }})
      .then(function (r) {{
        if (r.status !== 200) throw new Error("failed");
        if (r.body.opted_out) {{ auth.signOut(); show("s-done"); return; }}
        if (r.body.has_account) {{ show("s-account"); return; }}
        return takeOut(false);
      }})
      .catch(function (e) {{ $("code-err").textContent = problem(e); }})
      .then(function () {{ $("verify").disabled = false; }});
  }});
  $("delete").addEventListener("click", function () {{
    $("delete").disabled = true;
    takeOut(true).catch(function (e) {{ $("account-err").textContent = problem(e); }})
      .then(function () {{ $("delete").disabled = false; }});
  }});
  $("cancel").addEventListener("click", function () {{ auth.signOut(); show("s-number"); }});
}})();
</script>
"""


async def opt_out_page(request: Request) -> HTMLResponse:
    settings = request.app.state.settings
    nonce = secrets.token_urlsafe(16)
    config = {"apiKey": settings.firebase_web_api_key, "authDomain": settings.firebase_auth_domain,
              "projectId": settings.firebase_project_id}
    if not settings.firebase_web_api_key:
        config = {}
    script = _OUT_SCRIPT.format(nonce=nonce, fb=FIREBASE_JS, config=json.dumps(config))
    csp = {
        "script-src": f"'nonce-{nonce}' https://www.gstatic.com https://www.google.com https://apis.google.com",
        "connect-src": "'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.google.com",
        "frame-src": "https://www.google.com https://*.firebaseapp.com https://recaptcha.google.com",
        "img-src": "'self' data: https://www.gstatic.com https://www.google.com",
    }
    return _page("Remove your number · WhoHas", _OUT_BODY, nonce=nonce, extra_script=script, csp_extra=csp)


# ---------------------------------------------------------------- delete my account (the web page Play asks for)
# For people without the app. Same SMS sign-in as the opt-out page; the code step has the delete
# button. It deletes the account only: the number is not added to the opt-out list, so the person
# can sign up again later. What is kept afterwards is described in the privacy policy, not here.

_DELETE_BODY = """
<div class="logo" aria-hidden="true">W</div>
<section id="s-number">
  <h1>Delete your WhoHas account</h1>
  <p>You don't need the app. Enter the mobile number you signed up with. We send a code to confirm it is yours.</p>
  <div class="row"><input class="cc" value="+91" disabled aria-label="Country code">
    <input id="number" inputmode="numeric" autocomplete="tel-national" maxlength="14" placeholder="98450 12345" aria-label="Mobile number"></div>
  <div class="err" id="number-err" role="alert"></div>
  <button class="btn" id="send">Send code</button>
  <p class="note"><b>What is deleted:</b> your number, name, the contact and call log numbers WhoHas holds for you, blocked list and settings.
  Counts on other people's cards go down by one. What is kept after that, and for how long, is in the privacy policy.</p>
</section>
<section id="s-code" hidden>
  <h1>Enter the code</h1>
  <p>Sent to <span id="sent-to"></span> · <a href="#" id="change">Change number</a></p>
  <div class="row"><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="6-digit code" aria-label="Code"></div>
  <div class="err" id="code-err" role="alert"></div>
  <button class="btn danger" id="delete">Delete my account</button>
  <p class="note">This can't be undone. You can sign up again later with a fresh start.</p>
</section>
<section id="s-done" hidden>
  <h1 class="done">Your account is deleted</h1>
  <p>Your number, name, contacts and call log numbers are gone from WhoHas.</p>
</section>
<section id="s-none" hidden>
  <h1>No WhoHas account uses this number</h1>
  <p>There is nothing to delete. To stop your number showing to people on WhoHas, use <a href="/out">Remove your number</a>.</p>
</section>
<section id="s-off" hidden><h1>This page isn't ready yet</h1><p>Write to us at the contact address on our Google Play listing to delete your account.</p></section>
<div id="recaptcha"></div>
"""

_DELETE_SCRIPT = """
<script nonce="{nonce}" src="{fb}/firebase-app-compat.js"></script>
<script nonce="{nonce}" src="{fb}/firebase-auth-compat.js"></script>
<script nonce="{nonce}">
(function () {{
  var config = {config};
  var $ = function (id) {{ return document.getElementById(id); }};
  function show(id) {{ ["s-number", "s-code", "s-done", "s-none", "s-off"].forEach(function (s) {{ $(s).hidden = s !== id; }}); }}
  if (!config.apiKey || !window.firebase) {{ show("s-off"); return; }}
  firebase.initializeApp(config);
  var auth = firebase.auth(), confirmation = null, phone = null;
  var verifier = new firebase.auth.RecaptchaVerifier("send", {{ size: "invisible" }});
  function digits(s) {{ return (s || "").replace(/[^0-9]/g, ""); }}
  function mobile(raw) {{
    var d = digits(raw);
    if (d.length === 12 && d.indexOf("91") === 0) d = d.slice(2);
    if (d.length === 11 && d[0] === "0") d = d.slice(1);
    return /^[6-9][0-9]{{9}}$/.test(d) ? "+91" + d : null;
  }}
  function problem(e) {{
    var code = (e && e.code) || "";
    if (code.indexOf("too-many-requests") >= 0) return "Too many tries. For safety, wait a while and try again.";
    if (code.indexOf("invalid-verification-code") >= 0) return "That code doesn't match. Check the SMS.";
    if (code.indexOf("network") >= 0 || !navigator.onLine) return "No internet. Connect and try again.";
    return "Something went wrong. Try again.";
  }}
  $("send").addEventListener("click", function () {{
    phone = mobile($("number").value);
    $("number-err").textContent = phone ? "" : "10 digits, starting with 6, 7, 8 or 9.";
    if (!phone) return;
    $("send").disabled = true;
    auth.signInWithPhoneNumber(phone, verifier).then(function (c) {{
      confirmation = c; $("sent-to").textContent = phone.slice(0, 3) + " " + phone.slice(3, 8) + " " + phone.slice(8); show("s-code"); $("code").focus();
    }}).catch(function (e) {{ $("number-err").textContent = problem(e); }})
      .then(function () {{ $("send").disabled = false; }});
  }});
  $("change").addEventListener("click", function (e) {{ e.preventDefault(); show("s-number"); }});
  $("delete").addEventListener("click", function () {{
    var code = digits($("code").value);
    if (code.length !== 6) {{ $("code-err").textContent = "Enter the 6-digit code."; return; }}
    $("delete").disabled = true;
    confirmation.confirm(code).then(function (result) {{ return result.user.getIdToken(); }})
      .then(function (token) {{
        return fetch("/v1/account", {{ method: "DELETE", headers: {{ "Authorization": "Bearer " + token }} }});
      }})
      .then(function (r) {{
        if (r.status !== 200) throw new Error("failed");
        return r.json();
      }})
      .then(function (body) {{ auth.signOut(); show(body.had_account ? "s-done" : "s-none"); }})
      .catch(function (e) {{ $("code-err").textContent = problem(e); }})
      .then(function () {{ $("delete").disabled = false; }});
  }});
}})();
</script>
"""


async def delete_page(request: Request) -> HTMLResponse:
    settings = request.app.state.settings
    nonce = secrets.token_urlsafe(16)
    config = {"apiKey": settings.firebase_web_api_key, "authDomain": settings.firebase_auth_domain,
              "projectId": settings.firebase_project_id}
    if not settings.firebase_web_api_key:
        config = {}
    script = _DELETE_SCRIPT.format(nonce=nonce, fb=FIREBASE_JS, config=json.dumps(config))
    csp = {
        "script-src": f"'nonce-{nonce}' https://www.gstatic.com https://www.google.com https://apis.google.com",
        "connect-src": "'self' https://identitytoolkit.googleapis.com https://securetoken.googleapis.com https://www.google.com",
        "frame-src": "https://www.google.com https://*.firebaseapp.com https://recaptcha.google.com",
        "img-src": "'self' data: https://www.gstatic.com https://www.google.com",
    }
    return _page("Delete your account · WhoHas", _DELETE_BODY, nonce=nonce, extra_script=script, csp_extra=csp)


# ---------------------------------------------------------------- privacy policy (Google Play needs its address)
# Plain words, true to what the server and app actually do (checked against the code on 22 Nov 2026):
# numbers are stored as keyed hashes, everything in Mumbai (asia-south1). Saved contact names are not sent.

PRIVACY_UPDATED = "23 November 2026"


def privacy_body(settings) -> str:
    who = html.escape(settings.publisher_name or "the WhoHas team")
    email = html.escape(settings.contact_email)
    write = f'<a href="mailto:{email}">{email}</a>' if email else "the contact address on our Google Play listing"
    days = settings.inactive_days
    log_days = settings.lookup_log_days
    tomb = settings.tombstone_days
    return f"""
<div class="logo" aria-hidden="true">W</div>
<h1>WhoHas privacy policy</h1>
<p>Last updated {PRIVACY_UPDATED}. WhoHas is run by {who}. Questions or requests: {write}.</p>

<h2>What WhoHas does</h2>
<p>For any phone number, WhoHas shows how many people on WhoHas have it in their contacts or call log, and which of them you
know. To do that, it compares the numbers on your phone with the numbers on other WhoHas users' phones.</p>

<h2>What we collect</h2>
<ul>
<li><b>Your mobile number</b>, checked by an SMS code sent through Google Firebase.</li>
<li><b>Your name</b>, if you add one.</li>
<li><b>Your contacts</b>, after you allow it: each phone number. The names you saved stay on your phone.</li>
<li><b>Your call log</b> (only in the version of the app that asks for it, after you allow it): the phone numbers. Not call
times, not call lengths and not recordings.</li>
<li><b>Numbers you search</b> and numbers WhoHas checks after a call, with the time.</li>
<li><b>People you block</b>, numbers you hide, and reports you make.</li>
<li><b>Numbers you tell about WhoHas</b> (Tell), so that person sees who told them when they join, and whether
someone joined through your invite link.</li>
<li><b>Technical details</b>: a notification token for your phone, the app version, the Android version and the phone's
brand, and how many calls the app could check (counts only).</li>
</ul>
<p>WhoHas never reads your SMS messages (only the sign-in code is filled in for you, by Google's sign-in), never
records calls, and never messages or calls anyone. It does not use
advertising or sell data.</p>

<h2>How it is stored</h2>
<p>In WhoHas's database, phone numbers are stored only as keyed hashes (a one-way code made with a secret key), never
as plain numbers. Your own number is also kept by Google Firebase, which signs you in with it. Everything is stored on Google Cloud in Mumbai, India, and sent over encrypted
connections.</p>

<h2>What other people can see</h2>
<ul>
<li><b>Counts.</b> Anyone on WhoHas who looks up a number sees how many people on WhoHas have it. You are counted for every
number in your contacts or call log.</li>
<li><b>Your name.</b> When someone looks up a number you have, they see your name only if your number is in their contacts
or call log (so they already know you). Everyone else sees you only as part of the count.</li>
<li><b>Your WhoHas name, on your own number.</b> Anyone on WhoHas who looks up your number sees the name you
chose in WhoHas ("Their own name"), if you added one. You can change or remove it in Settings.</li>
<li><b>That you have their number.</b> People whose number you have can see that you have it, if your number is in their
contacts or call log.</li>
</ul>
<p>You can block someone so you don't see each other, and anyone can remove their own number from WhoHas.</p>

<h2>Who we share it with</h2>
<p>Only the services that run WhoHas for us: Google Cloud (servers and database) and Google Firebase (sign-in by SMS and
notifications). We don't share or sell your data to anyone else, except where the law requires it. When you choose to share
a number or an invite, WhatsApp or the app you pick receives only what you send.</p>

<h2>How long we keep it</h2>
<ul>
<li>Your account and its data: until you delete it. Accounts not opened for {days} days are deleted automatically.</li>
<li>When you delete your account, your number, name, contacts and call log numbers, blocks and settings are removed from
the live database at once. Other people's counts go down by one.</li>
<li>Database backups, which may still hold them, are kept for up to 14 days.</li>
<li>The record of searches and after-call checks (hashed numbers only) is kept for {log_days} days, also after an account is
deleted, to stop misuse.</li>
<li>After an account is deleted, a hash of its number and that day's search limits are kept for {tomb} days, so limits
can't be dodged by deleting and joining again; if you had joined through someone's invite, that record is kept for the
same time. If the account had been suspended, the suspension is remembered for longer.</li>
<li>Tells you made are deleted after 90 days.</li>
<li>Notification tokens not used for 60 days are deleted.</li>
</ul>

<h2>Your choices</h2>
<ul>
<li>Contacts and call log access are your choice. Without them you can still search numbers.</li>
<li>Download your data, or delete your account, in the app: Settings › Your data.</li>
<li>Delete your account without the app: <a href="/delete">Delete my account</a>.</li>
<li>Not on WhoHas but want your number out: <a href="/out">Remove your number</a>.</li>
</ul>

<h2>Children</h2>
<p>WhoHas is for people aged 18 and over.</p>

<h2>Changes</h2>
<p>If we change this policy, we update it here and change the date at the top. Big changes are shown in the app before they
apply.</p>
"""


async def home_page(request: Request) -> HTMLResponse:
    """The public home page (whohas-app.web.app/): what WhoHas is, and where to read more."""
    settings = request.app.state.settings
    email = html.escape(settings.contact_email)
    write = f'<p>Questions: <a href="mailto:{email}">{email}</a></p>' if email else ""
    body = (
        "<h1>WhoHas</h1>"
        "<p>Unknown number calling? See which of your people have that number, so you can ask them who it is. "
        "People you know are shown by name; everyone else is a count. On Android.</p>"
        f'<p><a href="{html.escape(settings.play_store_url)}">Get it on Google Play</a></p>'
        f"{write}"
        '<p><a href="/privacy">Privacy policy</a> · <a href="/terms">Terms of use</a> · '
        '<a href="/delete">Delete your account</a></p>'
    )
    return _page("WhoHas", body)


TERMS_UPDATED = "23 November 2026"


def terms_body(settings) -> str:
    """The terms of use, in the same plain words as the privacy policy. Not legal advice: have a lawyer
    read them before the public launch."""
    who = html.escape(settings.publisher_name or "the WhoHas team")
    email = html.escape(settings.contact_email)
    officer = html.escape(settings.grievance_officer or settings.publisher_name or "the WhoHas team")
    write = f'<a href="mailto:{email}">{email}</a>' if email else "the contact address on our Google Play listing"
    return f"""<h1>Terms of use</h1>
<p class="note">Updated {TERMS_UPDATED}</p>
<p>These terms are an agreement between you and {who}, who run WhoHas. By using WhoHas you agree to them
and to our <a href="/privacy">privacy policy</a>.</p>

<h2>Who can use WhoHas</h2>
<ul>
<li>You are 18 or older.</li>
<li>You sign up with an Indian mobile number that is yours, on your own phone. One account per number.</li>
<li>You keep your phone and account secure, and tell us if someone else uses your account.</li>
</ul>

<h2>What WhoHas shows, and its limits</h2>
<p>WhoHas shows how many people on WhoHas have a number in their contacts or call log, and which of them
you know. Names come from your own contacts and from the names people give themselves on WhoHas, so they
can be wrong or out of date. WhoHas does not confirm who anyone is. Use your own judgement before you act on
what you see, for example before you send money or share details with a caller.</p>

<h2>What you agree not to do</h2>
<ul>
<li>Share contacts or a call log that are not from your own phone.</li>
<li>Use WhoHas to harass, threaten, stalk, spam or cheat anyone, or to find out about a person for any
unlawful purpose.</li>
<li>Look up numbers in bulk, use bots or scripts, copy data out of WhoHas, or get around its limits.</li>
<li>Pretend to be someone else, or sign up with someone else's number.</li>
<li>Give a name, report or note that is unlawful, obscene, defamatory, hateful, or that harms a child or
infringes anyone's rights.</li>
</ul>

<h2>Names and reports</h2>
<p>The name you give is shown to anyone on WhoHas who looks up your number, and to people who have your number when you
show up as someone they know. You can change it in Settings.
If a name shown for a number is wrong or offensive, report it from the number's card. We review reports
and remove names that break these terms.</p>

<h2>Your account</h2>
<p>You can delete your account at any time in the app (Settings › Your data › Delete my account) or at
<a href="/delete">Delete your account</a>. We may suspend or close an account that breaks these terms or the
law. What is kept after that, and for how long, is in the privacy policy.</p>

<h2>The service</h2>
<p>WhoHas is free and provided "as is". We work to keep it running and correct, but we cannot promise it
will always be available, complete or free of mistakes. We may change or stop features. If we change
these terms in a way that matters, we will tell you in the app before the change applies.</p>

<h2>Responsibility</h2>
<p>As far as the law allows, {who} is not responsible for losses that come from relying on names or counts
shown in WhoHas, or from how other people use it. Nothing in these terms takes away rights you have under
Indian law that cannot be taken away.</p>

<h2>Law and courts</h2>
<p>These terms are governed by the laws of India. The courts at Bengaluru, Karnataka have jurisdiction.</p>

<h2 id="grievance">Grievance officer</h2>
<p>Under the Information Technology (Intermediary Guidelines and Digital Media Ethics Code) Rules, 2021,
complaints about WhoHas, or about anything shown in it, go to our grievance officer:</p>
<ul>
<li>Grievance officer: {officer}</li>
<li>Email: {write}</li>
</ul>
<p>Write with your mobile number, the number or name the complaint is about, and what happened. We
acknowledge complaints within 24 hours and resolve them within 15 days.</p>
"""


async def terms_page(request: Request) -> HTMLResponse:
    settings = request.app.state.settings
    return _page("Terms of use · WhoHas", terms_body(settings))


async def privacy_page(request: Request) -> HTMLResponse:
    settings = request.app.state.settings
    return _page("Privacy policy · WhoHas", privacy_body(settings) + '<p><a href="/terms">Terms of use</a></p>')
