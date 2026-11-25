-- Torn faction rank (from /faction/{id}/basic → rank), e.g. "Platinum II"
ALTER TABLE faction ADD COLUMN rank_label TEXT;
ALTER TABLE faction ADD COLUMN rank_level INTEGER;
