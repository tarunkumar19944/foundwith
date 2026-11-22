# Phone sign-in through Identity Platform (Firebase Authentication with OTP).
# SMS is restricted to Indian numbers, which blocks SMS-pumping fraud from other countries.
# Applying this upgrades the project to Identity Platform, which needs billing enabled.

resource "google_identity_platform_config" "auth" {
  project                    = var.project_id
  autodelete_anonymous_users = true

  sign_in {
    allow_duplicate_emails = false

    phone_number {
      enabled            = true
      test_phone_numbers = var.test_phone_numbers
    }
  }

  # Logs every sign-in request with its error, in Cloud Logging (resource "identitytoolkit_project"),
  # so a failed sign-in can be explained from the server side.
  monitoring {
    request_logging {
      enabled = true
    }
  }

  sms_region_config {
    allowlist_only {
      allowed_regions = ["IN"]
    }
  }

  # Web pages allowed to start a sign-in (the Android app doesn't need this): the API's own
  # address, for the opt-out page. "localhost" is left out in production; add it to
  # auth_extra_domains only while testing a web page locally.
  authorized_domains = concat(
    ["${var.project_id}.firebaseapp.com", "${var.project_id}.web.app",
      "${var.public_site_id}.web.app", "${var.public_site_id}.firebaseapp.com",
    trimprefix(google_cloud_run_v2_service.api.uri, "https://")],
    var.auth_extra_domains,
  )

  # Identity Platform fills these in with its defaults (email sign-in off, no tenants).
  lifecycle {
    ignore_changes = [multi_tenant, sign_in[0].email]
  }

  depends_on = [google_project_service.apis, google_firebase_project.main]
}
