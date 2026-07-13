# Remote state shared with bitbi — one bucket, forge gets its own prefix.
# The bucket already exists (created during the bitbi bootstrap):
#   gs://bitbi-terraform-state (project bitbi-production, us-central1, versioned)
#
# Init from this directory:
#   terraform init

terraform {
  backend "gcs" {
    bucket = "bitbi-terraform-state"
    prefix = "forge/environments/dev"
  }
}
