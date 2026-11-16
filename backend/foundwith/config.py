"""Runtime settings, read from environment variables (Cloud Run injects them; see infra/terraform)."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, field


class ConfigError(RuntimeError):
    pass


def _int(env: Mapping[str, str], key: str, default: int) -> int:
    raw = env.get(key)
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ConfigError(f"{key} must be an integer, got {raw!r}") from exc


# Environment variable -> integer setting. Anything not set keeps the default below.
_INT_ENV = {
    "DB_PORT": "db_port",
    "DB_POOL_SIZE": "db_pool_size",
    "SEARCH_DAILY_LIMIT": "search_daily_limit",
    "CALL_DAILY_LIMIT": "call_daily_limit",
    "MAX_NUMBERS_PER_USER": "max_numbers_per_user",
    "NEW_NUMBERS_BURST": "new_numbers_burst",
    "NEW_NUMBERS_PER_DAY": "new_numbers_per_day",
    "HOLDERS_LIST_CAP": "holders_list_cap",
    "INACTIVE_DAYS": "inactive_days",
    "LOOKUP_LOG_DAYS": "lookup_log_days",
    "NAME_CHANGE_DAYS": "name_change_days",
    "INVITE_WINDOW_DAYS": "invite_window_days",
    "CIRCLE_GOAL": "circle_goal",
    "POPULAR_NAME_MIN_PEOPLE": "popular_name_min_people",
    "POPULAR_NAME_MIN_ACCOUNT_DAYS": "popular_name_min_account_days",
    "CARDS_PUSH_GAP_S": "cards_push_gap_s",
    "MIN_APP_VERSION": "min_app_version",
}


@dataclass(frozen=True)
class Settings:
    # Where we run. "production" refuses unsafe settings such as dev sign-in.
    env: str = "development"
    # "firebase" verifies Identity Platform ID tokens; "dev" accepts "dev:<phone>" tokens (local only).
    auth_mode: str = "firebase"
    firebase_project_id: str = ""

    # Database. On Cloud Run, DB_HOST is the Cloud SQL socket directory: /cloudsql/PROJECT:REGION:INSTANCE
    db_host: str = "localhost"
    db_port: int = 5432
    db_name: str = "foundwith"
    db_user: str = "foundwith_app"
    db_password: str = field(default="", repr=False)
    # Connections per API instance. instances x pool size must stay below the database's
    # max_connections (100 on a 3.75 GB Cloud SQL instance, 50 on db-g1-small).
    db_pool_size: int = 6
    db_acquire_timeout_s: float = 5.0

    # Secret key for hashing phone numbers. Never rotate it in place: every stored hash depends on it.
    number_pepper: bytes = field(default=b"", repr=False)
    # Secret key for contact names: they are stored encrypted with a key derived from it, next to a
    # keyed hash used to count identical names. Kept in Secret Manager like the pepper.
    name_key: bytes = field(default=b"", repr=False)
    country_code: str = "91"

    # --- Product rules -------------------------------------------------------------------
    consent_version: str = "2026-11-16"
    # Who is named on a card: "everyone" who has the number saved, or only people the viewer has
    # saved themselves ("known"), with everyone else shown as a count.
    visibility: str = "known"
    quota_timezone: str = "Asia/Kolkata"
    search_daily_limit: int = 50
    call_daily_limit: int = 100

    # Address book. A person can store up to max_numbers_per_user numbers. New numbers are admitted
    # from an allowance that starts at new_numbers_burst and refills at new_numbers_per_day, so
    # putting numbers into contacts can't be used as an unlimited lookup. Numbers over the
    # allowance are not rejected; they are admitted by later syncs.
    max_numbers_per_user: int = 10_000
    new_numbers_burst: int = 3_000
    new_numbers_per_day: int = 150
    max_hidden_numbers: int = 1_000

    # Cards and holder lists
    card_sample_size: int = 3
    count_cap: int = 10_000
    cards_page_default: int = 100
    cards_page_max: int = 500
    holder_page_size: int = 50
    holders_list_cap: int = 500        # names that can be paged for one number

    # Invites: who joined through whose link, so the inviter hears about it. No reward.
    invite_window_days: int = 7        # an invite only counts in a new account's first week
    circle_goal: int = 20              # contacts on Found With at which most cards fill in
    play_store_url: str = "https://play.google.com/store/apps/details?id=app.foundwith"

    # Popular name: the name at least this many established people saved for a number. Established
    # means an account this old, used in the last 30 days, syncing and not suspended, so a handful
    # of fresh accounts can't name somebody. Worked out nightly.
    popular_name_min_people: int = 3
    popular_name_min_account_days: int = 7   # set to 0 for a test group whose accounts are all new
    popular_name_active_days: int = 30

    # Live updates (Firebase Cloud Messaging). "off": nothing is queued; "log": queued and logged,
    # for local runs; "fcm": sent. Cloud Scheduler calls /internal/push every minute.
    push_mode: str = "off"
    fcm_project_id: str = ""           # defaults to firebase_project_id
    push_caller: str = ""              # the service account Cloud Scheduler signs in as
    push_audience: str = ""            # the audience Cloud Scheduler puts in its token
    cards_push_gap_s: int = 600        # card changes reach a person at most every 10 minutes

    # Phones running an older app than this (versionCode, sent as X-App-Version) are asked to update.
    min_app_version: int = 0
    # The opt-out web page signs people in with Identity Platform's web SDK.
    firebase_web_api_key: str = ""
    firebase_auth_domain: str = ""

    # People
    name_change_days: int = 14         # wait between changes of display name (the first change is free)
    reports_daily_limit: int = 20
    max_blocks: int = 500

    # Lifecycle
    inactive_days: int = 90            # accounts not seen for this long are deleted
    lookup_log_days: int = 180         # how long the record of searches and call lookups is kept
    tombstone_days: int = 30           # how long a deleted account's limits are remembered
    suspension_memory_days: int = 90   # how long a suspension outlives a deleted account

    # HTTP
    cors_origins: tuple[str, ...] = ()
    max_body_bytes: int = 1_500_000
    rate_limit_per_minute: int = 120   # per person, per API instance
    rate_limit_heavy_per_minute: int = 20   # cards, sync

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None, *, for_api: bool = True) -> Settings:
        """`for_api=False` is for the migration, maintenance and admin jobs, which never hash
        numbers and so don't receive the pepper."""
        env = os.environ if env is None else env
        values: dict[str, object] = {name: _int(env, key, getattr(cls, name)) for key, name in _INT_ENV.items()}
        settings = cls(
            env=env.get("APP_ENV", "development"),
            auth_mode=env.get("AUTH_MODE", "firebase"),
            firebase_project_id=env.get("FIREBASE_PROJECT_ID", env.get("GOOGLE_CLOUD_PROJECT", "")),
            db_host=env.get("DB_HOST", "localhost"),
            db_name=env.get("DB_NAME", "foundwith"),
            db_user=env.get("DB_USER", "foundwith_app"),
            db_password=env.get("DB_PASSWORD", ""),
            number_pepper=env.get("NUMBER_PEPPER", "").encode("utf-8"),
            name_key=env.get("NAME_KEY", "").encode("utf-8"),
            country_code=env.get("DEFAULT_COUNTRY_CODE", "91"),
            consent_version=env.get("CONSENT_VERSION", cls.consent_version),
            visibility=env.get("HOLDER_VISIBILITY", cls.visibility),
            quota_timezone=env.get("QUOTA_TIMEZONE", cls.quota_timezone),
            cors_origins=tuple(o.strip() for o in env.get("CORS_ORIGINS", "").split(",") if o.strip()),
            play_store_url=env.get("PLAY_STORE_URL", cls.play_store_url),
            push_mode=env.get("PUSH_MODE", cls.push_mode),
            fcm_project_id=env.get("FCM_PROJECT_ID", env.get("FIREBASE_PROJECT_ID", env.get("GOOGLE_CLOUD_PROJECT", ""))),
            push_caller=env.get("PUSH_CALLER", ""),
            push_audience=env.get("PUSH_AUDIENCE", ""),
            firebase_web_api_key=env.get("FIREBASE_WEB_API_KEY", ""),
            firebase_auth_domain=env.get("FIREBASE_AUTH_DOMAIN", ""),
            **values,  # type: ignore[arg-type]
        )
        settings.validate(require_pepper=for_api)
        return settings

    def validate(self, *, require_pepper: bool = True) -> None:
        if self.auth_mode not in ("firebase", "dev"):
            raise ConfigError("AUTH_MODE must be 'firebase' or 'dev'")
        if self.auth_mode == "firebase" and not self.firebase_project_id:
            raise ConfigError("FIREBASE_PROJECT_ID is required when AUTH_MODE=firebase")
        if self.env == "production" and self.auth_mode != "firebase":
            raise ConfigError("Dev sign-in is not allowed when APP_ENV=production")
        if require_pepper and len(self.number_pepper) < 32:
            raise ConfigError("NUMBER_PEPPER must be set to at least 32 characters")
        if require_pepper and len(self.name_key) < 32:
            raise ConfigError("NAME_KEY must be set to at least 32 characters")
        if require_pepper and self.name_key == self.number_pepper:
            raise ConfigError("NAME_KEY must be different from NUMBER_PEPPER")
        if not (self.country_code.isdigit() and 1 <= len(self.country_code) <= 3):
            raise ConfigError("DEFAULT_COUNTRY_CODE must be 1-3 digits")
        if self.visibility not in ("everyone", "known"):
            raise ConfigError("HOLDER_VISIBILITY must be 'everyone' or 'known'")
        for name in ("db_pool_size", "search_daily_limit", "call_daily_limit", "max_numbers_per_user",
                     "new_numbers_burst", "new_numbers_per_day", "max_hidden_numbers", "card_sample_size",
                     "count_cap", "cards_page_default", "cards_page_max", "holder_page_size",
                     "holders_list_cap", "reports_daily_limit", "max_blocks", "inactive_days",
                     "lookup_log_days", "tombstone_days", "suspension_memory_days",
                     "rate_limit_per_minute", "rate_limit_heavy_per_minute"):
            if getattr(self, name) < 1:
                raise ConfigError(f"{name} must be at least 1")
        if self.name_change_days < 0:
            raise ConfigError("name_change_days can't be negative")
        if self.popular_name_min_people < 2:
            raise ConfigError("POPULAR_NAME_MIN_PEOPLE must be at least 2")
        if self.popular_name_min_account_days < 0:
            raise ConfigError("POPULAR_NAME_MIN_ACCOUNT_DAYS can't be negative")
        if self.min_app_version < 0:
            raise ConfigError("MIN_APP_VERSION can't be negative")
        if self.push_mode not in ("off", "log", "fcm"):
            raise ConfigError("PUSH_MODE must be 'off', 'log' or 'fcm'")
        if self.push_mode == "fcm" and not (self.fcm_project_id and self.push_caller and self.push_audience):
            raise ConfigError("PUSH_MODE=fcm needs FCM_PROJECT_ID, PUSH_CALLER and PUSH_AUDIENCE")
        if self.push_mode == "log" and self.env == "production":
            raise ConfigError("PUSH_MODE=log is for local runs only")
        for name in ("invite_window_days", "circle_goal", "popular_name_active_days", "cards_push_gap_s"):
            if getattr(self, name) < 1:
                raise ConfigError(f"{name} must be at least 1")
        # Invite links append "&referrer=…", so the store address must already have a query string.
        if not (self.play_store_url.startswith("https://") and "?" in self.play_store_url):
            raise ConfigError("PLAY_STORE_URL must be an https address with a query string, like the Play listing's ?id=…")
        if self.inactive_days < 30:
            raise ConfigError("INACTIVE_DAYS must be at least 30, so a short break never deletes an account")
