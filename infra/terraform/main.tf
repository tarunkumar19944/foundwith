locals {
  apis = [
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "cloudscheduler.googleapis.com",
    "fcm.googleapis.com",
    "iam.googleapis.com",
    "identitytoolkit.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com",
    "sqladmin.googleapis.com",
  ]

  image_repo = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
  db_socket  = "/cloudsql/${google_sql_database_instance.main.connection_name}"

  # Settings shared by the API and the jobs (see backend/foundwith/config.py)
  common_env = {
    APP_ENV             = "production"
    AUTH_MODE           = "firebase"
    FIREBASE_PROJECT_ID = var.project_id
    DB_HOST             = local.db_socket
    DB_NAME             = google_sql_database.app.name
    DB_USER             = google_sql_user.app.name
    INACTIVE_DAYS       = tostring(var.inactive_days)
    # Popular names count accounts at least this old; 0 for a first test group whose accounts are all new.
    POPULAR_NAME_MIN_ACCOUNT_DAYS = tostring(var.popular_name_min_account_days)
  }

  api_env = merge(local.common_env, {
    SEARCH_DAILY_LIMIT = tostring(var.search_daily_limit)
    CALL_DAILY_LIMIT   = tostring(var.call_daily_limit)
    CONSENT_VERSION    = var.consent_version
    CORS_ORIGINS       = join(",", var.cors_origins)
    HOLDER_VISIBILITY  = var.holder_visibility
    DB_POOL_SIZE       = tostring(var.db_pool_size)
    MIN_APP_VERSION    = tostring(var.min_app_version)
    # The test numbers (not their codes): the automated tests' accounts skip the daily lookup limits.
    QUOTA_EXEMPT_NUMBERS = join(",", sort(keys(var.test_phone_numbers)))
    PUSH_MODE            = var.push_mode
    PUSH_CALLER          = google_service_account.scheduler.email
    PUSH_AUDIENCE        = local.push_audience
    # The opt-out web page (/out) signs people in with Identity Platform's web SDK.
    FIREBASE_WEB_API_KEY = var.firebase_web_api_key != "" ? var.firebase_web_api_key : data.google_firebase_web_app_config.web.api_key
    FIREBASE_AUTH_DOMAIN = "${var.project_id}.firebaseapp.com"
    PUBLISHER_NAME       = var.publisher_name
    CONTACT_EMAIL        = var.contact_email
    PUBLIC_URL           = "https://${var.public_site_id}.web.app"
  })

  # The audience Cloud Scheduler puts in its token for /internal/push*; the API checks it.
  push_audience = "found-with-push"

  # Every API instance can open db_pool_size connections; the two jobs use up to 3 more, and
  # Cloud SQL keeps a few for itself. Going over max_connections makes requests fail at peak.
  db_connections_needed = var.api_max_instances * var.db_pool_size + 3 + 5

  labels = { app = "found-with" }
}

resource "google_project_service" "apis" {
  for_each           = toset(local.apis)
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "found-with"
  format        = "DOCKER"
  description   = "Found With container images"
  labels        = local.labels

  cleanup_policies {
    id     = "keep-recent-releases"
    action = "KEEP"
    most_recent_versions {
      keep_count = 20
    }
  }

  cleanup_policies {
    id     = "delete-old-images"
    action = "DELETE"
    condition {
      older_than = "2592000s" # 30 days
    }
  }

  depends_on = [google_project_service.apis]
}
