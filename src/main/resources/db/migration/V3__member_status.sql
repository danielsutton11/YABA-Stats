-- Torn status state from the faction roster (e.g. Okay, Hospital, Fallen).
-- Fallen members are excluded from stats, BSP lookups, reports and member counts.
ALTER TABLE member ADD COLUMN status_state TEXT;
