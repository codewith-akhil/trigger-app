#!/usr/bin/env bash
# Deploys the feed backend: SQL migration + edge functions.
# Usage: bash deploy-feed.sh <sbp_access_token> [function ...]
#   (no function args = the 4 feed functions)
set -euo pipefail

TOKEN="${1:?Usage: deploy-feed.sh <sbp_token> [function ...]}"
shift || true
PROJECT_REF="uazkcainrajcgxecomly"
REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FUNCS_DIR="${REPO_DIR}/supabase/functions"
# functions that self-validate (documented --no-verify-jwt design)
NO_VERIFY_JWT="purge-expired-media ${NO_VERIFY_JWT:-}"
FUNCTIONS=("$@")
[ ${#FUNCTIONS[@]} -eq 0 ] && FUNCTIONS=(save-feed-post get-feed create-razorpay-order verify-razorpay-payment)

deploy_function() {
  local slug="$1"
  local stage="/tmp/sb-deploy-${slug}"
  rm -rf "${stage}"; mkdir -p "${stage}/_shared"

  # stage function dir
  cp -r "${FUNCS_DIR}/${slug}/." "${stage}/"
  # stage every _shared file that the function imports (transitively, one hop
  # is enough here: all shared files are leaf modules)
  grep -rhoE 'from "\.\./_shared/[a-z_]+\.ts"' "${stage}" --include='*.ts' | \
    sed -E 's|.*_shared/([a-z_]+\.ts)".*|\1|' | sort -u | while read -r f; do
      [ -f "${FUNCS_DIR}/_shared/${f}" ] && cp "${FUNCS_DIR}/_shared/${f}" "${stage}/_shared/"
    done

  # flatten parent imports: the Management API bundles server-side from ONLY
  # the uploaded files, so "../_shared/x.ts" must become "./_shared/x.ts"
  # (shared files have no cross-imports, one rewrite pass is enough)
  grep -rl '\.\./_shared/' "${stage}" --include='*.ts' | while read -r f; do
    sed -i 's|\.\./_shared/|./_shared/|g' "${f}"
  done

  # build multipart request: metadata part + one file part per source file
  local args=()
  args+=(-F "metadata={\"entrypoint_path\":\"index.ts\",\"name\":\"${slug}\"};type=application/json")
  while IFS= read -r rel; do
    args+=(-F "file=@${stage}/${rel};filename=${rel};type=application/typescript")
  done < <(cd "${stage}" && find . -name '*.ts' -type f | sed 's|^\./||' | sort)

  local verify_jwt="true"
  case " ${NO_VERIFY_JWT} " in *" ${slug} "*) verify_jwt="false" ;; esac
  local http
  http=$(curl -s -o "/tmp/sb-deploy-${slug}-resp.json" -w "%{http_code}" \
    -X POST "https://api.supabase.com/v1/projects/${PROJECT_REF}/functions/deploy?slug=${slug}&verify_jwt=${verify_jwt}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${args[@]}")
  echo "    -> ${slug}: HTTP ${http} $(head -c 160 "/tmp/sb-deploy-${slug}-resp.json")"
  [ "${http}" = "200" ] || [ "${http}" = "201" ]
}

echo "==> 1/2 Applying SQL migration (idempotent)"
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
# Supabase query API returns 200 on SELECT-only and 201 when statements ran
if [ "${HTTP}" != "200" ] && [ "${HTTP}" != "201" ]; then cat /tmp/feed_sql_resp.txt; echo; echo "SQL FAILED — aborting."; exit 1; fi
echo "    SQL OK"

echo "==> 2/2 Deploying edge functions (multipart API)"
FAIL=0
for fn in "${FUNCTIONS[@]}"; do
  if ! deploy_function "${fn}"; then FAIL=1; fi
done
[ "${FAIL}" = "0" ] || { echo "ONE OR MORE DEPLOYS FAILED"; exit 1; }

echo "==> DONE. Feed backend is live."
