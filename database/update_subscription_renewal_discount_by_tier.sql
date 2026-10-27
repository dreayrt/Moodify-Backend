-- Moodify: update only the subscription-expiry reminder procedure.
-- Same-tier package variants (different active durations) are eligible for
-- the existing 20% renewal offer. The existing MySQL Event keeps calling this
-- procedure; no table, event, or song-license routine is changed here.

USE moodify;

DELIMITER $$

DROP PROCEDURE IF EXISTS sp_create_subscription_expiry_notifications$$
CREATE PROCEDURE sp_create_subscription_expiry_notifications()
BEGIN
    -- Notify only the user's furthest active subscription, avoiding reminders
    -- for an older subscription when a later subscription is already queued.
    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ',
            DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT('subscription-expiry:', s.user_id, ':',
               DATE_FORMAT(s.end_at, '%Y%m%d'), ':7')
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 7 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (later_sub.end_at > s.end_at
                 OR (later_sub.end_at = s.end_at AND later_sub.id > s.id))
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );

    INSERT IGNORE INTO notifications
        (user_id, title, message, type, link_url, created_at, dedupe_key)
    SELECT
        s.user_id,
        CONCAT('Gói ', p.name, ' sắp hết hạn'),
        CONCAT(
            'Gói ', p.name, ' của bạn sẽ hết hạn ngày ',
            DATE_FORMAT(s.end_at, '%d/%m/%Y'),
            '. Gia hạn cùng nhóm quyền lợi, kể cả chọn thời hạn khác, được giảm 20% trên giá hiện tại của biến thể đó.'
        ),
        'BILLING',
        '/dashboard/premium',
        NOW(),
        CONCAT('subscription-expiry:', s.user_id, ':',
               DATE_FORMAT(s.end_at, '%Y%m%d'), ':1')
    FROM subscriptions s
    JOIN service_packages p ON p.id = s.service_package_id
    JOIN users u ON u.id = s.user_id AND u.status = 'ACTIVE'
    WHERE s.status = 'ACTIVE'
      AND s.start_at <= NOW()
      AND s.end_at > NOW()
      AND DATE(s.end_at) = CURRENT_DATE + INTERVAL 1 DAY
      AND NOT EXISTS (
          SELECT 1
          FROM subscriptions later_sub
          WHERE later_sub.user_id = s.user_id
            AND later_sub.status = 'ACTIVE'
            AND later_sub.end_at > NOW()
            AND (later_sub.end_at > s.end_at
                 OR (later_sub.end_at = s.end_at AND later_sub.id > s.id))
      )
      AND EXISTS (
          SELECT 1
          FROM payment_transactions paid_tx
          JOIN subscriptions paid_sub ON paid_sub.id = paid_tx.subscription_id
          JOIN service_packages paid_package ON paid_package.id = paid_sub.service_package_id
          WHERE paid_sub.user_id = s.user_id
            AND paid_package.tier_id = p.tier_id
            AND paid_tx.status = 'SUCCESS'
            AND COALESCE(paid_tx.provider, '') NOT IN ('SANDBOX', 'DEV_SANDBOX')
      );
END$$

DELIMITER ;

-- Optional: run once to create reminders immediately for currently matching
-- subscriptions, or verify the installed routine with SHOW CREATE PROCEDURE.
-- CALL sp_create_subscription_expiry_notifications();
-- SHOW CREATE PROCEDURE sp_create_subscription_expiry_notifications;
