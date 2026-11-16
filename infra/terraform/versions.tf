terraform {
  required_version = ">= 1.6"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.40"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region

  # Identity Platform rejects calls made with your own gcloud login unless a billing (quota)
  # project is sent with them. These two lines send this project.
  billing_project       = var.project_id
  user_project_override = true
}
