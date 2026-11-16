# Alerts for the things users would notice: the API down, errors, slow responses, a busy database.

resource "google_monitoring_notification_channel" "email" {
  count        = var.alert_email == "" ? 0 : 1
  display_name = "Found With alerts"
  type         = "email"
  labels = {
    email_address = var.alert_email
  }
}

locals {
  alert_channels = google_monitoring_notification_channel.email[*].id
  api_filter     = "resource.type = \"cloud_run_revision\" AND resource.labels.service_name = \"${google_cloud_run_v2_service.api.name}\""
}

resource "google_monitoring_uptime_check_config" "api" {
  display_name = "Found With API ready"
  timeout      = "10s"
  period       = "300s"

  http_check {
    path         = "/readyz"
    port         = 443
    use_ssl      = true
    validate_ssl = true
  }

  monitored_resource {
    type = "uptime_url"
    labels = {
      project_id = var.project_id
      host       = trimprefix(google_cloud_run_v2_service.api.uri, "https://")
    }
  }
}

resource "google_monitoring_alert_policy" "api_down" {
  display_name          = "Found With API is down"
  combiner              = "OR"
  notification_channels = local.alert_channels

  conditions {
    display_name = "Readiness check failing from several locations"
    condition_threshold {
      filter          = "metric.type = \"monitoring.googleapis.com/uptime_check/check_passed\" AND resource.type = \"uptime_url\" AND metric.label.check_id = \"${google_monitoring_uptime_check_config.api.uptime_check_id}\""
      comparison      = "COMPARISON_GT"
      threshold_value = 1
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_NEXT_OLDER"
        cross_series_reducer = "REDUCE_COUNT_FALSE"
        group_by_fields      = ["resource.label.*"]
      }
    }
  }
}

resource "google_monitoring_alert_policy" "api_errors" {
  display_name          = "Found With API server errors"
  combiner              = "OR"
  notification_channels = local.alert_channels

  conditions {
    display_name = "More than 5 server errors a minute for 5 minutes"
    condition_threshold {
      filter          = "${local.api_filter} AND metric.type = \"run.googleapis.com/request_count\" AND metric.labels.response_code_class = \"5xx\""
      comparison      = "COMPARISON_GT"
      threshold_value = 5
      duration        = "300s"

      aggregations {
        alignment_period     = "60s"
        per_series_aligner   = "ALIGN_SUM"
        cross_series_reducer = "REDUCE_SUM"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "api_latency" {
  display_name          = "Found With API is slow"
  combiner              = "OR"
  notification_channels = local.alert_channels

  conditions {
    display_name = "95th percentile latency above 800 ms for 10 minutes"
    condition_threshold {
      filter          = "${local.api_filter} AND metric.type = \"run.googleapis.com/request_latencies\""
      comparison      = "COMPARISON_GT"
      threshold_value = 800
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
}

resource "google_monitoring_alert_policy" "db_cpu" {
  display_name          = "Found With database CPU high"
  combiner              = "OR"
  notification_channels = local.alert_channels

  conditions {
    display_name = "Cloud SQL CPU above 80% for 15 minutes"
    condition_threshold {
      filter          = "resource.type = \"cloudsql_database\" AND resource.labels.database_id = \"${var.project_id}:${google_sql_database_instance.main.name}\" AND metric.type = \"cloudsql.googleapis.com/database/cpu/utilization\""
      comparison      = "COMPARISON_GT"
      threshold_value = 0.8
      duration        = "900s"

      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_MEAN"
      }
    }
  }
}

# The real-time path: how long the API itself takes to answer a lookup (search or incoming call),
# read from the request log the app writes. Cloud Run's own latency metric mixes in syncs and
# Home loads, which are allowed to be slower; this one is only the call-time path.
resource "google_logging_metric" "lookup_latency" {
  name   = "found_with/lookup_latency"
  filter = "resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${google_cloud_run_v2_service.api.name}\" AND jsonPayload.event=\"request\" AND jsonPayload.route=\"/v1/lookup\""

  metric_descriptor {
    metric_kind  = "DELTA"
    value_type   = "DISTRIBUTION"
    unit         = "ms"
    display_name = "Found With lookup latency"
  }

  value_extractor = "EXTRACT(jsonPayload.latency_ms)"

  bucket_options {
    exponential_buckets {
      num_finite_buckets = 40
      growth_factor      = 1.3
      scale              = 1
    }
  }
}

resource "google_monitoring_alert_policy" "lookup_latency" {
  display_name          = "Found With lookups are slow"
  combiner              = "OR"
  notification_channels = local.alert_channels

  documentation {
    content = "The incoming-call panel has a few seconds to show anything. Check Cloud SQL CPU and connections, and whether instances are cold-starting (api_min_instances)."
  }

  conditions {
    display_name = "95th percentile lookup above 300 ms for 10 minutes"
    condition_threshold {
      filter          = "metric.type = \"logging.googleapis.com/user/${google_logging_metric.lookup_latency.name}\" AND resource.type = \"cloud_run_revision\""
      comparison      = "COMPARISON_GT"
      threshold_value = 300
      duration        = "600s"

      aggregations {
        alignment_period     = "300s"
        per_series_aligner   = "ALIGN_PERCENTILE_95"
        cross_series_reducer = "REDUCE_MAX"
      }
    }
  }
}
