#!/bin/bash
set -e

# Create pact_broker database and user for Pact Broker
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "postgres" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'pact_broker') THEN
            CREATE USER pact_broker WITH PASSWORD 'pact_broker';
        END IF;
    END
    \$\$;
    SELECT 'CREATE DATABASE pact_broker OWNER pact_broker' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'pact_broker')\gexec
EOSQL
