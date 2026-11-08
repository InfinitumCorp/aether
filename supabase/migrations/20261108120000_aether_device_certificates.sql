-- ÆTHER device certificates: the CA public keys an organization trusts and every
-- certificate issued with `aether-ca sign`, so serials can be looked up and revoked.
-- The CA secret key never goes here; it stays offline with the issuer.
--
-- Same rules as the base schema: RLS on, no client policies, service role only.

create table aether.certificate_authorities (
  key_id      text primary key check (key_id ~ '^[0-9a-f]{16}$'),   -- SHA-256(public_key)[0..8]
  public_key  bytea not null unique check (octet_length(public_key) = 32),  -- Ed25519
  label       text not null check (char_length(label) between 1 and 64),
  created_at  timestamptz not null default now(),
  retired_at  timestamptz
);

create table aether.device_certificates (
  serial        text primary key check (serial ~ '^[0-9a-f]{32}$'),
  ca_key_id     text not null references aether.certificate_authorities (key_id),
  device_id     uuid references aether.devices (id) on delete set null,
  device_key    bytea not null check (octet_length(device_key) = 32),  -- X25519 identity key
  label         text not null check (octet_length(label) between 1 and 64),
  profile       text not null check (profile in ('node', 'relay', 'operator')),
  not_before    timestamptz not null,
  not_after     timestamptz not null,
  certificate   bytea not null,
  issued_at     timestamptz not null default now(),
  revoked_at    timestamptz,
  revoke_reason text check (revoke_reason is null or char_length(revoke_reason) <= 200),
  check (not_after > not_before)
);

create index device_certificates_device_key_idx on aether.device_certificates (device_key);
create index device_certificates_revoked_idx on aether.device_certificates (revoked_at) where revoked_at is not null;

alter table aether.certificate_authorities enable row level security;
alter table aether.device_certificates enable row level security;

revoke all on aether.certificate_authorities, aether.device_certificates from public, anon, authenticated;
grant select, insert, update, delete on aether.certificate_authorities, aether.device_certificates to service_role;
