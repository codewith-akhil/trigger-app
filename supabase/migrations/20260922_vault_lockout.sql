-- ============================================================================
-- 20260922_vault_lockout.sql
-- Vault PIN lockout: 5 lifetime attempts → 3 strikes per rolling 24h window.
--
-- verify-vault-pin now anchors each failure window with first_fail_at:
--   • attempts < 3                              → user has strikes left
--   • attempts >= 3 AND now - first_fail_at < 24h → LOCKED until
--     first_fail_at + 24h; the only ways out are the email-OTP reset
--     (reset-vault-pin) or the window elapsing
--   • attempts >= 3 AND window elapsed          → verify resets
--     attempts=0 + first_fail_at=null and lets the user try again
--
-- first_fail_at is stamped by bump_vault_pin_attempts (upgraded below) the
-- moment the counter goes 0 → 1 (first failure of a window) and is cleared
-- together with attempts on a correct PIN / window rollover / OTP reset.
-- ============================================================================

-- ------------------------------------------------------- first_fail_at column
alter table public.vault_pins
  add column if not exists first_fail_at timestamptz;

comment on column public.vault_pins.first_fail_at is
  'Start of the current 3-strikes/24h lockout window (first failed PIN attempt). NULL when the counter is clean. Locked until first_fail_at + interval ''24 hours''.';

-- ------------------------------ upgrade the atomic attempt-counter RPC ------
-- Same signature/ACL as 20260921_deep_audit_hardening.sql; additionally
-- anchors the lockout window on the 0 -> 1 transition. Column references in
-- the UPDATE see the PRE-update row, so attempts = 0 means "first strike".
create or replace function public.bump_vault_pin_attempts(p_user_id uuid)
returns void
language sql
security definer set search_path = public
as $$
  update public.vault_pins
     set attempts = attempts + 1,
         first_fail_at = case when attempts = 0 then now() else first_fail_at end
   where user_id = p_user_id;
$$;
revoke execute on function public.bump_vault_pin_attempts(uuid) from public, anon, authenticated;
grant execute on function public.bump_vault_pin_attempts(uuid) to service_role;
