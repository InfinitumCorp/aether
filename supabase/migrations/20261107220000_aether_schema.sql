-- ÆTHER MIL backend schema (shared Supabase project "Infinitum Corp").
--
-- Holds only what the organization manages centrally: enrolled devices and the number
-- bundles granted to them. Burner-number records, keys and messages never leave the
-- device's encrypted vault and are not stored here.
--
-- Row-level security is on for every table with no client policies: only the service
-- role (Worker / Edge Function secrets) can read or write. The anon key sees nothing.

create schema if not exists aether;

revoke all on schema aether from public, anon, authenticated;
grant usage on schema aether to service_role;

-- Enrolled ÆTHER nodes: identity public keys the organization recognises.
create table aether.devices (
  id          uuid primary key default gen_random_uuid(),
  public_key  bytea not null unique check (octet_length(public_key) = 32),
  label       text not null check (char_length(label) between 1 and 64),
  enrolled_at timestamptz not null default now(),
  revoked_at  timestamptz,
  check (revoked_at is null or revoked_at >= enrolled_at)
);

-- Number bundles: 1, 2 or 5 slots, renewed monthly or yearly. Matches the shared
-- bundle record (docs/BURNER_NUMBERS.md) and FABRIC's relay model. No pricing.
create table aether.bundles (
  id          text primary key check (id ~ '^[A-Za-z0-9_-]{22}$'),  -- 128-bit base64url
  device_id   uuid not null references aether.devices (id) on delete cascade,
  size        smallint not null check (size in (1, 2, 5)),
  period      text not null check (period in ('month', 'year')),
  granted_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  check (expires_at > granted_at)
);

create index bundles_device_id_idx on aether.bundles (device_id);

alter table aether.devices enable row level security;
alter table aether.bundles enable row level security;

revoke all on all tables in schema aether from public, anon, authenticated;
grant select, insert, update, delete on all tables in schema aether to service_role;
