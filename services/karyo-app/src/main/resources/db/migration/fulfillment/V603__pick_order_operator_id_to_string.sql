-- operatorId was an unused BIGINT; the unified work-inbox uses the Keycloak username (text).
ALTER TABLE pick_orders ALTER COLUMN operator_id TYPE VARCHAR(100) USING operator_id::VARCHAR;
