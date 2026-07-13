project_id         = "bitbi-production"
region             = "us-central1"
environment        = "prod"
cloud_sql_instance = "prod-bitbi-db"

# DO NOT put db_password here. Set TF_VAR_db_password in CI.
