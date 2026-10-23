-- Run once against an existing Moodify database to remove the retired
-- checkout idempotency and webhook deduplication tables.
DROP TABLE IF EXISTS payment_webhook_logs;
DROP TABLE IF EXISTS payment_idempotency_keys;
