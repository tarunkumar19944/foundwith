import datetime as dt
import time

import jwt
import pytest
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

from foundwith.auth import AuthError, AuthUnavailable, DevVerifier, FirebaseVerifier, Identity

pytestmark = pytest.mark.anyio
PROJECT = "found-with-test"


def _key_and_cert():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "securetoken.system.gserviceaccount.com")])
    now = dt.datetime.now(dt.timezone.utc)
    cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
            .serial_number(1).not_valid_before(now - dt.timedelta(days=1))
            .not_valid_after(now + dt.timedelta(days=1)).sign(key, hashes.SHA256()))
    return key, cert.public_bytes(serialization.Encoding.PEM).decode()


KEY, PEM = _key_and_cert()


class Certs:
    def __init__(self):
        self.calls = 0

    async def __call__(self):
        self.calls += 1
        return {"kid-1": PEM}, 3600.0


def token(**overrides):
    now = int(time.time())
    claims = {"iss": f"https://securetoken.google.com/{PROJECT}", "aud": PROJECT, "sub": "user-123",
              "iat": now - 10, "exp": now + 3600, "auth_time": now - 20, "phone_number": "+919845031207",
              "firebase": {"sign_in_provider": "phone"}}
    headers = {"kid": overrides.pop("kid", "kid-1")}
    claims.update(overrides)
    return jwt.encode({k: v for k, v in claims.items() if v is not None}, KEY, algorithm="RS256", headers=headers)


async def test_valid_phone_token():
    certs = Certs()
    verifier = FirebaseVerifier(PROJECT, fetch_certs=certs)
    ident = await verifier.verify(token())
    assert (ident.uid, ident.phone) == ("user-123", "+919845031207")
    assert abs(ident.auth_time - (time.time() - 20)) < 5      # when the OTP sign-in happened
    await verifier.verify(token())
    assert certs.calls == 1  # certificates are cached


@pytest.mark.parametrize("bad", [
    {"aud": "someone-else"},
    {"iss": "https://securetoken.google.com/someone-else"},
    {"exp": int(time.time()) - 3600},
    {"auth_time": int(time.time()) + 3600},
    {"sub": ""},
    {"phone_number": None},
    {"firebase": {"sign_in_provider": "password"}},
    {"kid": "kid-unknown"},
])
async def test_rejected_tokens(bad):
    verifier = FirebaseVerifier(PROJECT, fetch_certs=Certs())
    with pytest.raises(AuthError):
        await verifier.verify(token(**bad))


async def test_rejects_tokens_not_signed_by_google():
    verifier = FirebaseVerifier(PROJECT, fetch_certs=Certs())
    forged = jwt.encode({"sub": "x"}, "secret-secret-secret-secret-secret", algorithm="HS256", headers={"kid": "kid-1"})
    with pytest.raises(AuthError):
        await verifier.verify(forged)
    other_key, _ = _key_and_cert()
    signed_elsewhere = jwt.encode({"sub": "x"}, other_key, algorithm="RS256", headers={"kid": "kid-1"})
    with pytest.raises(AuthError):
        await verifier.verify(signed_elsewhere)
    with pytest.raises(AuthError):
        await verifier.verify("not-a-token")


async def test_dev_tokens():
    ident = await DevVerifier().verify("dev:+919845031207")
    assert (ident.uid, ident.phone) == ("dev-919845031207", "+919845031207")
    assert isinstance(ident, Identity) and ident.auth_time > 0
    with pytest.raises(AuthError):
        await DevVerifier().verify("dev:9845031207")


class Clock:
    def __init__(self):
        self.now = time.time()

    def __call__(self):
        return self.now


class FlakyCerts(Certs):
    def __init__(self):
        super().__init__()
        self.fail = False

    async def __call__(self):
        self.calls += 1
        if self.fail:
            raise OSError("network is down")
        return {"kid-1": PEM}, 3600.0


async def test_made_up_key_ids_cannot_force_a_download_per_request():
    certs, clock = Certs(), Clock()
    verifier = FirebaseVerifier(PROJECT, fetch_certs=certs, clock=clock)
    await verifier.verify(token())
    for _ in range(20):
        with pytest.raises(AuthError):
            await verifier.verify(token(kid="made-up"))
    assert certs.calls == 1
    clock.now += 61                      # a genuinely new key is picked up after a minute
    with pytest.raises(AuthError):
        await verifier.verify(token(kid="made-up"))
    assert certs.calls == 2


async def test_sign_in_keeps_working_when_google_certs_cannot_be_refreshed():
    certs, clock = FlakyCerts(), Clock()
    verifier = FirebaseVerifier(PROJECT, fetch_certs=certs, clock=clock)
    await verifier.verify(token())
    certs.fail = True
    clock.now += 3601                    # cached certificates have expired
    assert (await verifier.verify(token(exp=int(clock.now) + 600))).uid == "user-123"
    assert certs.calls == 2
    await verifier.verify(token(exp=int(clock.now) + 600))
    assert certs.calls == 2              # no retry storm: next attempt waits
    clock.now += 16
    certs.fail = False
    await verifier.verify(token(exp=int(clock.now) + 600))
    assert certs.calls == 3


async def test_no_certificates_at_all_is_a_temporary_failure_not_a_sign_out():
    certs = FlakyCerts()
    certs.fail = True
    verifier = FirebaseVerifier(PROJECT, fetch_certs=certs)
    with pytest.raises(AuthUnavailable):
        await verifier.verify(token())
    with pytest.raises(AuthUnavailable):
        await verifier.verify(token())
    assert certs.calls == 1              # fails fast between attempts
