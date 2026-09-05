# Trigger App — Production Deployment Guide

## Prerequisites

1. **Supabase CLI** installed: `npm install -g supabase`
2. **Supabase account** + project created at https://supabase.com
3. **Access token**: run `supabase login` locally
4. **Docker** (for local dev only — not needed for remote deploy)

## Step 1: Link the project

```bash
cd trigger-app
supabase link --project-ref <your-project-ref>
```

## Step 2: Set required secrets

```bash
# Supabase (auto-provided by platform, but verify):
supabase secrets set SUPABASE_URL=https://<project>.supabase.co
supabase secrets set SUPABASE_SERVICE_ROLE_KEY=<your-service-role-key>
supabase secrets set SUPABASE_ANON_KEY=<your-anon-key>

# Email (Resend):
supabase secrets set RESEND_API_KEY=<your-resend-api-key>

# Razorpay:
supabase secrets set RAZORPAY_KEY_ID=<your-razorpay-key-id>
supabase secrets set RAZORPAY_KEY_SECRET=<your-razorpay-key-secret>
supabase secrets set RAZORPAY_WEBHOOK_SECRET=<your-webhook-secret>

# Firebase (FCM):
supabase secrets set FIREBASE_CLIENT_EMAIL=<your-firebase-client-email>
supabase secrets set FIREBASE_PRIVATE_KEY=<your-firebase-private-key-pem>

# Agora:
supabase secrets set AGORA_APP_ID=<your-agora-app-id>
supabase secrets set AGORA_APP_CERTIFICATE=<your-agora-app-certificate>
supabase secrets set AGORA_PRIMARY_CERTIFICATE=<your-agora-primary-certificate>

# Cron security:
supabase secrets set CRON_SECRET=<a-strong-random-string>
```

## Step 3: Run database migrations

```bash
# Push all migrations in order:
supabase db push

# OR apply individually if you need to control the order:
# supabase db execute --file supabase/migrations/20260904_full_app_schema.sql
# ... etc for each migration file
```

**Important:** After running `20260905_cron_and_views.sql`, set the cron secret GUC:
```sql
ALTER DATABASE postgres SET app.cron_secret = '<same-CRON_SECRET-as-above>';
```

Also update the hardcoded project URL in the cron job:
```sql
-- In 20260905_cron_and_views.sql, replace <project-ref> with your actual project ref:
-- https://<project-ref>.functions.supabase.co/update-fx-rates
```

## Step 4: Deploy all edge functions

```bash
# Deploy all functions at once:
supabase functions deploy --no-verify-jwt

# OR deploy individually (49 functions):
supabase functions deploy verify-email-otp --no-verify-jwt
supabase functions deploy send-email-otp --no-verify-jwt
supabase functions deploy check-email --no-verify-jwt
supabase functions deploy reset-password --no-verify-jwt
supabase functions deploy delete-account-otp --no-verify-jwt
supabase functions deploy razorpay-webhook --no-verify-jwt
supabase functions deploy update-fx-rates --no-verify-jwt
supabase functions deploy cron-auto-start-streams --no-verify-jwt
supabase functions deploy send-stream-scheduled-email --no-verify-jwt
supabase functions deploy send-booking-confirmation-email --no-verify-jwt
# ... deploy the remaining 39 functions WITH JWT verification (default)
```

**Note:** The `verify_jwt = false` functions are configured in `supabase/config.toml`. When you run `supabase functions deploy`, the CLI reads config.toml and applies the correct JWT setting automatically. The `--no-verify-jwt` flag above is only for the initial deploy of public functions.

## Step 5: Set up the Razorpay webhook

1. Go to https://dashboard.razorpay.com/app/webhooks
2. Add a webhook for `https://<project>.supabase.co/functions/v1/razorpay-webhook`
3. Subscribe to `payment.captured` + `payment.failed` events
4. Copy the webhook secret → set as `RAZORPAY_WEBHOOK_SECRET` in Supabase secrets

## Step 6: Set up pg_cron jobs

The migration `20260905_cron_and_views.sql` creates:
- `update-fx-rates` — runs every hour
- `cron-auto-start-streams` — runs every minute
- `expire-stale-presence` — runs every minute

Verify they're scheduled:
```sql
SELECT jobid, jobname, schedule FROM cron.job;
```

## Step 7: Configure Storage buckets

The migration `20260904_full_app_schema.sql` creates these buckets:
- `avatars` (public, 10 MB, images only)
- `chat_media` (private, 64 MB)
- `voice_notes` (private, 16 MB)
- `documents` (private, 100 MB)
- `vault_media` (private, 100 MB)
- `stream_thumbnails` (public, 10 MB)

Verify in the Supabase Dashboard → Storage.

## Step 8: Configure Android app

1. Create a `.env` file in the `trigger-app/` root:
```bash
SUPABASE_URL=https://<project>.supabase.co
SUPABASE_ANON_KEY=<your-anon-key>
AGORA_APP_ID=<your-agora-app-id>
# (server-only keys are NOT needed in the app)
```

2. Build the release APK:
```bash
export KEYSTORE_PATH=$PWD/my-upload-key.jks
export STORE_PASSWORD=<your-keystore-password>
export KEY_ALIAS=<your-key-alias>
export KEY_PASSWORD=<your-key-password>
./gradlew :app:assembleRelease :app:bundleRelease
```

3. Output:
   - APK: `app/build/outputs/apk/release/app-release.apk`
   - AAB: `app/build/outputs/bundle/release/app-release.aab`

## Verification checklist

- [ ] `supabase db push` completes with no errors
- [ ] All 49 edge functions deploy successfully
- [ ] `SELECT count(*) FROM cron.job;` returns 3+ rows
- [ ] Test signup flow: app → `check-email` → `supabase.auth.signUp` → `send-email-otp` → `verify-email-otp` → `get-my-profile`
- [ ] Test login flow: `supabase.auth.signInWithPassword` → `get-my-profile`
- [ ] Test profile update: `sync-user-profile` with each field
- [ ] Test avatar upload: upload to `avatars` bucket → `sync-user-profile({avatarUrl})`
- [ ] Test chat: `send-message` → realtime → `sync-messages`
- [ ] Test wallet: `update-bank-details` → `wallet-withdraw`
- [ ] Test vault: `upsert-vault-pin` → `verify-vault-pin`
- [ ] Release APK installs + launches without crash
