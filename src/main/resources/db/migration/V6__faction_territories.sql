-- Number of territories the faction holds (from /faction/{id}/territory), refreshed with each roster
ALTER TABLE faction ADD COLUMN territories INTEGER;
