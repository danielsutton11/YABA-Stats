-- Faction total respect (from /faction/{id}/basic → respect), refreshed with each roster
ALTER TABLE faction ADD COLUMN respect BIGINT;
