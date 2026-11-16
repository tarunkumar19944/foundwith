# Secrets are generated here and stored in Secret Manager in the deployment region.
# They also live in Terraform state, so keep state in a locked-down GCS bucket (see backend.tf.example).

resource "random_password" "db" {
  length  = 32
  special = false
}

# The pepper keys the hash of every stored phone number. Changing it breaks all matching,
# so it is generated once and protected from replacement.
resource "random_password" "pepper" {
  length  = 64
  special = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "found-with-db-password"
  labels    = local.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "db_password" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = random_password.db.result
}

resource "google_secret_manager_secret" "pepper" {
  secret_id = "found-with-number-pepper"
  labels    = local.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [google_project_service.apis]
}

# The key contact names are encrypted and counted with. Like the pepper: generated once, never rotated in place.
resource "random_password" "name_key" {
  length  = 64
  special = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_secret_manager_secret" "name_key" {
  secret_id = "found-with-name-key"
  labels    = local.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [google_project_service.apis]
}

resource "google_secret_manager_secret_version" "name_key" {
  secret      = google_secret_manager_secret.name_key.id
  secret_data = random_password.name_key.result

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [secret_data]
  }
}

resource "google_secret_manager_secret_iam_member" "api_name_key" {
  secret_id = google_secret_manager_secret.name_key.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_secret_manager_secret_version" "pepper" {
  secret      = google_secret_manager_secret.pepper.id
  secret_data = random_password.pepper.result

  lifecycle {
    prevent_destroy = true
    ignore_changes  = [secret_data]
  }
}

# Only the API reads the pepper; both the API and the jobs read the database password.
resource "google_secret_manager_secret_iam_member" "api_pepper" {
  secret_id = google_secret_manager_secret.pepper.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.api.email}"
}

resource "google_secret_manager_secret_iam_member" "db_password" {
  for_each  = { api = google_service_account.api.email, jobs = google_service_account.jobs.email }
  secret_id = google_secret_manager_secret.db_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${each.value}"
}
