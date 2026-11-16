output "api_url" {
  description = "Base URL of the API."
  value       = google_cloud_run_v2_service.api.uri
}

output "image_repository" {
  description = "Push images here (Cloud Build does this for you)."
  value       = local.image_repo
}

output "deployer_service_account" {
  description = "Use this service account for the Cloud Build trigger."
  value       = google_service_account.deployer.email
}

output "db_connection_name" {
  value = google_sql_database_instance.main.connection_name
}

output "region" {
  value = var.region
}
