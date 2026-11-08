-- ÆTHER certificate registry API: functions `aether-ca` calls through PostgREST
-- (`POST /rest/v1/rpc/<name>`) with the service-role key.
--
-- They live in `public` because that is the schema PostgREST exposes; the `aether`
-- schema itself stays unexposed. They run as the caller (security invoker), so only a
-- role that already has the `aether` table grants (service_role) can do anything, and
-- EXECUTE is revoked from everyone else as well. Keys travel as lowercase hex.

create or replace function public.aether_register_ca(p_public_key_hex text, p_label text)
returns text
language plpgsql
security invoker
set search_path = ''
as $$
declare
  k  bytea := decode(p_public_key_hex, 'hex');
  id text  := encode(substring(pg_catalog.sha256(k) from 1 for 8), 'hex');
begin
  insert into aether.certificate_authorities (key_id, public_key, label)
  values (id, k, p_label)
  on conflict (key_id) do update set label = excluded.label;
  return id;
end;
$$;

-- Records an issued certificate and enrols (or re-labels) the device it names.
create or replace function public.aether_record_certificate(
  p_serial text,
  p_ca_key_id text,
  p_device_key_hex text,
  p_label text,
  p_profile text,
  p_not_before_ms bigint,
  p_not_after_ms bigint,
  p_certificate_hex text
)
returns uuid
language plpgsql
security invoker
set search_path = ''
as $$
declare
  dk  bytea := decode(p_device_key_hex, 'hex');
  dev uuid;
begin
  insert into aether.devices (public_key, label)
  values (dk, p_label)
  on conflict (public_key) do update set label = excluded.label
  returning id into dev;

  insert into aether.device_certificates
    (serial, ca_key_id, device_id, device_key, label, profile, not_before, not_after, certificate)
  values (
    p_serial, p_ca_key_id, dev, dk, p_label, p_profile,
    to_timestamp(p_not_before_ms / 1000.0), to_timestamp(p_not_after_ms / 1000.0),
    decode(p_certificate_hex, 'hex')
  );
  return dev;
end;
$$;

-- Revokes one certificate. Returns false if the serial is unknown or already revoked.
create or replace function public.aether_revoke_certificate(p_serial text, p_reason text default null)
returns boolean
language plpgsql
security invoker
set search_path = ''
as $$
begin
  update aether.device_certificates
     set revoked_at = now(), revoke_reason = p_reason
   where serial = p_serial and revoked_at is null;
  return found;
end;
$$;

-- Registry view of one certificate: 'unknown', 'revoked', 'expired' or 'valid'.
create or replace function public.aether_certificate_status(p_serial text)
returns table (status text, label text, profile text, not_after timestamptz, revoked_at timestamptz, revoke_reason text)
language sql
stable
security invoker
set search_path = ''
as $$
  select case
           when c.serial is null then 'unknown'
           when c.revoked_at is not null then 'revoked'
           when c.not_after < now() then 'expired'
           else 'valid'
         end,
         c.label, c.profile, c.not_after, c.revoked_at, c.revoke_reason
    from (select 1) one
    left join aether.device_certificates c on c.serial = p_serial;
$$;

revoke all on function
  public.aether_register_ca(text, text),
  public.aether_record_certificate(text, text, text, text, text, bigint, bigint, text),
  public.aether_revoke_certificate(text, text),
  public.aether_certificate_status(text)
from public, anon, authenticated;

grant execute on function
  public.aether_register_ca(text, text),
  public.aether_record_certificate(text, text, text, text, text, bigint, bigint, text),
  public.aether_revoke_certificate(text, text),
  public.aether_certificate_status(text)
to service_role;
