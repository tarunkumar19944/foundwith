"""Sign-in. Users verify their phone with an OTP through Identity Platform (Firebase Authentication);
the app sends the resulting ID token as `Authorization: Bearer <token>` on every request.

Tokens are verified here with PyJWT against Google's published signing certificates, following
Firebase's documented rules for verifying ID tokens with a third-party JWT library."""

from __future__ import annotations

import json
import logging
import re
import time
import urllib.request
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

import anyio
import jwt
from cryptography import x509

log = logging.getLogger("foundwith.auth")

GOOGLE_CERTS_URL = (
    "https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com"
)
# Google's keys for ID tokens of service accounts, such as the one Cloud Scheduler signs in as.
GOOGLE_OIDC_CERTS_URL = "https://www.googleapis.com/oauth2/v1/certs"

CertFetcher = Callable[[], Awaitable[tuple[dict[str, str], float]]]


@dataclass(frozen=True)
class Identity:
    uid: str
    phone: str               # E.164, verified by OTP
    auth_time: float = 0.0   # when the person last proved the number with an OTP (epoch seconds)


class AuthError(Exception):
    """The token is not acceptable: the caller must sign in again."""


class AuthUnavailable(Exception):
    """We can't check tokens right now (Google's signing keys could not be fetched)."""


async def fetch_google_certs(url: str = GOOGLE_CERTS_URL) -> tuple[dict[str, str], float]:
    """Download the current signing certificates and how long they may be cached."""

    def _get() -> tuple[dict[str, str], float]:
        with urllib.request.urlopen(url, timeout=5) as resp:  # noqa: S310 (fixed https URL)
            certs = json.loads(resp.read())
            match = re.search(r"max-age=(\d+)", resp.headers.get("Cache-Control", ""))
        return certs, float(match.group(1)) if match else 3600.0

    return await anyio.to_thread.run_sync(_get)


class FirebaseVerifier:
    # After a key refresh, wait this long before refreshing again for a key id we don't know.
    # Without it, anyone could make every request trigger a download by sending made-up key ids.
    UNKNOWN_KEY_REFETCH_S = 60.0
    # When a refresh fails: retry this often, and meanwhile keep using the keys we have.
    RETRY_AFTER_FAILURE_S = 15.0

    def __init__(self, project_id: str, fetch_certs: CertFetcher = fetch_google_certs,
                 leeway: int = 60, clock: Callable[[], float] = time.time) -> None:
        if not project_id:
            raise ValueError("project_id is required")
        self.project_id = project_id
        self._fetch = fetch_certs
        self._leeway = leeway
        self._clock = clock
        self._certs: dict[str, str] = {}
        self._keys: dict[str, object] = {}
        self._expires_at = 0.0
        self._next_attempt_at = 0.0
        self._lock = anyio.Lock()

    def _should_fetch(self, kid: str) -> bool:
        now = self._clock()
        if now < self._next_attempt_at:
            return False
        return now >= self._expires_at or kid not in self._certs

    async def _refresh(self, kid: str) -> None:
        async with self._lock:
            if not self._should_fetch(kid):
                return
            now = self._clock()
            try:
                certs, max_age = await self._fetch()
                if not isinstance(certs, dict) or not certs:
                    raise ValueError("empty certificate list")
            except Exception:  # noqa: BLE001 - network or parsing failure; keep serving with cached keys
                self._next_attempt_at = now + self.RETRY_AFTER_FAILURE_S
                log.warning("could not refresh sign-in certificates", exc_info=True,
                            extra={"event": "cert_refresh_failed"})
                return
            self._certs = {str(k): str(v) for k, v in certs.items()}
            self._keys = {}
            self._expires_at = now + max(60.0, max_age)
            self._next_attempt_at = now + self.UNKNOWN_KEY_REFETCH_S

    async def warm(self) -> None:
        """Fetch the certificates now (best effort), so the first request doesn't wait for them."""
        await self._refresh("")

    async def _key_for(self, kid: str) -> object:
        if self._should_fetch(kid):
            await self._refresh(kid)
        if not self._certs:
            raise AuthUnavailable("sign-in certificates are not available")
        pem = self._certs.get(kid)
        if pem is None:
            raise AuthError("token signed with an unknown key")
        key = self._keys.get(kid)
        if key is None:
            key = self._keys[kid] = x509.load_pem_x509_certificate(pem.encode("ascii")).public_key()
        return key

    async def verify(self, token: str) -> Identity:
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as exc:
            raise AuthError("malformed token") from exc
        if header.get("alg") != "RS256" or not isinstance(header.get("kid"), str):
            raise AuthError("unexpected token header")
        key = await self._key_for(header["kid"])
        try:
            claims = jwt.decode(
                token,
                key=key,
                algorithms=["RS256"],
                audience=self.project_id,
                issuer=f"https://securetoken.google.com/{self.project_id}",
                leeway=self._leeway,
                options={"require": ["exp", "iat", "aud", "iss", "sub"]},
            )
        except jwt.PyJWTError as exc:
            raise AuthError(f"invalid token: {type(exc).__name__}") from exc

        sub = claims.get("sub")
        if not isinstance(sub, str) or not sub or len(sub) > 128:
            raise AuthError("invalid subject")
        auth_time = claims.get("auth_time")
        if isinstance(auth_time, bool) or not isinstance(auth_time, (int, float)) \
                or auth_time > self._clock() + self._leeway:
            raise AuthError("invalid auth_time")
        provider = (claims.get("firebase") or {}).get("sign_in_provider")
        phone = claims.get("phone_number")
        if provider != "phone" or not isinstance(phone, str) or not phone.startswith("+"):
            raise AuthError("sign in with your phone number")
        return Identity(uid=sub, phone=phone, auth_time=float(auth_time))


