-- Shared ingress rate limiting for public, anonymous analytics endpoints.
-- Store only keyed HMAC digests of IP-derived identifiers, never raw IP addresses.
create schema if not exists private;
revoke all on schema private from public, anon, authenticated;
grant usage on schema private to service_role;

create table if not exists private.analytics_rate_limits (
  key_hash text not null check (key_hash ~ '^[a-f0-9]{64}$'),
  bucket_start timestamptz not null,
  hits integer not null default 1 check (hits > 0),
  updated_at timestamptz not null default now(),
  primary key (key_hash, bucket_start)
);

alter table private.analytics_rate_limits enable row level security;
revoke all on table private.analytics_rate_limits from public, anon, authenticated;
grant select, insert, update, delete on table private.analytics_rate_limits to service_role;

create or replace function public.consume_analytics_rate_limit(
  p_key_hash text,
  p_bucket_start timestamptz,
  p_max_hits integer
)
returns table (allowed boolean, hits integer)
language plpgsql
security definer
set search_path = pg_catalog, public, private
as $function$
declare
  v_hits integer;
begin
  if p_key_hash is null or p_key_hash !~ '^[a-f0-9]{64}$'
     or p_bucket_start is null
     or p_max_hits is null or p_max_hits < 1 or p_max_hits > 10000 then
    raise exception 'Invalid rate-limit arguments';
  end if;

  insert into private.analytics_rate_limits as limits (key_hash, bucket_start, hits, updated_at)
  values (p_key_hash, p_bucket_start, 1, clock_timestamp())
  on conflict (key_hash, bucket_start)
  do update set hits = limits.hits + 1, updated_at = clock_timestamp()
  returning limits.hits into v_hits;

  if random() < 0.02 then
    delete from private.analytics_rate_limits
    where bucket_start < clock_timestamp() - interval '1 hour';
  end if;

  return query select (v_hits <= p_max_hits), v_hits;
end;
$function$;

revoke all on function public.consume_analytics_rate_limit(text, timestamptz, integer) from public, anon, authenticated;
grant execute on function public.consume_analytics_rate_limit(text, timestamptz, integer) to service_role;
