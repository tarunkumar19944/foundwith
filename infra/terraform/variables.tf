variable "project_id" {
  description = "GCP project that hosts Found With."
  type        = string
}

variable "region" {
  description = "Region for every regional resource. Mumbai keeps data in India and close to users."
  type        = string
  default     = "asia-south1"
}

variable "api_image" {
  description = "Image the API and jobs start with. Keep the placeholder for the first apply; Cloud Build deploys real images after that (Terraform ignores later image changes)."
  type        = string
  default     = "us-docker.pkg.dev/cloudrun/container/hello"
}

variable "api_min_instances" {
  description = "Instances kept warm. Keep at least 1: a phone rings for about 30 seconds and the incoming-call panel can't wait several seconds for a cold start. 0 is only for a test project nobody calls."
  type        = number
  default     = 1
}

variable "api_max_instances" {
  type    = number
  default = 10
}

variable "db_tier" {
  description = "Cloud SQL machine type. db-custom-1-3840 is 1 vCPU / 3.75 GB."
  type        = string
  default     = "db-custom-1-3840"
}

variable "db_high_availability" {
  description = "Regional (two-zone) Cloud SQL. Doubles the database cost; turn on before a public launch."
  type        = bool
  default     = false
}

variable "db_deletion_protection" {
  type    = bool
  default = true
}

variable "search_daily_limit" {
  type    = number
  default = 50
}

variable "call_daily_limit" {
  type    = number
  default = 100
}

variable "holder_visibility" {
  description = "Who is named on a card: only people the viewer knows (\"known\": their number is in the viewer's contacts or call log), with everyone else shown as a count, or \"everyone\" who has the number saved. Going from known to everyone needs a new consent version."
  type        = string
  default     = "known"

  validation {
    condition     = contains(["everyone", "known"], var.holder_visibility)
    error_message = "holder_visibility must be \"everyone\" or \"known\"."
  }
}

variable "inactive_days" {
  description = "Accounts not seen for this many days are deleted by the nightly job (at least 30)."
  type        = number
  default     = 90
}

variable "db_pool_size" {
  description = "Database connections per API instance."
  type        = number
  default     = 6
}

variable "db_max_connections" {
  description = "max_connections of the Cloud SQL tier (100 for 3.75 GB, 50 for db-g1-small). Used to check the connection budget below."
  type        = number
  default     = 100
}

variable "consent_version" {
  description = "Version of the contact-sync consent text. Bump it when the text changes; users must accept again."
  type        = string
  default     = "2026-11-16"
}

variable "push_mode" {
  description = "\"fcm\" sends the evening note, wake-ups and silent refreshes through Firebase Cloud Messaging; \"off\" sends nothing."
  type        = string
  default     = "fcm"
  validation {
    condition     = contains(["off", "fcm"], var.push_mode)
    error_message = "push_mode must be \"off\" or \"fcm\"."
  }
}

variable "firebase_web_api_key" {
  description = "Overrides the Web API key Terraform reads from the Firebase web app (firebase.tf), for the opt-out page's SMS sign-in. Leave empty."
  type        = string
  default     = ""
}

variable "min_app_version" {
  description = "Android versionCode below which the app is asked to update. 0 asks nobody."
  type        = number
  default     = 0
}

variable "popular_name_min_account_days" {
  description = "How old an account must be before the names it saved count towards popular names."
  type        = number
  default     = 7
}

variable "cors_origins" {
  description = "Web origins allowed to call the API from a browser, e.g. https://foundwith.app"
  type        = list(string)
  default     = []
}

variable "auth_extra_domains" {
  description = "Extra domains allowed for web phone sign-in (your web app's domain)."
  type        = list(string)
  default     = []
}

variable "test_phone_numbers" {
  description = "Identity Platform test numbers and their fixed codes, e.g. { \"+919000000001\" = \"123456\" }. No SMS is sent for these, and anyone who knows the code can sign in as that number, so set this to {} before real users arrive."
  type        = map(string)
  default     = {}
}

variable "alert_email" {
  description = "Where alerts go. Leave empty to create alerts without notifications."
  type        = string
  default     = ""
}

variable "android_sha1" {
  description = "SHA-1 fingerprints of the keys the Android app is signed with (debug key, Play's app-signing key)."
  type        = list(string)
  default     = []
}

variable "android_sha256" {
  description = "SHA-256 fingerprints of the same keys."
  type        = list(string)
  default     = []
}

variable "publisher_name" {
  description = "Who runs WhoHas (the Google Play account holder): named in the privacy policy at /privacy."
  type        = string
  default     = ""
}

variable "contact_email" {
  description = "Public contact address: the privacy policy at /privacy and the Play listing."
  type        = string
  default     = ""
}

variable "public_site_id" {
  description = "Firebase Hosting site for the public address: invite links, /privacy, /delete (whohas.web.app is taken)."
  type        = string
  default     = "whohas-app"
}

variable "android_package" {
  description = "The Android app ID (applicationId): the Play Store ID, permanent once uploaded; must match android/app/build.gradle.kts."
  type        = string
  default     = "app.whohas"
}
