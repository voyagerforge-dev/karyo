-- V1203__create_auth_event_cursor.sql
-- SC19: singleton progress cursor for the Keycloak auth-event poller (FanoutCursor shape,
-- see webhooks V1001). last_event_time is the Keycloak event timestamp in epoch millis;
-- the poller re-reads events with time >= cursor (deliberate overlap) and dedupes against
-- inventory_journals, so the cursor only has to be monotonic, not exact.
CREATE TABLE karyo.auth_event_cursor (
    id              BIGINT PRIMARY KEY,
    last_event_time BIGINT NOT NULL DEFAULT 0
);

INSERT INTO karyo.auth_event_cursor (id, last_event_time) VALUES (1, 0);
