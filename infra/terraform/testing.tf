# Automated testing from GitHub (keyless, as the found-with-android-ci account):
#  - the live multi-user test (tests/live) signs in as Identity Platform's test numbers, whose
#    codes it reads from Secret Manager;
#  - Firebase Test Lab runs the app on real phones and emulators, with results in their own bucket.

resource "google_project_service" "testing" {
  for_each           = local.android_ci ? toset(["testing.googleapis.com", "toolresults.googleapis.com"]) : toset([])
  service            = each.value
  disable_on_destroy = false
}

resource "google_secret_manager_secret" "test_accounts" {
  count     = local.android_ci ? 1 : 0
  secret_id = "found-with-test-accounts"
  labels    = local.labels
  replication {
    auto {}
  }
  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "test_accounts" {
  count  = local.android_ci ? 1 : 0
  secret = google_secret_manager_secret.test_accounts[0].id
  secret_data = jsonencode({
    api_url      = google_cloud_run_v2_service.api.uri
    web_api_key  = data.google_firebase_web_app_config.web.api_key
    test_numbers = var.test_phone_numbers
  })
}

resource "google_secret_manager_secret_iam_member" "ci_reads_test_accounts" {
  count     = local.android_ci ? 1 : 0
  secret_id = google_secret_manager_secret.test_accounts[0].secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.android_ci[0].email}"
}

resource "google_storage_bucket" "test_lab" {
  count                       = local.android_ci ? 1 : 0
  name                        = "${var.project_id}-test-lab"
  location                    = "US"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
  force_destroy               = true
  labels                      = local.labels
  lifecycle_rule {
    condition {
      age = 30
    }
    action {
      type = "Delete"
    }
  }
}

resource "google_storage_bucket_iam_member" "ci_writes_test_results" {
  count  = local.android_ci ? 1 : 0
  bucket = google_storage_bucket.test_lab[0].name
  role   = "roles/storage.admin"
  member = "serviceAccount:${google_service_account.android_ci[0].email}"
}

resource "google_project_iam_member" "ci_runs_test_lab" {
  for_each = local.android_ci ? toset([
    "roles/cloudtestservice.testAdmin",
    "roles/firebase.analyticsViewer",
    "roles/serviceusage.serviceUsageConsumer",
  ]) : toset([])
  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.android_ci[0].email}"
}
