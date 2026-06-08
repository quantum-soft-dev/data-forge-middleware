variable "project_id" {
  type    = string
  default = "bitbi-production"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "cloud_sql_instance" {
  type    = string
  default = "prod-bitbi-db"
}

variable "db_password" {
  type      = string
  sensitive = true
}
