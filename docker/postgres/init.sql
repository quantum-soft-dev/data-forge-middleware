-- Initialize databases and users for Data Forge Middleware
-- This script creates the dfm database and its dedicated user.
-- The keycloak database and role were dropped after the Auth0 migration: nothing
-- connected to them, and the role shipped with a hardcoded password.

-- Create DFM database and user
CREATE DATABASE dfm
    WITH
    OWNER = postgres
    ENCODING = 'UTF8'
    LC_COLLATE = 'en_US.utf8'
    LC_CTYPE = 'en_US.utf8'
    TABLESPACE = pg_default
    CONNECTION LIMIT = -1;

CREATE USER dfm WITH PASSWORD 'dfm_password';
GRANT ALL PRIVILEGES ON DATABASE dfm TO dfm;

-- Connect to dfm database and grant schema permissions
\c dfm;
GRANT ALL ON SCHEMA public TO dfm;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO dfm;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO dfm;

-- Enable required extensions for DFM
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pg_stat_statements";

-- Return to postgres database
\c postgres;
