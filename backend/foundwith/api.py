"""HTTP API (Starlette). Run with: uvicorn foundwith.api:create_app --factory

All /v1 endpoints need `Authorization: Bearer <Identity Platform ID token>`.
Phone numbers only ever travel in request bodies, never in URLs, so they can't end up in request logs."""

from __future__ import annotations

import json
import logging
import re
import time
from collections.abc import Awaitable, Callable
from contextlib import asynccontextmanager
from typing import Any, TypeVar

import anyio
from pydantic import BaseModel, ValidationError
from starlette.applications import Starlette
from starlette.exceptions import HTTPException
from starlette.middleware import Middleware
from starlette.middleware.cors import CORSMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, Response
from starlette.routing import Route
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from . import push, web
from .auth import (AuthError, AuthUnavailable, DevPushCaller, DevVerifier, FirebaseVerifier, Identity,
                   ServiceAccountVerifier)
from .config import Settings
from .db import AsyncpgDatabase, Database, Row
from .errors import ApiError, InvalidRequest, RateLimited, Unauthenticated, Unavailable, UpdateRequired
from .identity import GoogleIdentityAdmin, IdentityAdmin, NoopIdentityAdmin
from .logs import setup_logging
from .numbers import NumberHasher, normalise
from .ratelimit import RateLimiter
from .schemas import (BlockIn, DeviceIn, HealthIn, InviteIn, LookupIn, TellIn, NewProfileIn, NumberChangesIn, NumbersIn,
                      OptOutIn, ProfileIn, ReportIn, SettingsIn, SyncSettingIn)
from .service import Service

log = logging.getLogger("foundwith.http")
Model = TypeVar("Model", bound=BaseModel)
_REF_IN_PATH = re.compile(r"/[A-Za-z0-9_-]{43}(?=/|$)")

# Database errors that mean "try again in a moment", matched by class name so this module
# doesn't have to import the driver.
_TRANSIENT_DB_ERRORS = frozenset({
    "PostgresConnectionError", "TooManyConnectionsError", "CannotConnectNowError",
    "AdminShutdownError", "CrashShutdownError", "DeadlockDetectedError", "QueryCanceledError",
    "LockNotAvailableError", "SerializationError",
})


def error_response(status: int, code: str, message: str, details: dict[str, Any] | None = None,
                   retry_after: int | None = None) -> JSONResponse:
    body: dict[str, Any] = {"error": {"code": code, "message": message}}
    if details:
        body["error"]["details"] = details
    headers = {"Retry-After": str(retry_after)} if retry_after else None
    return JSONResponse(body, status_code=status, headers=headers)


async def read_body(request: Request, model: type[Model]) -> Model:
    raw = await request.body()
    try:
        data = json.loads(raw or b"{}")
    except (ValueError, RecursionError) as exc:
        raise ApiError("The request body isn't valid JSON.") from exc
    try:
        return model.model_validate(data)
    except ValidationError as exc:
        fields = [{"field": ".".join(str(p) for p in err["loc"]) or "body", "problem": err["msg"]}
                  for err in exc.errors(include_url=False, include_input=False)][:20]
        raise InvalidRequest("Some fields are missing or invalid.", details={"fields": fields}) from exc


def int_param(request: Request, name: str, low: int, high: int) -> int | None:
    raw = request.query_params.get(name)
    if raw is None:
        return None
    if not (raw.isascii() and raw.isdigit()) or len(raw) > 9 or not low <= int(raw) <= high:
        raise InvalidRequest(f"'{name}' must be a whole number from {low} to {high}.")
    return int(raw)


