-- Accept databases where the column was already added during an earlier deployment.
ALTER TABLE chat_message ADD COLUMN IF NOT EXISTS deleted boolean;
ALTER TABLE chat_message ALTER COLUMN deleted SET DEFAULT false;
UPDATE chat_message SET deleted = false WHERE deleted IS NULL;
ALTER TABLE chat_message ALTER COLUMN deleted SET NOT NULL;
