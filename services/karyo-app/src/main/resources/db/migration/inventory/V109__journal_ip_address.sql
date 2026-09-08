-- V109__journal_ip_address.sql
-- SC19: source IP for auth-event journal rows (LOGIN/LOGOUT/LOGIN_FAILED, from the
-- Keycloak admin Events API). Nullable + additive: every pre-existing row and every
-- stock-movement row simply has no IP. 45 chars covers a full IPv6 textual form.
ALTER TABLE karyo.inventory_journals ADD COLUMN ip_address VARCHAR(45);
