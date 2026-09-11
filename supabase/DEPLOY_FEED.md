# Feed backend — deployment guide (v1.0.11)

The app (v1.0.11) is code-complete against this backend. Until these steps run,
the feed shows its honest **"Feed backend is not ready"** state — no fake data.

## What deploys

| Artifact | Path | Purpose |
|---|---|---|
| SQL migration | `supabase/migrations/20260911_feed_system.sql` | `feed_posts` + `feed_post_media` + `post_unlocks` tables (backend-generated ids, DB-level FREE/PAID check), RLS, private `feed-media` bucket + owner-folder policies, `countries` currency/fx seed |
| Edge function | `supabase/functions/save-feed-post/` | create / update / publish / delete (id always generated server-side, description ≤300 validated, paid posts require amount+currency) |
| Edge function | `supabase/functions/get-feed/` | newest-first feed, drafts, single post; private media served via short-lived signed URLs (tiny 24px preview for locked images, NOTHING for locked videos) |
| Edge function | `supabase/functions/create-razorpay-order/` (update) | widened Razorpay currency whitelist |
| Edge function | `supabase/functions/verify-razorpay-payment/` (update) | new `post_unlock` branch: records unlock (idempotent) + credits creator wallet |

## Option A — paste a fresh `sbp_` token to me

I run `bash supabase/deploy-feed.sh <sbp_...>` (applies SQL via the Management
API, then deploys all 4 functions). Takes minutes.

## Option B — manual (no token needed)

1. **SQL**: Supabase dashboard → SQL Editor → paste
   `supabase/migrations/20260911_feed_system.sql` → Run. (Idempotent.)
2. **Functions**: dashboard → Edge Functions:
   - Create `save-feed-post` and `get-feed` (copy from this repo).
   - Re-deploy `create-razorpay-order` and `verify-razorpay-payment`
     (copy the updated sources from this repo).

The moment SQL + `get-feed` are live, pull-to-refresh on the feed switches
from the setup notice to real posts.
