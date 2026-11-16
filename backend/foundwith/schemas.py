"""Request bodies. Responses are plain dicts built in service.py."""

from __future__ import annotations

from datetime import date
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class _Body(BaseModel):
    model_config = ConfigDict(extra="forbid")


class ProfileIn(_Body):
    # Only a rough bound here; the name rules (and their messages) live in names.py.
    display_name: str = Field(min_length=1, max_length=200)


class NewProfileIn(_Body):
    """Signing in creates the account straight away; the name comes later."""
    display_name: str | None = Field(default=None, max_length=200)


class SettingsIn(_Body):
    evening_note: bool


class OptOutIn(_Body):
    delete_account: bool = False


class HealthIn(_Body):
    """One phone's day, for the brand report. Nothing in it identifies the person or the phone."""
    day: date
    brand: str = Field(min_length=1, max_length=40, pattern=r"^[a-z0-9][a-z0-9 _.-]*$")
    android: int = Field(ge=1, le=99)
    calls_seen: int = Field(ge=0, le=2000)
    calls_checked: int = Field(ge=0, le=2000)


class SyncSettingIn(_Body):
    enabled: bool
    consent_version: str | None = Field(default=None, max_length=32)


class NumbersIn(_Body):
    # Entries are whatever is saved on the phone. One odd entry must never fail the whole sync,
    # so there is no per-entry length rule: anything unusable is skipped during normalisation.
    contacts: list[Any] = Field(default_factory=list, max_length=20_000)
    call_log: list[Any] = Field(default_factory=list, max_length=20_000)


class NumberChangesIn(_Body):
    """What changed on the phone since the last sync. Small by design: a contact saved, a call
    received. Anything bigger is a job for the full sync."""
    contacts_added: list[Any] = Field(default_factory=list, max_length=500)
    contacts_removed: list[Any] = Field(default_factory=list, max_length=500)
    call_log_added: list[Any] = Field(default_factory=list, max_length=500)
    call_log_removed: list[Any] = Field(default_factory=list, max_length=500)


class InviteIn(_Body):
    code: str = Field(min_length=4, max_length=16)


class TellIn(_Body):
    number: str = Field(max_length=64)


class LookupIn(_Body):
    number: str = Field(max_length=64)
    context: Literal["search", "call"] = "search"


class ReportIn(_Body):
    user_id: str = Field(max_length=64)
    reason: Literal["fake_name", "impersonation", "offensive", "harassment", "other"]
    note: str | None = Field(default=None, max_length=500)


class BlockIn(_Body):
    user_id: str = Field(max_length=64)


class DeviceIn(_Body):
    token: str = Field(min_length=20, max_length=4096)
