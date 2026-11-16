# Cloud SQL for PostgreSQL 16. It has a public IP but no authorised networks, so the only way in is
# the Cloud SQL connector that Cloud Run mounts as a Unix socket (IAM-checked, encrypted).

resource "google_sql_database_instance" "main" {
  name                = "found-with-pg"
  database_version    = "POSTGRES_16"
  region              = var.region
  deletion_protection = var.db_deletion_protection

  settings {
    edition                     = "ENTERPRISE"
    tier                        = var.db_tier
    availability_type           = var.db_high_availability ? "REGIONAL" : "ZONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = 10
    disk_autoresize             = true
    deletion_protection_enabled = var.db_deletion_protection
    user_labels                 = local.labels

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "20:30" # UTC, which is 02:00 IST
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 14
      }
    }

    ip_configuration {
      ipv4_enabled = true
      ssl_mode     = "ENCRYPTED_ONLY"
    }

    maintenance_window {
      day          = 7  # Sunday
      hour         = 21 # UTC, which is Monday 02:30 IST
      update_track = "stable"
    }

    insights_config {
      query_insights_enabled  = true
      record_application_tags = false
      record_client_address   = false
    }
  }

  depends_on = [google_project_service.apis]
}

resource "google_sql_database" "app" {
  name     = "foundwith"
  instance = google_sql_database_instance.main.name
}

resource "google_sql_user" "app" {
  name     = "foundwith_app"
  instance = google_sql_database_instance.main.name
  password = random_password.db.result
}