async def authenticate(request: Request) -> Identity:
    scheme, _, token = request.headers.get("authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not token.strip():
        raise Unauthenticated("Sign in to continue.")
    try:
        return await request.app.state.verifier.verify(token.strip())
    except AuthError as exc:
        raise Unauthenticated("Your session has expired. Sign in again.") from exc
    except AuthUnavailable as exc:
        raise Unavailable("We can't check your sign-in right now. Try again in a moment.", retry_after=5) from exc


Handler = Callable[[Request, Service, Identity, Row], Awaitable[Response]]


def signed_in(fn: Handler, *, needs_profile: bool = True, allow_suspended: bool = False,
              heavy: bool = False) -> Callable[[Request], Awaitable[Response]]:
    """Wrap a handler: check the token, apply the per-person rate limit, load the profile."""

    async def endpoint(request: Request) -> Response:
        state = request.app.state
        settings: Settings = state.settings
        version = request.headers.get("x-app-version", "")
        if settings.min_app_version and version.isdigit() and int(version) < settings.min_app_version:
            raise UpdateRequired("This version of WhoHas is too old. Update it from the Play Store.")
        ident = await authenticate(request)
        kind, per_minute = (("heavy", settings.rate_limit_heavy_per_minute) if heavy
                            else ("any", settings.rate_limit_per_minute))
        # The automated tests' test numbers run from many phones at once: no per-person limit.
        exempt = normalise(ident.phone, settings.country_code) in state.limit_exempt
        wait = None if exempt else state.rate_limiter.check(ident.uid, kind, per_minute)
        if wait is not None:
            raise RateLimited("Too many requests. Try again in a few seconds.", retry_after=wait)
        service: Service = state.service
        user = await service.current_user(ident, allow_suspended=allow_suspended) if needs_profile else None
        if user is not None:
            request.scope.setdefault("foundwith", {})["user"] = user["id"]
        return await fn(request, service, ident, user)

    return endpoint


# ---------------------------------------------------------------- endpoints

async def healthz(request: Request) -> Response:
    return JSONResponse({"status": "ok"})


async def readyz(request: Request) -> Response:
    try:
        async with request.app.state.db.acquire() as conn:
            await conn.fetchval("SELECT 1")
    except Exception:  # noqa: BLE001
        log.exception("readiness check failed")
        return error_response(503, "not_ready", "The database isn't reachable.")
    return JSONResponse({"status": "ready"})


async def create_profile(request: Request, svc: Service, ident: Identity, _: Row) -> Response:
    body = await read_body(request, NewProfileIn)
    user = await svc.register(ident, body.display_name)
    return JSONResponse(await svc.profile(user, ident))


async def get_profile(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse(await svc.profile(user, ident))


async def update_profile(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, ProfileIn)
    return JSONResponse(await svc.profile(await svc.rename(user, body.display_name), ident))


async def put_settings(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, SettingsIn)
    await svc.set_evening_note(user, body.evening_note)
    return JSONResponse(await svc.profile(await svc.current_user(ident), ident))


async def post_health(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, HealthIn)
    await svc.record_health(body.brand, body.android, body.day, body.calls_seen, body.calls_checked)
    return Response(status_code=204)


async def delete_profile(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse(await svc.delete_account(user))


async def set_sync(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, SyncSettingIn)
    user = await svc.set_sync(user, body.enabled, body.consent_version)
    return JSONResponse(await svc.profile(user, ident))


async def sync_numbers(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, NumbersIn)
    return JSONResponse(await svc.sync_numbers(user, body.contacts, body.call_log))


async def change_numbers(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, NumberChangesIn)
    return JSONResponse(await svc.apply_changes(user, body.contacts_added, body.contacts_removed,
                                                body.call_log_added, body.call_log_removed))


async def get_cards(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    limit = int_param(request, "limit", 1, svc.settings.cards_page_max)
    return JSONResponse(await svc.cards(user, request.query_params.get("cursor"), limit))


async def lookup(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, LookupIn)
    return JSONResponse(await svc.lookup(user, body.number, body.context))


async def get_holders(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    limit = int_param(request, "limit", 1, 100)
    return JSONResponse(await svc.holders(user, request.path_params["ref"], request.query_params.get("cursor"), limit))


async def get_circle(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse(await svc.circle(user, str(request.base_url)))


async def redeem_invite(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, InviteIn)
    return JSONResponse(await svc.redeem_invite(user, body.code))


async def post_tell(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, TellIn)
    await svc.tell(user, body.number)
    return Response(status_code=204)


async def put_device(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, DeviceIn)
    await svc.save_device(user, body.token)
    return Response(status_code=204)


async def delete_device(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, DeviceIn)
    await svc.forget_device(user, body.token)
    return Response(status_code=204)


async def get_my_data(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse(await svc.my_data(user, ident))


async def get_export(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse(await svc.export(user, ident),
                        headers={"Content-Disposition": 'attachment; filename="whohas-data.json"'})


async def _scheduler_only(request: Request) -> bool:
    """Internal endpoints are called by Cloud Scheduler with its own Google-signed token.
    False when pushes are off (the endpoint then doesn't exist)."""
    state = request.app.state
    if state.push_caller is None:
        return False
    scheme, _, token = request.headers.get("authorization", "").partition(" ")
    try:
        if scheme.lower() != "bearer":
            raise AuthError("no token")
        await state.push_caller.verify_caller(token.strip())
    except AuthError as exc:
        raise Unauthenticated("Only the push scheduler may call this.") from exc
    except AuthUnavailable as exc:
        raise Unavailable("Can't check the caller right now.", retry_after=30) from exc
    return True


def _not_found() -> Response:
    return JSONResponse({"error": {"code": "not_found", "message": "Not found."}}, status_code=404)


async def dispatch_pushes(request: Request) -> Response:
    """Every minute: silent refreshes that are due."""
    if not await _scheduler_only(request):
        return _not_found()
    return JSONResponse(await push.dispatch(request.app.state.db, request.app.state.push_sender))


async def wake_ups(request: Request) -> Response:
    """A few times a day: apps check their call log for calls they didn't get to check."""
    if not await _scheduler_only(request):
        return _not_found()
    state = request.app.state
    return JSONResponse(await push.send_wakeups(state.db, state.push_sender))


async def list_hidden(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse({"refs": await svc.hidden(user)})


async def hide_number(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    await svc.hide(user, request.path_params["ref"])
    return Response(status_code=204)


async def unhide_number(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    await svc.unhide(user, request.path_params["ref"])
    return Response(status_code=204)


async def delete_by_phone(request: Request, svc: Service, ident: Identity, _: Row) -> Response:
    return JSONResponse(await svc.delete_by_phone(ident))


async def get_opt_out(request: Request, svc: Service, ident: Identity, _: Row) -> Response:
    return JSONResponse(await svc.opt_out_status(ident))


async def put_opt_out(request: Request, svc: Service, ident: Identity, _: Row) -> Response:
    body = await read_body(request, OptOutIn)
    return JSONResponse(await svc.opt_out(ident, body.delete_account))


async def delete_opt_out(request: Request, svc: Service, ident: Identity, _: Row) -> Response:
    return JSONResponse(await svc.opt_in(ident))


async def create_report(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, ReportIn)
    await svc.report(user, body.user_id, body.reason, body.note)
    return Response(status_code=204)


async def list_blocks(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    return JSONResponse({"blocks": await svc.blocks(user)})


async def create_block(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    body = await read_body(request, BlockIn)
    return JSONResponse(await svc.block(user, body.user_id), status_code=201)


async def delete_block(request: Request, svc: Service, ident: Identity, user: Row) -> Response:
    await svc.unblock(user, request.path_params["block_id"])
    return Response(status_code=204)


# ---------------------------------------------------------------- error handling and logging

async def handle_api_error(request: Request, exc: Exception) -> Response:
    assert isinstance(exc, ApiError)
    return error_response(exc.status, exc.code, exc.message, exc.details, exc.retry_after)


async def handle_http_error(request: Request, exc: Exception) -> Response:
    assert isinstance(exc, HTTPException)
    codes = {404: ("not_found", "There's nothing at this address."),
             405: ("method_not_allowed", "This address doesn't accept that method."),
             413: ("body_too_large", "The request is too large.")}
    code, message = codes.get(exc.status_code, ("http_error", str(exc.detail)))
    return error_response(exc.status_code, code, message)


def _is_temporary(exc: BaseException) -> bool:
    """Timeouts, dropped connections and a busy or restarting database: worth a retry by the client."""
    if isinstance(exc, (TimeoutError, OSError)):
        return True
    return any(cls.__module__.startswith("asyncpg") and cls.__name__ in _TRANSIENT_DB_ERRORS
               for cls in type(exc).__mro__)


async def handle_unexpected(request: Request, exc: Exception) -> Response:
    if _is_temporary(exc):
        log.error("temporary failure: %s", type(exc).__name__, exc_info=exc, extra={"event": "temporary_failure"})
        return error_response(503, "unavailable", "WhoHas is busy right now. Try again in a moment.",
                              retry_after=3)
    if any(cls.__name__ == "UniqueViolationError" for cls in type(exc).__mro__):
        # Two requests raced to create the same thing; the second one loses and can simply retry.
        return error_response(409, "conflict", "That was already done by another request. Try again.")
    log.error("unhandled error", exc_info=exc, extra={"event": "unhandled_error"})
    return error_response(500, "internal", "Something went wrong on our side. Try again in a moment.")


class AccessLog:
    """Logs one line per request (route with number refs masked, status, latency, user id) and adds
    no-store caching headers, since every API response is personal data."""

    def __init__(self, app: ASGIApp, project_id: str) -> None:
        self.app = app
        self.project_id = project_id

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        start = time.perf_counter()
        status = 500

        async def send_with_headers(message: Message) -> None:
            nonlocal status
            if message["type"] == "http.response.start":
                status = message["status"]
                headers = list(message.get("headers", []))
                headers += [(b"cache-control", b"no-store"), (b"x-content-type-options", b"nosniff")]
                message = {**message, "headers": headers}
            await send(message)

        try:
            await self.app(scope, receive, send_with_headers)
        finally:
            path = scope.get("path", "")
            if path not in ("/healthz", "/readyz"):
                extra: dict[str, Any] = {
                    "event": "request",
                    "method": scope.get("method"),
                    "route": _REF_IN_PATH.sub("/{ref}", path),
                    "status": status,
                    "latency_ms": round((time.perf_counter() - start) * 1000, 1),
                    "user": scope.get("foundwith", {}).get("user"),
                }
                trace_header = dict(scope.get("headers", [])).get(b"x-cloud-trace-context", b"").decode("latin-1")
                if trace_header and self.project_id:
                    extra["trace"] = f"projects/{self.project_id}/traces/{trace_header.split('/')[0]}"
                log.info("%s %s %s", extra["method"], extra["route"], status, extra=extra)


# ---------------------------------------------------------------- app factory

def create_app(settings: Settings | None = None, *, db: Database | None = None, verifier: Any = None,
               identity: IdentityAdmin | None = None, rate_limiter: RateLimiter | None = None,
               configure_logging: bool = True, push_sender: Any = None, push_caller: Any = None) -> Starlette:
    settings = settings or Settings.from_env()
    if configure_logging:
        setup_logging()
    hasher = NumberHasher(settings.number_pepper)
    if verifier is None:
        verifier = DevVerifier() if settings.auth_mode == "dev" else FirebaseVerifier(settings.firebase_project_id)
    if identity is None:
        identity = NoopIdentityAdmin() if settings.auth_mode == "dev" else GoogleIdentityAdmin(settings.firebase_project_id)
    if settings.push_mode == "fcm":
        push_sender = push_sender or push.FcmSender(settings.fcm_project_id)
        push_caller = push_caller or ServiceAccountVerifier(settings.push_audience, settings.push_caller)
    elif settings.push_mode == "log":
        push_sender = push_sender or push.LogSender()
        push_caller = push_caller or DevPushCaller()
    else:
        push_sender = push_caller = None

    @asynccontextmanager
    async def lifespan(app: Starlette):  # type: ignore[no-untyped-def]
        database = db if db is not None else await AsyncpgDatabase.connect(settings)
        app.state.db = database
        app.state.settings = settings
        app.state.verifier = verifier
        app.state.rate_limiter = rate_limiter or RateLimiter()
        app.state.limit_exempt = frozenset(
            e164 for e164 in (normalise(n, settings.country_code) for n in settings.quota_exempt_numbers) if e164)
        app.state.push_sender = push_sender
        app.state.push_caller = push_caller
        app.state.service = Service(database, hasher, settings, identity)
        # Download the sign-in certificates before taking traffic, so the first incoming-call
        # lookup on a fresh instance doesn't wait for them.
        warm = getattr(verifier, "warm", None)
        if warm is not None:
            with anyio.move_on_after(5):
                await warm()
        try:
            yield
        finally:
            if db is None:
                await database.close()

    routes = [
        Route("/healthz", healthz, methods=["GET"]),
        Route("/readyz", readyz, methods=["GET"]),
        Route("/v1/me", signed_in(create_profile, needs_profile=False), methods=["POST"]),
        Route("/v1/me", signed_in(get_profile, allow_suspended=True), methods=["GET"]),
        Route("/v1/me", signed_in(update_profile), methods=["PATCH"]),
        Route("/v1/me", signed_in(delete_profile, allow_suspended=True), methods=["DELETE"]),
        Route("/v1/me/settings", signed_in(put_settings), methods=["PUT"]),
        Route("/v1/me/health", signed_in(post_health), methods=["POST"]),
        Route("/v1/me/sync", signed_in(set_sync), methods=["PUT"]),
        Route("/v1/me/numbers", signed_in(sync_numbers, heavy=True), methods=["PUT"]),
        Route("/v1/me/numbers/changes", signed_in(change_numbers), methods=["POST"]),
        Route("/v1/cards", signed_in(get_cards, heavy=True), methods=["GET"]),
        Route("/v1/lookup", signed_in(lookup), methods=["POST"]),
        Route("/v1/numbers/{ref}/holders", signed_in(get_holders), methods=["GET"]),
        Route("/v1/me/circle", signed_in(get_circle), methods=["GET"]),
        Route("/v1/me/invite/redeem", signed_in(redeem_invite), methods=["POST"]),
        Route("/v1/tells", signed_in(post_tell), methods=["POST"]),
        Route("/i/{code}", web.invite_page, methods=["GET"]),
        Route("/out", web.opt_out_page, methods=["GET"]),
        Route("/delete", web.delete_page, methods=["GET"]),
        Route("/v1/me/device", signed_in(put_device), methods=["PUT"]),
        Route("/v1/me/device", signed_in(delete_device, allow_suspended=True), methods=["DELETE"]),
        Route("/v1/me/data", signed_in(get_my_data, allow_suspended=True), methods=["GET"]),
        Route("/v1/me/export", signed_in(get_export, heavy=True, allow_suspended=True), methods=["GET"]),
        Route("/internal/push", dispatch_pushes, methods=["POST"]),
        Route("/internal/push/wake", wake_ups, methods=["POST"]),
        Route("/v1/hidden", signed_in(list_hidden), methods=["GET"]),
        Route("/v1/hidden/{ref}", signed_in(hide_number), methods=["PUT"]),
        Route("/v1/hidden/{ref}", signed_in(unhide_number), methods=["DELETE"]),
        # Taking your own number out of Found With needs a verified sign-in, not a profile.
        Route("/v1/account", signed_in(delete_by_phone, needs_profile=False), methods=["DELETE"]),
        Route("/v1/optout", signed_in(get_opt_out, needs_profile=False), methods=["GET"]),
        Route("/v1/optout", signed_in(put_opt_out, needs_profile=False), methods=["PUT"]),
        Route("/v1/optout", signed_in(delete_opt_out, needs_profile=False), methods=["DELETE"]),
        Route("/v1/reports", signed_in(create_report), methods=["POST"]),
        Route("/v1/blocks", signed_in(list_blocks), methods=["GET"]),
        Route("/v1/blocks", signed_in(create_block), methods=["POST"]),
        Route("/v1/blocks/{block_id:int}", signed_in(delete_block), methods=["DELETE"]),
    ]
    middleware = [Middleware(AccessLog, project_id=settings.firebase_project_id)]
    if settings.cors_origins:
        middleware.append(Middleware(
            CORSMiddleware, allow_origins=list(settings.cors_origins),
            allow_methods=["GET", "POST", "PUT", "PATCH", "DELETE"],
            allow_headers=["authorization", "content-type", "x-app-version"], max_age=600))
    return Starlette(
        routes=routes,
        middleware=middleware,
        exception_handlers={ApiError: handle_api_error, HTTPException: handle_http_error, Exception: handle_unexpected},
        lifespan=lifespan,
        max_body_size=settings.max_body_bytes,
    )
