-- Airlock: friend requests, remove/block, new code, rate-limited codes, hidden hits,
-- and nuke limits that can't be bypassed from the app. Run once in the Supabase SQL editor after 002.

-- ---------- Profiles ----------
alter table public.profiles add column time_zone text not null default 'UTC';
alter table public.profiles add column hide_hits boolean not null default false; -- senders don't learn if you were scrolling

-- ---------- Friend requests ----------
-- Existing friendships stay accepted; new ones start as a request the other person has to accept.
alter table public.friendships add column status text not null default 'accepted' check (status in ('pending', 'accepted'));
alter table public.friendships add column requested_by uuid references public.profiles on delete cascade;

create table public.blocks (
    blocker uuid not null references public.profiles on delete cascade,
    blocked uuid not null references public.profiles on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker, blocked)
);
alter table public.blocks enable row level security;
create policy "own blocks" on public.blocks for select using (blocker = auth.uid());

-- Every code entered, to stop people guessing codes. No policies: only the functions below touch it.
create table public.friend_code_attempts (
    user_id uuid not null references auth.users on delete cascade,
    at timestamptz not null default now()
);
create index friend_code_attempts_idx on public.friend_code_attempts (user_id, at);
alter table public.friend_code_attempts enable row level security;

-- Only accepted friends can nuke each other.
create or replace function public.are_friends(x uuid, y uuid) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from friendships where a = least(x, y) and b = greatest(x, y) and status = 'accepted');
$$;

-- Friends or a pending request either way (so you can see who asked).
create function public.is_connected(x uuid, y uuid) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from friendships where a = least(x, y) and b = greatest(x, y));
$$;

drop policy "own and friends' profiles" on public.profiles;
create policy "own and connected profiles" on public.profiles for select
    using (id = auth.uid() or public.is_connected(id, auth.uid()));

-- Older app versions added friends without asking.
create or replace function public.add_friend(friend_code text) returns public.profiles
language plpgsql security definer set search_path = public as $$
begin
    raise exception 'Update Airlock to add friends';
end;
$$;

-- Enter someone's code: sends a request, or accepts theirs if they already asked you.
create function public.request_friend(friend_code text) returns json
language plpgsql security definer set search_path = public as $$
declare
    me uuid := auth.uid();
    friend public.profiles;
    existing public.friendships;
begin
    if me is null then raise exception 'not signed in'; end if;
    if (select count(*) from friend_code_attempts where user_id = me and at > now() - interval '1 hour') >= 10 then
        return json_build_object('result', 'rate_limited');
    end if;
    insert into friend_code_attempts (user_id) values (me);

    select * into friend from profiles where code = upper(trim(friend_code));
    -- Blocked either way looks exactly like a wrong code.
    if not found or exists (select 1 from blocks
                            where (blocker = friend.id and blocked = me) or (blocker = me and blocked = friend.id)) then
        return json_build_object('result', 'not_found');
    end if;
    if friend.id = me then return json_build_object('result', 'self'); end if;

    select * into existing from friendships where a = least(me, friend.id) and b = greatest(me, friend.id);
    if found then
        if existing.status = 'accepted' then return json_build_object('result', 'already', 'name', friend.name); end if;
        if existing.requested_by = me then return json_build_object('result', 'requested', 'name', friend.name); end if;
        update friendships set status = 'accepted' where a = existing.a and b = existing.b;
        return json_build_object('result', 'accepted', 'name', friend.name);
    end if;

    insert into friendships (a, b, status, requested_by) values (least(me, friend.id), greatest(me, friend.id), 'pending', me);
    return json_build_object('result', 'requested', 'name', friend.name);
end;
$$;

-- Accept or decline a request someone sent you.
create function public.respond_friend(friend uuid, accept boolean) returns void
language plpgsql security definer set search_path = public as $$
begin
    if accept then
        update friendships set status = 'accepted'
        where a = least(auth.uid(), friend) and b = greatest(auth.uid(), friend) and status = 'pending' and requested_by = friend;
    else
        delete from friendships
        where a = least(auth.uid(), friend) and b = greatest(auth.uid(), friend) and status = 'pending';
    end if;
