# Firebase on the same project, so nothing has to be clicked in the Firebase console:
#  - the Android app, whose google-services.json turns on SMS sign-in and push in the app
#    (terraform output -raw google_services_json > google-services.json);
#  - a web app, whose key the opt-out page (/out) uses for its SMS sign-in.

resource "google_project_service" "firebase" {
  for_each           = toset(["firebase.googleapis.com", "firebaseinstallations.googleapis.com"])
  service            = each.value
  disable_on_destroy = false
}

resource "google_firebase_project" "main" {
  provider   = google-beta
  project    = var.project_id
  depends_on = [google_project_service.firebase, google_project_service.apis]
}

resource "google_firebase_android_app" "app" {
  provider     = google-beta
  project      = var.project_id
  display_name = "Found With"
  package_name = "app.foundwith"
  # Fingerprints of the keys the APKs are signed with: SMS sign-in checks them. Add Play's
  # app-signing key here before the Play release.
  sha1_hashes     = var.android_sha1
  sha256_hashes   = var.android_sha256
  deletion_policy = "DELETE"
  depends_on      = [google_firebase_project.main]
}

data "google_firebase_android_app_config" "app" {
  provider = google-beta
  project  = var.project_id
  app_id   = google_firebase_android_app.app.app_id
}

resource "google_firebase_web_app" "web" {
  provider        = google-beta
  project         = var.project_id
  display_name    = "Found With web"
  deletion_policy = "DELETE"
  depends_on      = [google_firebase_project.main]
}

data "google_firebase_web_app_config" "web" {
  provider   = google-beta
  project    = var.project_id
  web_app_id = google_firebase_web_app.web.app_id
}
