terraform {
  required_version = ">= 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

module "forge" {
  source = "../../modules/forge-app"

  project_id         = var.project_id
  region             = var.region
  environment        = var.environment
  cloud_sql_instance = var.cloud_sql_instance
  db_password        = var.db_password

  # dev uses in-cluster Redis (no Memorystore)
  create_redis = false
}

output "app_service_account_email" {
  value = module.forge.app_service_account_email
}

output "artifact_registry_repo" {
  value = module.forge.artifact_registry_repo
}

output "uploads_bucket" {
  value = module.forge.uploads_bucket
}

output "gcs_hmac_access_id" {
  value     = module.forge.gcs_hmac_access_id
  sensitive = true
}

output "gcs_hmac_secret" {
  value     = module.forge.gcs_hmac_secret
  sensitive = true
}
