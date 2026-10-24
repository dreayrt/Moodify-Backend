# MySQL notifications and renewal discounts

## What this change deploys

- Stores inbox notifications and admin broadcast history in MySQL.
- Creates `sp_create_subscription_expiry_notifications` and the daily MySQL Event that calls it at 09:00 UTC+7.
- Applies a 20% renewal discount when the account has a prior successful non-sandbox payment for the same `tier_id`; package duration may differ.
- Leaves song-license expiry and Oracle catalog synchronization on their existing Spring schedules. The deployment SQL removes the former MySQL song-license event so it cannot compete with Spring.

## Deploy the database objects

1. Back up the `moodify` database.
2. Run `database/deploy_subscription_renewal_notifications.sql` against the target MySQL server.
3. Confirm the Event Scheduler is `ON` and that `evt_daily_subscription_expiry_reminders` is enabled:

```sql
SHOW VARIABLES LIKE 'event_scheduler';
SHOW EVENTS FROM moodify;
```

The SQL session sets `time_zone` to `+07:00` before creating the Event. MySQL captures the creating session's time zone for that Event. `SET GLOBAL event_scheduler = ON` requires an administrative database account; if it is denied, enable it in the MySQL server configuration and rerun the script. MySQL documents that Events do not execute unless Event Scheduler is enabled and that `CREATE EVENT` uses the session time zone at creation: [CREATE EVENT](https://dev.mysql.com/doc/refman/8.0/en/create-event.html).

## Renewal eligibility

Eligibility is computed from successful payment history joined through `payment_transactions.subscription_id`, `subscriptions.service_package_id`, and `service_packages.tier_id`. A purchase of FULL for 30 days qualifies the same account for FULL 90-day and FULL 1-year variants, while BASIC and FAMILY remain separate tiers. `SANDBOX` and `DEV_SANDBOX` records do not qualify. The selected variant's current `service_packages.price` is the source amount; 20% is applied to that amount. No pricing columns are added. The final payable VND amount is stored in the existing `payment_transactions.amount` field and encoded in the QR.

The subscription application policy is:

- A same-tier purchase (including a different duration) starts after the currently effective plan ends.
- An upgrade applies immediately. The remaining value of the current paid subscription is converted to whole bonus days using the selected package's price per day. Any already paid future package remains queued before later purchases.
- A downgrade starts after the current plan ends.
- Tier ranking for upgrades is BASIC < FULL < FAMILY.

The backend exposes authenticated package quotes at `/api/subscriptions/package-quotes`. It returns the discounted amount, action label, estimated start/end, and any upgrade bonus. Checkout and SePay confirmation calculate the policy again on the server; the confirmed subscription dates are the source of truth.

Each successful subscription payment writes a deduplicated `BILLING` inbox notification in the same MySQL transaction as the payment and subscription updates. It includes the package, amount, and actual scheduled/effective period. Underpayments receive a separate notice while the QR order remains pending. Orders cancelled by a replacement checkout or expired after 15 minutes receive one failure notice. Spring checks expired pending orders every minute, so expiry notifications are created even if the user closes the checkout page. This uses the existing `notifications.dedupe_key` column and requires no additional database migration.

## Event behavior

The procedure checks the user's furthest effective subscription end date and sends reminders only when payment history includes a successful non-sandbox purchase from the same tier. It creates one `BILLING` notification at 7 days and 1 day before expiry. A unique `dedupe_key` prevents duplicate reminders if the procedure or Event runs again. The Event only inserts MySQL inbox rows; Spring serves the notification API.

If the notification/Event setup was already deployed and only the new tier-based reminder wording and eligibility need to be applied, run `database/update_subscription_renewal_discount_by_tier.sql`. This focused script replaces the subscription reminder procedure only. It does not recreate notification tables, modify the Event, or touch song-license routines.

## Manual checks

```sql
SHOW CREATE PROCEDURE sp_create_subscription_expiry_notifications;
SHOW CREATE EVENT evt_daily_subscription_expiry_reminders;
SELECT id, user_id, title, type, created_at, dedupe_key
FROM notifications
ORDER BY id DESC
LIMIT 20;
```

Calling the procedure manually is safe from duplicate rows, but it can send reminders immediately for users currently at the 7-day or 1-day boundary.
