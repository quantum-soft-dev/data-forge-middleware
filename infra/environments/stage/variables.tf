variable "project_id" {
  type    = string
  default = "bitbi-stage"
}

variable "region" {
  type    = string
  default = "us-central1"
}

variable "environment" {
  type    = string
  default = "stage"
}

variable "cloud_sql_instance" {
  type    = string
  default = "stage-bitbi-db"
}

variable "db_password" {
  type      = string
  sensitive = true
}