class ServiceAccountVerifier(FirebaseVerifier):
    """Checks the ID token Cloud Scheduler sends to /internal/push: signed by Google, for our
    audience, from our scheduler's service account. Reuses the key caching above."""

    def __init__(self, audience: str, caller: str, fetch_certs: CertFetcher | None = None, **kwargs: Any) -> None:
        super().__init__(audience, fetch_certs or (lambda: fetch_google_certs(GOOGLE_OIDC_CERTS_URL)), **kwargs)
        self.caller = caller

    async def verify_caller(self, token: str) -> None:
        try:
            header = jwt.get_unverified_header(token)
        except jwt.PyJWTError as exc:
            raise AuthError("malformed token") from exc
        if header.get("alg") != "RS256" or not isinstance(header.get("kid"), str):
            raise AuthError("unexpected token header")
        key = await self._key_for(header["kid"])
        try:
            claims = jwt.decode(token, key=key, algorithms=["RS256"], audience=self.project_id,
                                issuer=["https://accounts.google.com", "accounts.google.com"],
                                leeway=self._leeway, options={"require": ["exp", "iat", "aud", "iss"]})
        except jwt.PyJWTError as exc:
            raise AuthError(f"invalid token: {type(exc).__name__}") from exc
        if claims.get("email") != self.caller or claims.get("email_verified") is not True:
            raise AuthError("not the push scheduler")


class DevPushCaller:
    """Local runs: `dev:push` may call /internal/push."""

    async def verify_caller(self, token: str) -> None:
        if token != "dev:push":
            raise AuthError("not the push scheduler")


class DevVerifier:
    """Local development only: accepts `dev:+919845031207` (uid becomes `dev-919845031207`).
    Settings refuse this mode when APP_ENV=production."""

    async def verify(self, token: str) -> Identity:
        prefix, _, phone = token.partition(":")
        if prefix != "dev" or not re.fullmatch(r"\+[0-9]{8,15}", phone):
            raise AuthError("dev tokens look like dev:+919845031207")
        return Identity(uid="dev-" + phone[1:], phone=phone, auth_time=time.time())
