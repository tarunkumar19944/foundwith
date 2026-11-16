# The API (Cloud Run service) plus two Cloud Run jobs from the same image:
#   found-with-migrate      runs SQL migrations; Cloud Build executes it before every deploy
#   found-with-maintenance  nightly: deletes inactive accounts, retries failed sign-in deletions,
#                           purges old records and checks the stored holder counts. Moderation
#                           commands run as one-off executions of the same job with other args:
#                             gcloud run jobs execute found-with-maintenance --region=asia-south1 \
#                               --wait --args=-m,foundwith.admin,reports

resource "google_cloud_run_v2_service" "api" {
  name                = "found-with-api"
  location            = var.region
  ingress             = "INGRESS_TRAFFIC_ALL"
  deletion_protection = false
  labels              = local.labels

  template {
    service_account                  = google_service_account.api.email
    timeout                          = "30s"
    max_instance_request_concurrency = 80

    scaling {
      min_instance_count = var.api_min_instances
      max_instance_count = var.api_max_instances
    }

    volumes {
      name = "cloudsql"
      cloud_sql_instance {
        instances = [google_sql_database_instance.main.connection_name]
      }
    }

    containers {
      image = var.api_image

      ports {
        container_port = 8080
      }

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
        cpu_idle          = true
        startup_cpu_boost = true
      }

      dynamic "env" {
        for_each = local.api_env
        content {
          name  = env.key
          value = env.value
        }
      }

      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_password.secret_id
            version = "latest"
          }
        }
      }

      # Pinned to the first version: the pepper must never change under a running service.
      env {
        name = "NUMBER_PEPPER"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.pepper.secret_id
            version = google_secret_manager_secret_version.pepper.version
          }
        }
      }

      # Pinned too: stored names can only be read with the key they were written with.
      env {
        name = "NAME_KEY"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.name_key.secret_id
            version = google_secret_manager_secret_version.name_key.version
          }
        }
      }

      volume_mounts {
        name       = "cloudsql"
        mount_path = "/cloudsql"
      }

      startup_probe {
        http_get {
          path = "/healthz"
        }
        period_seconds    = 3
        timeout_seconds   = 2
        failure_threshold = 10
      }

      liveness_probe {
        http_get {
          path = "/healthz"
        }
        period_seconds  = 30
        timeout_seconds = 3
      }
    }
  }

  lifecycle {
    # Cloud Build deploys new images; Terraform owns everything else.
    ignore_changes = [template[0].containers[0].image, client, client_version]

    precondition {
      condition     = local.db_connections_needed <= var.db_max_connections
      error_message = "api_max_instances x db_pool_size (+ jobs) is more than the database's max_connections. Lower one of them or use a bigger database tier."
    }
  }

  depends_on = [
    google_project_iam_member.api,
    google_secret_manager_secret_iam_member.api_pepper,
    google_secret_manager_secret_iam_member.api_name_key,
    google_secret_manager_secret_iam_member.db_password,
  ]
}

# The app checks every request's ID token itself, so the service accepts unauthenticated HTTP.
# If an organisation policy blocks "allUsers", ask an org admin for an exception for this service.
resource "google_cloud_run_v2_service_iam_member" "public" {
  name     = google_cloud_run_v2_service.api.name
  location = google_cloud_run_v2_service.api.location
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_job" "migrate" {
  name                = "found-with-migrate"
  location            = var.region
  deletion_protection = false
  labels              = local.labels

  template {
    task_count = 1

    template {
      service_account = google_service_account.jobs.email
      timeout         = "600s"
      max_retries     = 1

      volumes {
        name = "cloudsql"
        cloud_sql_instance {
          instances = [google_sql_database_instance.main.connection_name]
        }
      }

      containers {
        image   = var.api_image
        command = ["python", "-m", "foundwith.migrate"]

        dynamic "env" {
          for_each = local.common_env
          content {
            name  = env.key
            value = env.value
          }
        }

        env {
          name = "DB_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.db_password.secret_id
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "512Mi"
          }
        }

        volume_mounts {
          name       = "cloudsql"
          mount_path = "/cloudsql"
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].template[0].containers[0].image, client, client_version]
  }

  depends_on = [google_project_iam_member.jobs, google_secret_manager_secret_iam_member.db_password]
}

