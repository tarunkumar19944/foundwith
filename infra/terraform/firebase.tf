# Firebase on the same project, so nothing has to be clicked in the Firebase console:
#  - the Android app, whose google-services.json turns on SMS sign-in and push in the app
#    (terraform output -raw google_services_json > google-services.json);
#  - a web app, whose key the opt-out page (/out) uses for its SMS sign-in.

resource "google_project_service" "firebase" {
  for_each           = toset(["firebase.googleapis.com", "firebaseinstallations.googleapis.com", "firebasehosting.googleapis.com"])
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
  display_name = "WhoHas"
  package_name = var.android_package
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

# The app's API key ("Android key (auto created by Firebase)") must have no application
# restriction, only API limits, or that reCAPTCHA page can't use it. Firebase creates it with an
# empty Android restriction; clear it once after the first apply:
#   ARGS=$(gcloud services api-keys describe KEY_UID --format=json | python3 -c "import json,sys; \
#     print(' '.join('--api-target=service='+t['service'] for t in json.load(sys.stdin)['restrictions']['apiTargets']))")
#   gcloud services api-keys update KEY_UID --clear-restrictions && gcloud services api-keys update KEY_UID $ARGS
#
# The default Hosting site serves foundwith.firebaseapp.com/__/auth/handler: the page SMS sign-in
# falls back to (a reCAPTCHA check) when Google Play can't vouch for the app, as with test builds
# installed from a link. Enabling the Hosting API creates it (terraform import it if so). Its home
# page is web/hosting/index.html.
# The public address people see: https://whohas-app.web.app. Every path goes to the API service,
# which serves the home page (/), invite links (/i/CODE), /privacy, /delete and /out.
resource "google_firebase_hosting_site" "public" {
  provider   = google-beta
  project    = var.project_id
  site_id    = var.public_site_id
  depends_on = [google_firebase_project.main, google_project_service.firebase]
}

resource "google_firebase_hosting_version" "public" {
  provider = google-beta
  site_id  = google_firebase_hosting_site.public.site_id
  config {
    rewrites {
      glob = "**"
      run {
        service_id = google_cloud_run_v2_service.api.name
        region     = var.region
      }
    }
  }
}

resource "google_firebase_hosting_release" "public" {
  provider     = google-beta
  site_id      = google_firebase_hosting_site.public.site_id
  version_name = google_firebase_hosting_version.public.name
  message      = "Every path to the WhoHas API"
}

resource "google_firebase_hosting_site" "default" {
  provider   = google-beta
  project    = var.project_id
  site_id    = var.project_id
  depends_on = [google_firebase_project.main, google_project_service.firebase]
}
