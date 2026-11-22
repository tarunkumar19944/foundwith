# The Android build on GitHub gets the app's Firebase config and the debug signing key from
# Secret Manager, keylessly (the same GitHub sign-in exchange as cicd.tf), so nothing has to be
# stored in GitHub. Only pushes to main of var.github_repository can read them.

variable "android_debug_keystore_base64" {
  description = "base64 of the debug.keystore the GitHub APKs are signed with (its fingerprints are android_sha1/android_sha256)."
  type        = string
  default     = ""
  sensitive   = true
}

variable "android_upload_keystore_base64" {
  description = "base64 of the upload key (PKCS12, alias \"upload\") the Play release bundles are signed with. Google Play re-signs them with its own app signing key."
  type        = string
  default     = ""
  sensitive   = true
}

variable "android_upload_keystore_password" {
  description = "Password of the upload key (the store and the key share it)."
  type        = string
  default     = ""
  sensitive   = true
}

locals {
  android_ci = var.github_repository != ""
  # Whether a key was given is not itself secret; the key is.
  has_debug_keystore  = nonsensitive(var.android_debug_keystore_base64 != "")
  has_upload_keystore = nonsensitive(var.android_upload_keystore_base64 != "")
}

resource "google_service_account" "android_ci" {
  count        = local.android_ci ? 1 : 0
  account_id   = "found-with-android-ci"
  display_name = "Found With Android build on GitHub (reads the app's config and debug key)"
}

resource "google_secret_manager_secret" "android_google_services" {
  count     = local.android_ci ? 1 : 0
  secret_id = "found-with-android-google-services"
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "android_google_services" {
  count       = local.android_ci ? 1 : 0
  secret      = google_secret_manager_secret.android_google_services[0].id
  secret_data = base64decode(data.google_firebase_android_app_config.app.config_file_contents)
}

resource "google_secret_manager_secret" "android_debug_keystore" {
  count     = local.android_ci && local.has_debug_keystore ? 1 : 0
  secret_id = "found-with-android-debug-keystore"
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "android_debug_keystore" {
  count       = length(google_secret_manager_secret.android_debug_keystore)
  secret      = google_secret_manager_secret.android_debug_keystore[0].id
  secret_data = var.android_debug_keystore_base64
}

# The Play upload key, for .github/workflows/release.yml. Keep a copy outside GCP too: if it is lost,
# Play Console can reset the upload key, but only through a support request.
resource "google_secret_manager_secret" "android_upload_keystore" {
  for_each  = local.android_ci && local.has_upload_keystore ? toset(["keystore", "password"]) : toset([])
  secret_id = "found-with-android-upload-${each.key}"
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "android_upload_keystore" {
  for_each    = google_secret_manager_secret.android_upload_keystore
  secret      = each.value.id
  secret_data = each.key == "keystore" ? var.android_upload_keystore_base64 : var.android_upload_keystore_password
}

resource "google_secret_manager_secret_iam_member" "android_ci_reads" {
  for_each = local.android_ci ? merge(
    { google_services = google_secret_manager_secret.android_google_services[0].secret_id },
    local.has_debug_keystore ? { keystore = google_secret_manager_secret.android_debug_keystore[0].secret_id } : {},
    { for k, v in google_secret_manager_secret.android_upload_keystore : "upload_${k}" => v.secret_id },
  ) : {}
  secret_id = each.value
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.android_ci[0].email}"
}

resource "google_service_account_iam_member" "github_is_android_ci" {
  count              = local.android_ci ? 1 : 0
  service_account_id = google_service_account.android_ci[0].name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github_repository}"
}

output "android_ci_service_account" {
  value = local.android_ci ? google_service_account.android_ci[0].email : null
}
