-- Shake Lock: friends and nukes.
-- Run once in the Supabase SQL editor of the shake-lock project.
-- Every phone signs in anonymously; a profile has a display name and a share code.
-- Sharing your code = agreeing to be nuked by whoever enters it. Either side can unfriend.

create table public.profiles (
    id uuid primary key references auth.users on delete cascade,
    name text not null check (char_length(name) between 1 and 30),
    code text not null unique,
    created_at timestamptz not null default now()
);

create table public.friendships (
    a uuid not null references public.profiles on delete cascade,
    b uuid not null references public.profiles on delete cascade,
    created_at timestamptz not null default now(),
    primary key (a, b),
    check (a < b)
);

create table public.nukes (
    id bigint generated always as identity primary key,
    sender uuid not null references public.profiles on delete cascade,
    target uuid not null references public.profiles on delete cascade,
    created_at timestamptz not null default now(),
    -- armed: on its way / waiting for the target to open a blocked app
    -- hit: landed; expired: target didn't open a blocked app in time
    status text not null default 'armed' check (status in ('armed', 'hit', 'expired')),
    hit_at timestamptz
);

create index nukes_target_idx on public.nukes (target, created_at desc);
create index nukes_sender_idx on public.nukes (sender, created_at desc);

alter table public.profiles enable row level security;
alter table public.friendships enable row level security;
alter table public.nukes enable row level security;

create function public.are_friends(x uuid, y uuid) returns boolean
language sql stable security definer set search_path = public as $$
    select exists (select 1 from friendships where a = least(x, y) and b = greatest(x, y));
$$;

-- Read access only; all writes go through the functions below.
create policy "own and friends' profiles" on public.profiles for select
    using (id = auth.uid() or public.are_friends(id, auth.uid()));
create policy "own friendships" on public.friendships for select
    using (auth.uid() in (a, b));
create policy "nukes I sent or received" on public.nukes for select
    using (auth.uid() in (sender, target));

-- Create or rename my profile. Returns it.
create function public.ensure_profile(display_name text) returns public.profiles
language plpgsql security definer set search_path = public as $$
declare
    me public.profiles;
    new_code text;
begin
    if auth.uid() is null then raise exception 'not signed in'; end if;
    update profiles set name = display_name where id = auth.uid() returning * into me;
    if found then return me; end if;
    loop
        -- 6 characters, no look-alikes (0/O, 1/I)
        new_code := (select string_agg(substr('ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 1 + floor(random() * 32)::int, 1), '')
                     from generate_series(1, 6));
        begin
            insert into profiles (id, name, code) values (auth.uid(), display_name, new_code) returning * into me;
            return me;
        exception when unique_violation then
            -- code taken, try another
        end;
    end loop;
end;
$$;

-- Add a friend by their code. Returns the friend's profile.
create function public.add_friend(friend_code text) returns public.profiles
language plpgsql security definer set search_path = public as $$
declare
    friend public.profiles;
begin
    select * into friend from profiles where code = upper(trim(friend_code));
    if not found then raise exception 'No one with that code'; end if;
    if friend.id = auth.uid() then raise exception 'That''s your own code'; end if;
    insert into friendships (a, b) values (least(auth.uid(), friend.id), greatest(auth.uid(), friend.id))
        on conflict do nothing;
    return friend;
end;
$$;

create function public.remove_friend(friend uuid) returns void
language sql security definer set search_path = public as $$
    delete from friendships where a = least(auth.uid(), friend) and b = greatest(auth.uid(), friend);
$$;

-- Fire a nuke at a friend. Max 3 per friend per day.
create function public.send_nuke(target_id uuid) returns public.nukes
language plpgsql security definer set search_path = public as $$
declare
    nuke public.nukes;
begin
    if not are_friends(auth.uid(), target_id) then raise exception 'Not your friend'; end if;
    if (select count(*) from nukes
        where sender = auth.uid() and target = target_id and created_at > now() - interval '1 day') >= 3 then
        raise exception 'Out of nukes for today';
    end if;
    insert into nukes (sender, target) values (auth.uid(), target_id) returning * into nuke;
    return nuke;
end;
$$;

-- The target's phone reports what happened.
create function public.report_nuke(nuke_id bigint, new_status text) returns void
language sql security definer set search_path = public as $$
    update nukes set status = new_status, hit_at = case when new_status = 'hit' then now() end
    where id = nuke_id and target = auth.uid() and status = 'armed' and new_status in ('hit', 'expired');
$$;

-- Live updates: targets hear about new nukes, senders about hits.
alter publication supabase_realtime add table public.nukes;
