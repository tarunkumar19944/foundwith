# Deploys from GitHub Actions without any key: GitHub's own sign-in token is exchanged for the
# deployer service account, and only for pushes to main of this one repository.
# .github/workflows/deploy.yml then runs cloudbuild.yaml whenever backend/ changes.

variable "github_repository" {
  description = "owner/repo allowed to deploy, e.g. tarunkumar19944/foundwith. Empty turns this off."
  type        = string
  default     = ""
}

resource "google_project_service" "keyless" {
  for_each           = var.github_repository == "" ? toset([]) : toset(["iamcredentials.googleapis.com", "sts.googleapis.com"])
  service            = each.value
  disable_on_destroy = false
}

resource "google_iam_workload_identity_pool" "github" {
  count                     = var.github_repository == "" ? 0 : 1
  workload_identity_pool_id = "github"
  display_name              = "GitHub Actions"
  depends_on                = [google_project_service.keyless]
}

resource "google_iam_workload_identity_pool_provider" "github" {
  count                              = var.github_repository == "" ? 0 : 1
  workload_identity_pool_id          = google_iam_workload_identity_pool.github[0].workload_identity_pool_id
  workload_identity_pool_provider_id = "github-actions"
  display_name                       = "GitHub Actions"
  attribute_mapping = {
    "google.subject"       = "assertion.sub"
    "attribute.repository" = "assertion.repository"
    "attribute.ref"        = "assertion.ref"
  }
  attribute_condition = "assertion.repository == '${var.github_repository}' && assertion.ref == 'refs/heads/main'"
  oidc {
    issuer_uri = "https://token.actions.githubusercontent.com"
  }
}

resource "google_service_account_iam_member" "github_is_deployer" {
  count              = var.github_repository == "" ? 0 : 1
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.github[0].name}/attribute.repository/${var.github_repository}"
}

# What starting a build needs: create and read builds, upload the code, and run the build as itself.
resource "google_project_iam_member" "deployer_starts_builds" {
  count   = var.github_repository == "" ? 0 : 1
  project = var.project_id
  role    = "roles/cloudbuild.builds.editor"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

# gcloud builds submit checks the bucket itself before uploading, so objects alone aren't enough.
resource "google_storage_bucket_iam_member" "deployer_uploads_source" {
  count  = var.github_repository == "" ? 0 : 1
  bucket = google_storage_bucket.build_source.name
  role   = "roles/storage.admin"
  member = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_project_iam_member" "deployer_uses_apis" {
  count   = var.github_repository == "" ? 0 : 1
  project = var.project_id
  role    = "roles/serviceusage.serviceUsageConsumer"
  member  = "serviceAccount:${google_service_account.deployer.email}"
}

resource "google_service_account_iam_member" "deployer_acts_as_itself" {
  count              = var.github_repository == "" ? 0 : 1
  service_account_id = google_service_account.deployer.name
  role               = "roles/iam.serviceAccountUser"
  member             = "serviceAccount:${google_service_account.deployer.email}"
}

output "github_workload_identity_provider" {
  description = "For .github/workflows/deploy.yml"
  value       = var.github_repository == "" ? null : google_iam_workload_identity_pool_provider.github[0].name
}
