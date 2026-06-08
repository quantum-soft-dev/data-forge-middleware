variable "project_id" {
  description = "GCP project ID (e.g. bitbi-dev / bitbi-stage / bitbi-production)"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "us-central1"
}

variable "environment" {
  description = "Environment name (dev / stage / prod)"
  type        = string
}

# ── Cloud SQL (database + user on an EXISTING shared instance) ─────────────────

variable "cloud_sql_instance" {
  description = "Name of the existing Cloud SQL instance to create the forge DB/user on (e.g. dev-bitbi-db)"
  type        = string
}

variable "db_name" {
  description = "forge database name"
  type        = string
  default     = "dfm"
}

variable "db_user" {
  description = "forge database user"
  type        = string
  default     = "dfm-app"
}

variable "db_password" {
  description = "Password for the forge DB user (set via TF_VAR_db_password, never in tfvars)"
  type        = string
  sensitive   = true
}

# ── Object storage (GCS bucket + HMAC for S3-compatible access) ────────────────

variable "bucket_location" {
  description = "GCS bucket location"
  type        = string
  default     = "US"
}

# ── Redis (Memorystore — stage/prod only; dev uses in-cluster Redis) ───────────

variable "create_redis" {
  description = "Create a Memorystore Redis instance (false for dev)"
  type        = bool
  default     = false
}

variable "redis_tier" {
  description = "Memorystore tier (BASIC / STANDARD_HA)"
  type        = string
  default     = "BASIC"
}

variable "redis_memory_gb" {
  description = "Memorystore memory size in GB"
  type        = number
  default     = 1
}

variable "redis_authorized_network" {
  description = "Self link / id of the VPC the Memorystore instance attaches to (stage/prod)"
  type        = string
  default     = ""
}
