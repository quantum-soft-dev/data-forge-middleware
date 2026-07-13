variable "project_id" {
  type    = string
  default = "bitbi-dev"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "environment" {
  type    = string
  default = "dev"
}

variable "cloud_sql_instance" {
  description = "Existing Cloud SQL instance shared with bitbi"
  type        = string
  default     = "dev-bitbi-db"
}

# Sensitive — set via TF_VAR_db_password (GitHub Actions secret), never in tfvars.
variable "db_password" {
  type      = string
  sensitive = true
}
