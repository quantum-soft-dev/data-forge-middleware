terraform {
  backend "gcs" {
    bucket = "bitbi-terraform-state"
    prefix = "forge/environments/stage"
  }
}