resource "google_cloud_run_v2_job" "maintenance" {
  name                = "found-with-maintenance"
  location            = var.region
  deletion_protection = false
  labels              = local.labels

  template {
    task_count = 1

    template {
      service_account = google_service_account.jobs.email
      timeout         = "300s"
      max_retries     = 1

      volumes {
        name = "cloudsql"
        cloud_sql_instance {
          instances = [google_sql_database_instance.main.connection_name]
        }
      }

      containers {
        image = var.api_image
        # Split into command and args so a one-off execution can swap the args for an admin command.
        command = ["python"]
        args    = ["-m", "foundwith.maintenance"]

        dynamic "env" {
          for_each = local.common_env
          content {
            name  = env.key
            value = env.value
          }
        }

        env {
          name = "DB_PASSWORD"
          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.db_password.secret_id
              version = "latest"
            }
          }
        }

        resources {
          limits = {
            cpu    = "1"
            memory = "512Mi"
          }
        }

        volume_mounts {
          name       = "cloudsql"
          mount_path = "/cloudsql"
        }
      }
    }
  }

  lifecycle {
    ignore_changes = [template[0].template[0].containers[0].image, client, client_version]
  }

  depends_on = [google_project_iam_member.jobs, google_secret_manager_secret_iam_member.db_password]
}

resource "google_cloud_run_v2_job_iam_member" "scheduler_runs_maintenance" {
  name     = google_cloud_run_v2_job.maintenance.name
  location = google_cloud_run_v2_job.maintenance.location
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.scheduler.email}"
}

resource "google_cloud_scheduler_job" "maintenance" {
  name             = "found-with-maintenance-daily"
  region           = var.region
  description      = "Runs the Found With maintenance job every night"
  schedule         = "17 3 * * *"
  time_zone        = "Asia/Kolkata"
  attempt_deadline = "320s"

  http_target {
    http_method = "POST"
    uri         = "https://run.googleapis.com/v2/projects/${var.project_id}/locations/${var.region}/jobs/${google_cloud_run_v2_job.maintenance.name}:run"

    oauth_token {
      service_account_email = google_service_account.scheduler.email
    }
  }

  depends_on = [google_cloud_run_v2_job_iam_member.scheduler_runs_maintenance]
}

# ---------------------------------------------------------------- push messages (FCM)

# The API sends push messages as itself.
resource "google_project_iam_member" "api_sends_pushes" {
  count   = var.push_mode == "fcm" ? 1 : 0
  project = var.project_id
  role    = "roles/firebasecloudmessaging.admin"
  member  = "serviceAccount:${google_service_account.api.email}"
}

locals {
  push_jobs = var.push_mode == "fcm" ? {
    # Silent refreshes that are due (card changes, someone you know joined).
    "found-with-push-every-minute" = { path = "/internal/push", schedule = "* * * * *", about = "Sends due silent refreshes" }
    # The evening note, counts only.
    "found-with-evening-note" = { path = "/internal/push/evening", schedule = "30 19 * * *", about = "Sends the evening note" }
    # Apps check their call log for calls they didn't get to check (some phones stop apps in the background).
    "found-with-wake-ups" = { path = "/internal/push/wake", schedule = "0 10,14,18 * * *", about = "Wakes apps to catch up on missed calls" }
  } : {}
}

resource "google_cloud_scheduler_job" "push" {
  for_each         = local.push_jobs
  name             = each.key
  region           = var.region
  description      = each.value.about
  schedule         = each.value.schedule
  time_zone        = "Asia/Kolkata"
  attempt_deadline = "300s"

  http_target {
    http_method = "POST"
    uri         = "${google_cloud_run_v2_service.api.uri}${each.value.path}"

    oidc_token {
      service_account_email = google_service_account.scheduler.email
      audience              = local.push_audience
    }
  }
}
