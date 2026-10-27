-- Run against an existing Moodify database to remove legacy payment
-- idempotency tables and the provider-transaction uniqueness guard.
DROP TABLE IF EXISTS payment_webhook_logs;
DROP TABLE IF EXISTS payment_idempotency_keys;

SET @payment_tx_unique_index_exists = (
    SELECT COUNT(*)
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_transactions'
      AND index_name = 'uk_payment_provider_tx'
);
SET @drop_payment_tx_unique_index_sql = IF(
    @payment_tx_unique_index_exists > 0,
    'ALTER TABLE payment_transactions DROP INDEX uk_payment_provider_tx',
    'SELECT 1'
);
PREPARE drop_payment_tx_unique_index_stmt FROM @drop_payment_tx_unique_index_sql;
EXECUTE drop_payment_tx_unique_index_stmt;
DEALLOCATE PREPARE drop_payment_tx_unique_index_stmt;
