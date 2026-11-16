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
:root{--bg:#f7f7f5;--card:#fff;--text:#1d1d1b;--muted:#6b6b66;--accent:#2f5fd0;--accent-bg:#e8eefc;
--danger:#b3261e;--ok:#1e7a46;--border:#dcdcd6}
@media (prefers-color-scheme:dark){:root{--bg:#141413;--card:#1f1f1d;--text:#f1f1ec;--muted:#a3a39b;
--accent:#8fb0ff;--accent-bg:#22314f;--danger:#f2b8b5;--ok:#7fd3a0;--border:#3a3a36}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);
font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif}
main{max-width:420px;margin:0 auto;padding:32px 16px}
.logo{width:48px;height:48px;border-radius:12px;background:var(--accent-bg);color:var(--accent);
display:flex;align-items:center;justify-content:center;font-weight:700;font-size:20px}
h1{font-size:22px;line-height:1.3;margin:16px 0 8px}p{margin:8px 0;color:var(--muted)}
.btn{display:block;width:100%;margin-top:20px;padding:12px;border-radius:12px;border:0;font:inherit;
font-weight:600;text-align:center;text-decoration:none;cursor:pointer;background:var(--accent);color:#fff}
@media (prefers-color-scheme:dark){.btn{color:#10204a}}
.btn.ghost{background:transparent;color:var(--accent);border:1px solid var(--border)}
.btn.danger{background:var(--danger);color:#fff}
.btn[disabled]{opacity:.5;cursor:default}
.row{display:flex;gap:8px;margin-top:16px}
input{font:inherit;padding:12px;border-radius:10px;border:1px solid var(--border);background:var(--card);
color:var(--text);width:100%}
.cc{flex:none;width:64px;text-align:center;color:var(--muted)}
.err{color:var(--danger);font-size:14px;margin-top:8px;min-height:1.2em}
a{color:var(--accent);font-weight:600;text-decoration:none}
.done{color:var(--ok);font-weight:600}
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
<section id="s-off" hidden><h1>This page isn't ready yet</h1><p>Write to help@found-with.app to remove your number.</p></section>
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
