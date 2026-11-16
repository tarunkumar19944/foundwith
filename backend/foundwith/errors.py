"""Errors the API returns. Every message is written to be shown to the person using the app."""

from __future__ import annotations

from typing import Any


class ApiError(Exception):
    status = 400
    code = "bad_request"

    def __init__(self, message: str, *, details: dict[str, Any] | None = None,
                 retry_after: int | None = None) -> None:
        super().__init__(message)
        self.message = message
        self.details = details or {}
        self.retry_after = retry_after


class Unauthenticated(ApiError):
    status, code = 401, "unauthenticated"


class InvalidIdentity(ApiError):
    status, code = 401, "invalid_identity"


class ReauthRequired(ApiError):
    """The account was deleted after this sign-in; a fresh OTP sign-in is needed to create a new one."""
    status, code = 401, "reauth_required"


class AccountSuspended(ApiError):
    status, code = 403, "account_suspended"


class NotRegistered(ApiError):
    status, code = 404, "not_registered"


class NotFound(ApiError):
    status, code = 404, "not_found"


class SyncRequired(ApiError):
    status, code = 409, "sync_required"


class ConsentRequired(ApiError):
    status, code = 409, "consent_required"


class FullSyncRequired(ApiError):
    status, code = 409, "full_sync_required"


class AccountExists(ApiError):
    """Taking a number out of Found With would also delete its account; the page must say so first."""
    status, code = 409, "account_exists"


class UpdateRequired(ApiError):
    """The app is older than MIN_APP_VERSION."""
    status, code = 426, "update_required"


class LimitReached(ApiError):
    status, code = 409, "limit_reached"


class InviteNotFound(ApiError):
    status, code = 404, "invite_not_found"


class InviteRejected(ApiError):
    """Own invite, too late to use one, or this number already joined through an invite."""
    status, code = 409, "invite_rejected"


class InvalidRequest(ApiError):
    status, code = 422, "invalid_request"


class InvalidNumber(ApiError):
    status, code = 422, "invalid_number"


class NameNotAllowed(ApiError):
    status, code = 422, "name_not_allowed"


class QuotaExceeded(ApiError):
    status, code = 429, "quota_exceeded"


class NameChangeTooSoon(ApiError):
    status, code = 429, "name_change_too_soon"


class RateLimited(ApiError):
    status, code = 429, "rate_limited"


class Unavailable(ApiError):
    status, code = 503, "unavailable"
