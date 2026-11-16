"""Deleting a person's sign-in account from Identity Platform when they delete their WhoHas account."""

from __future__ import annotations

import json
import urllib.error
import urllib.request
from typing import Protocol

import anyio

METADATA_TOKEN_URL = "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token"


class IdentityAdmin(Protocol):
    async def delete_user(self, uid: str) -> None: ...


class GoogleIdentityAdmin:
    """Calls the Identity Toolkit REST API as the Cloud Run service account
    (needs roles/identityplatform.admin or roles/firebaseauth.admin, granted in Terraform)."""

    def __init__(self, project_id: str) -> None:
        self.project_id = project_id

    async def delete_user(self, uid: str) -> None:
        await anyio.to_thread.run_sync(self._delete, uid)

    def _delete(self, uid: str) -> None:
        token_req = urllib.request.Request(METADATA_TOKEN_URL, headers={"Metadata-Flavor": "Google"})
        with urllib.request.urlopen(token_req, timeout=3) as resp:  # noqa: S310 (fixed metadata URL)
            access_token = json.loads(resp.read())["access_token"]
        req = urllib.request.Request(
            f"https://identitytoolkit.googleapis.com/v1/projects/{self.project_id}/accounts:delete",
            data=json.dumps({"localId": uid}).encode("utf-8"),
            method="POST",
            headers={"Authorization": f"Bearer {access_token}", "Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=5):  # noqa: S310 (fixed Google API URL)
                return
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            if exc.code == 400 and "USER_NOT_FOUND" in detail:
                return  # already gone
            raise RuntimeError(f"Identity Toolkit returned {exc.code}: {detail[:200]}") from exc


class NoopIdentityAdmin:
    """Used with dev sign-in, where there is no Identity Platform account to delete."""

    async def delete_user(self, uid: str) -> None:
        return None
