# forge-app: lean per-environment infra for the data-forge-middleware service.
# Reuses bitbi's existing GKE cluster, VPC and Cloud SQL instance — this module only
# provisions the forge-specific resources: Artifact Registry repo, Workload-Identity
# service account, Cloud SQL database/user, and a GCS bucket + HMAC keys.

terraform {
  required_version = ">= 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

# ── Artifact Registry ─────────────────────────────────────────────────────────

resource "google_artifact_registry_repository" "forge" {
  project       = var.project_id
  location      = var.region
  repository_id = "forge-app"
  format        = "DOCKER"
  description   = "Forge (data-forge-middleware) Docker images (${var.environment})"
}

# ── Workload Identity service account ─────────────────────────────────────────

resource "google_service_account" "app" {
  project      = var.project_id
  account_id   = "${var.environment}-forge-app"
  display_name = "Forge App (${var.environment})"
}

resource "google_project_iam_member" "sql_client" {
  project = var.project_id
  role    = "roles/cloudsql.client"
  member  = "serviceAccount:${google_service_account.app.email}"
}

resource "google_project_iam_member" "secret_accessor" {
  project = var.project_id
  role    = "roles/secretmanager.secretAccessor"
  member  = "serviceAccount:${google_service_account.app.email}"
}

# Bind the K8s SA forge/forge-app to this GCP SA.
resource "google_service_account_iam_member" "workload_identity" {
  service_account_id = google_service_account.app.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "serviceAccount:${var.project_id}.svc.id.goog[forge/forge-app]"
}

# ── Cloud SQL database + user on the existing shared instance ──────────────────

resource "google_sql_database" "dfm" {
  project  = var.project_id
  name     = var.db_name
  instance = var.cloud_sql_instance
}

resource "google_sql_user" "dfm_app" {
  project  = var.project_id
  name     = var.db_user
  instance = var.cloud_sql_instance
  password = var.db_password
}

# ── Object storage: GCS bucket + HMAC keys (S3-compatible access) ──────────────

resource "google_storage_bucket" "uploads" {
  project                     = var.project_id
  name                        = "dfm-${var.environment}-uploads"
  location                    = var.bucket_location
  uniform_bucket_level_access = true
  force_destroy               = false

  versioning {
    enabled = false
  }
}

resource "google_storage_bucket_iam_member" "app_object_admin" {
  bucket = google_storage_bucket.uploads.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.app.email}"
}

# HMAC keys authenticate as the app SA against the S3-compatible XML API.
# Disabled where org policy iam.disableServiceAccountKeyCreation blocks SA keys.
resource "google_storage_hmac_key" "app" {
  count                 = var.create_hmac ? 1 : 0
  project               = var.project_id
  service_account_email = google_service_account.app.email
}
