-- Shake Lock: nukes reset at midnight (instead of a rolling 24 h) + one secret bonus nuke per friend per day.
-- Run once in the Supabase SQL editor after 001.

alter table public.nukes add column bonus boolean not null default false;

drop function public.send_nuke(uuid);

-- day_start: the sender's local midnight (the app sends it); defaults to UTC midnight for older app versions.
-- use_bonus: the secret extra nuke (tap the empty nuke button 5x).
create function public.send_nuke(
    target_id uuid,
    day_start timestamptz default date_trunc('day', now()),
    use_bonus boolean default false
) returns public.nukes
language plpgsql security definer set search_path = public as $$
declare
    nuke public.nukes;
begin
    if not are_friends(auth.uid(), target_id) then raise exception 'Not your friend'; end if;
    -- Keep a made-up day_start from giving more than a day's worth of nukes.
    if day_start < now() - interval '1 day' or day_start > now() then
        day_start := date_trunc('day', now());
    end if;

    if use_bonus then
        if exists (select 1 from nukes
                   where sender = auth.uid() and target = target_id and nukes.bonus and created_at >= day_start) then
            raise exception 'Secret nuke already used today';
        end if;
    elsif (select count(*) from nukes
           where sender = auth.uid() and target = target_id and not nukes.bonus and created_at >= day_start) >= 3 then
        raise exception 'Out of nukes for today';
    end if;

    insert into nukes (sender, target, bonus) values (auth.uid(), target_id, use_bonus) returning * into nuke;
    return nuke;
end;
$$;
