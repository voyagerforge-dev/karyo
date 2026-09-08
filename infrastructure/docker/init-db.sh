#!/bin/bash
set -e

# Create the application schema in the main karyo database.
# Flyway also has create-schemas:true — this is belt-and-braces for first boot.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS karyo;
EOSQL

# Create keycloak database
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
    SELECT 'CREATE DATABASE keycloak OWNER ' || quote_ident(current_user) WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'keycloak')\gexec
EOSQL
