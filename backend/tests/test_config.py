import pytest

from foundwith.config import ConfigError, Settings

BASE = {"AUTH_MODE": "dev", "NUMBER_PEPPER": "p" * 32, "NAME_KEY": "n" * 32}


def test_invite_settings_come_from_the_environment():
    s = Settings.from_env({**BASE, "INVITE_WINDOW_DAYS": "3", "CIRCLE_GOAL": "15",
                           "PLAY_STORE_URL": "https://play.google.com/store/apps/details?id=app.whohas.beta"})
    assert (s.invite_window_days, s.circle_goal) == (3, 15)
    assert s.play_store_url.endswith("id=app.whohas.beta")


def test_defaults():
    s = Settings.from_env(BASE)
    assert (s.invite_window_days, s.circle_goal) == (7, 20)
    assert (s.popular_name_min_people, s.popular_name_min_account_days, s.min_app_version) == (3, 7, 0)


def test_new_settings_come_from_the_environment():
    s = Settings.from_env({**BASE, "POPULAR_NAME_MIN_PEOPLE": "5", "POPULAR_NAME_MIN_ACCOUNT_DAYS": "0",
                           "MIN_APP_VERSION": "12", "FIREBASE_WEB_API_KEY": "web-key",
                           "FIREBASE_AUTH_DOMAIN": "found-with.firebaseapp.com"})
    assert (s.popular_name_min_people, s.popular_name_min_account_days, s.min_app_version) == (5, 0, 12)
    assert (s.firebase_web_api_key, s.firebase_auth_domain) == ("web-key", "found-with.firebaseapp.com")
    assert s.name_key == b"n" * 32


@pytest.mark.parametrize("env", [
    {"NAME_KEY": ""},
    {"NAME_KEY": "short"},
    {"NAME_KEY": "p" * 32},                     # the same as the number pepper
    {"POPULAR_NAME_MIN_PEOPLE": "1"},
    {"POPULAR_NAME_MIN_ACCOUNT_DAYS": "-1"},
    {"MIN_APP_VERSION": "-3"},
])
def test_bad_name_settings_stop_start_up(env):
    with pytest.raises(ConfigError):
        Settings.from_env({**BASE, **env})


def test_jobs_dont_need_the_secrets():
    s = Settings.from_env({"AUTH_MODE": "dev"}, for_api=False)
    assert s.name_key == b""


@pytest.mark.parametrize("env", [
    {"CIRCLE_GOAL": "0"},
    {"INVITE_WINDOW_DAYS": "0"},
    {"PLAY_STORE_URL": "http://play.google.com/store/apps/details?id=app.whohas"},
    {"PLAY_STORE_URL": "https://play.google.com/store/apps/details"},
])
def test_bad_invite_settings_stop_start_up(env):
    with pytest.raises(ConfigError):
        Settings.from_env({**BASE, **env})
