# One service account per job to do, each with only what that job needs.

resource "google_service_account" "api" {
  account_id   = "found-with-api"
  display_name = "Found With API (Cloud Run service)"
}

resource "google_service_account" "jobs" {
  account_id   = "found-with-jobs"
  display_name = "Found With migration and maintenance jobs"
}

resource "google_service_account" "scheduler" {
  account_id   = "found-with-scheduler"
  display_name = "Found With scheduler (starts the maintenance job)"
}

resource "google_service_account" "deployer" {
  account_id   = "found-with-deployer"
  display_name = "Found With deployer (Cloud Build)"
}

locals {
  # firebaseauth.admin lets the app delete a person's sign-in account when they delete their profile.
  api_roles = [
    "roles/cloudsql.client",
    "roles/cloudtrace.agent",
    "roles/firebaseauth.admin",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
  ]
  jobs_roles = [
    "roles/cloudsql.client",
    "roles/firebaseauth.admin",
    "roles/logging.logWriter",
  ]
  deployer_roles = [
    "roles/logging.logWriter",
    "roles/run.developer",
  ]
}

resource "google_project_iam_member" "api" {
  for_each = toset(local.api_roles)
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.api.email}"
}

resource "google_project_iam_member" "jobs" {
  for_each = toset(local.jobs_roles)
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.jobs.email}"
}

resource "google_project_iam_member" "deployer" {
  for_each = toset(local.deployer_roles)
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.deployer.email}"
}

# The deployer pushes images and deploys revisions that run as the api and jobs accounts.
resource "google_artifact_registry_repository_iam_member" "deployer_push" {
  location   = google_artifact_registry_repository.images.location
  repository = google_artifact_registry_repository.images.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_acts_as" {
  for_each           = { api = google_service_account.api.name, jobs = google_service_account.jobs.name }
  service_account_id = each.value
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

# Where `gcloud builds submit` uploads the code for a build started by hand (README, "Deploy").
# Builds from the GitHub trigger don't use it.
resource "google_storage_bucket" "build_source" {
  name                        = "${var.project_id}-build-source"
  location                    = var.region
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

resource "google_storage_bucket_iam_member" "deployer_reads_source" {
  bucket = google_storage_bucket.build_source.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.deployer.email}"
}