end;
$$;

-- Unfriend and make sure they can't send a new request.
create function public.block_user(other uuid) returns void
language sql security definer set search_path = public as $$
    delete from friendships where a = least(auth.uid(), other) and b = greatest(auth.uid(), other);
    insert into blocks (blocker, blocked) values (auth.uid(), other) on conflict do nothing;
$$;

-- New share code; the old one stops working.
create function public.new_code() returns public.profiles
language plpgsql security definer set search_path = public as $$
declare
    me public.profiles;
    fresh text;
begin
    loop
        fresh := (select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1), '')
                  from generate_series(1, 6));
        begin
            update profiles set code = fresh where id = auth.uid() returning * into me;
            return me;
        exception when unique_violation then
            -- taken, try another
        end;
    end loop;
end;
$$;

-- Time zone (for the daily nuke reset) and the hide-hits privacy switch.
create function public.set_settings(tz text, hide boolean) returns void
language sql security definer set search_path = public as $$
    update profiles set
        time_zone = case when exists (select 1 from pg_timezone_names where name = tz) then tz else time_zone end,
        hide_hits = hide
    where id = auth.uid();
$$;

-- ---------- Nukes ----------
-- The limit used to trust a day_start sent by the app, so a faked "now" meant unlimited nukes.
-- Midnight is now worked out here from the sender's time zone; day_start is only kept so old apps still work.
drop function public.send_nuke(uuid, timestamptz, boolean);
create function public.send_nuke(
    target_id uuid,
    day_start timestamptz default null,
    use_bonus boolean default false
) returns public.nukes
language plpgsql security definer set search_path = public as $$
declare
    nuke public.nukes;
    tz text;
    today timestamptz;
begin
    if not are_friends(auth.uid(), target_id) then raise exception 'Not your friend'; end if;
    select coalesce(time_zone, 'UTC') into tz from profiles where id = auth.uid();
    today := date_trunc('day', now() at time zone tz) at time zone tz;

    if use_bonus then
        if exists (select 1 from nukes
                   where sender = auth.uid() and target = target_id and nukes.bonus and created_at >= today) then
            raise exception 'Secret nuke already used today';
        end if;
    elsif (select count(*) from nukes
           where sender = auth.uid() and target = target_id and not nukes.bonus and created_at >= today) >= 3 then
        raise exception 'Out of nukes for today';
    end if;

    insert into nukes (sender, target, bonus) values (auth.uid(), target_id, use_bonus) returning * into nuke;
    return nuke;
end;
$$;

-- Senders no longer read nukes directly (that would show hit/miss even when the target hides it).
drop policy "nukes I sent or received" on public.nukes;
create policy "nukes aimed at me" on public.nukes for select using (target = auth.uid());

-- Nukes I sent or received; hit/miss is masked as 'hidden' when the target hides it.
create function public.recent_nukes() returns setof public.nukes
language sql stable security definer set search_path = public as $$
    select n.id, n.sender, n.target, n.created_at,
           case when n.sender = auth.uid() and p.hide_hits and n.status <> 'armed' then 'hidden' else n.status end,
           case when n.sender = auth.uid() and p.hide_hits then null else n.hit_at end,
           n.bonus
    from nukes n join profiles p on p.id = n.target
    where auth.uid() in (n.sender, n.target)
    order by n.created_at desc
    limit 50;
$$;

create function public.nuke_status(nuke_id bigint) returns setof public.nukes
language sql stable security definer set search_path = public as $$
    select n.id, n.sender, n.target, n.created_at,
           case when n.sender = auth.uid() and p.hide_hits and n.status <> 'armed' then 'hidden' else n.status end,
           case when n.sender = auth.uid() and p.hide_hits then null else n.hit_at end,
           n.bonus
    from nukes n join profiles p on p.id = n.target
    where n.id = nuke_id and auth.uid() in (n.sender, n.target);
$$;
