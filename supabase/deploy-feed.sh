#!/usr/bin/env bash
# Deploys the feed backend: SQL migration + 4 edge functions.
# Usage: bash deploy-feed.sh <sbp_access_token>
set -euo pipefail

TOKEN="${1:?Usage: deploy-feed.sh <sbp_token>}"
PROJECT_REF="uazkcainrajcgxecomly"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> 1/2 Applying SQL migration"
HTTP=$(curl -s -o /tmp/feed_sql_resp.txt -w "%{http_code}" \
  -X POST "https://api.supabase.com/v1/projects/${PROJECT_REF}/database/query" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$(python3 - "$REPO_DIR/supabase/migrations/20260911_feed_system.sql" << 'EOF'
import json, sys
print(json.dumps({"query": open(sys.argv[1]).read()}))
EOF
)")
echo "    HTTP ${HTTP}"
if [ "${HTTP}" != "200" ]; then cat /tmp/feed_sql_resp.txt; echo; echo "SQL FAILED — aborting."; exit 1; fi
echo "    SQL OK"

echo "==> 2/2 Deploying edge functions"
for fn in save-feed-post get-feed create-razorpay-order verify-razorpay-payment; do
  echo "    -> ${fn}"
  HTTP=$(cd "${REPO_DIR}/supabase/functions/${fn}" && zip -qr /tmp/fn.zip * && \
  curl -s -o /tmp/fn_resp.txt -w "%{http_code}" \
    -X POST "https://api.supabase.com/v1/projects/${PROJECT_REF}/functions/deploy?slug=${fn}&verify_jwt=true" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/zip" \
    --data-binary @/tmp/fn.zip)
  echo "       HTTP ${HTTP} $(cat /tmp/fn_resp.txt | head -c 200)"
  if [ "${HTTP}" != "200" ]; then echo "    ${fn} FAILED"; exit 1; fi
done

echo "==> DONE. Feed backend is live."
