output "app_service_account_email" {
  description = "GCP service account used by forge pods via Workload Identity"
  value       = google_service_account.app.email
}

output "artifact_registry_repo" {
  description = "Artifact Registry Docker repo path"
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.forge.repository_id}"
}

output "uploads_bucket" {
  description = "GCS uploads bucket name"
  value       = google_storage_bucket.uploads.name
}

output "gcs_hmac_access_id" {
  description = "GCS HMAC access id (use as AWS_ACCESS_KEY_ID for the S3-compatible client)"
  value       = google_storage_hmac_key.app.access_id
  sensitive   = true
}

output "gcs_hmac_secret" {
  description = "GCS HMAC secret (use as AWS_SECRET_ACCESS_KEY)"
  value       = google_storage_hmac_key.app.secret
  sensitive   = true
}

output "redis_host" {
  description = "Memorystore Redis host (empty for dev / in-cluster)"
  value       = var.create_redis ? google_redis_instance.cache[0].host : ""
}
