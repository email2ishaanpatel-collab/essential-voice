-- Card likes.
--
-- Run this once, whole, in the Supabase SQL editor. It is written to be safe to
-- run again: every statement is create-if-not-exists or create-or-replace.
--
-- The shape is deliberate. The app ships with the *anon* key baked into it, and
-- an anon key is public by definition — it is in the APK, and anyone who wants
-- it has it. So nothing here trusts the client. The table has row level security
-- on and **no policies at all**, which means the anon role cannot read it, write
-- it, or count it. The only two things anon can do are call the two functions
-- below, and those decide for themselves what a caller is allowed to change.
--
-- What a determined person can still do is mint device ids and like a card more
-- than once. That is the cost of having no accounts, and having no accounts is
-- the point: this is a like button on a settings screen, not a ballot. See the
-- note at the foot for what to do if it is ever actually abused.

create table if not exists public.card_likes (
    -- The card's name from the Page enum in Home.kt: 'SetUp', 'Pill', 'Volume'.
    -- Not the [01] in the corner — that number moves when a feature flag turns a
    -- card off, and a like that follows the number would follow it onto a
    -- different card.
    card text not null,

    -- One install, generated on the phone and stored in SharedPreferences.
    -- Random, and tied to nothing: not the account, not the hardware, not the
    -- advertising id. Clearing the app's data makes a new one, which is the
    -- right behaviour — it is a "have I already liked this" token, not an
    -- identity.
    device uuid not null,

    created_at timestamptz not null default now(),

    -- One like per card per install, enforced by the database rather than by
    -- the app. The client is the part that cannot be trusted to remember.
    primary key (card, device)
);

alter table public.card_likes enable row level security;

-- No policies, on purpose. See the header.
revoke all on public.card_likes from anon, authenticated;

-- Counting a card means reading every row for it, so the index the primary key
-- gives us (card first) is already the one that matters. Nothing else needed.

-- ---------------------------------------------------------------------------
-- What the app reads: every card's total, plus whether this install is in it.
--
-- One call rather than two, because the screen needs both facts at once and a
-- heart drawn full before the count arrives is a heart that flickers.
--
-- `security definer` is what lets it read a table anon cannot. `set search_path`
-- pins the schema so the function cannot be redirected by a caller's own
-- search_path — the standard hardening for a definer function, and the reason
-- both functions below fully qualify every name.
create or replace function public.like_counts(p_device uuid)
returns table (card text, likes bigint, liked boolean)
language sql
stable
security definer
set search_path = public
as $$
    select l.card,
           count(*)::bigint as likes,
           bool_or(l.device = p_device) as liked
      from public.card_likes l
     group by l.card;
$$;

-- ---------------------------------------------------------------------------
-- What the app writes: like or unlike one card, and get the new total back.
--
-- Returning the count means the tap is one round trip, not a write followed by
-- a re-read — and the number that lands on screen is the real one rather than
-- the app's guess at it.
create or replace function public.set_like(p_card text, p_device uuid, p_liked boolean)
returns bigint
language plpgsql
volatile
security definer
set search_path = public
as $$
begin
    -- The card name is the one thing a caller chooses freely, so it is checked
    -- here. Without this, the table fills with whatever anyone felt like posting
    -- and the counts still look fine, because nothing reads the junk rows.
    if p_card is null or p_card !~ '^[A-Za-z]{2,24}$' then
        raise exception 'unknown card';
    end if;

    if p_liked then
        insert into public.card_likes (card, device)
        values (p_card, p_device)
        on conflict (card, device) do nothing;
    else
        delete from public.card_likes
         where card = p_card and device = p_device;
    end if;

    return (select count(*) from public.card_likes where card = p_card);
end;
$$;

-- ---------------------------------------------------------------------------
-- Grants. `public` includes every role, so the revoke comes first and the two
-- grants are then the whole of what anon can do in this schema.
revoke all on function public.like_counts(uuid) from public;
revoke all on function public.set_like(text, uuid, boolean) from public;

grant execute on function public.like_counts(uuid) to anon;
grant execute on function public.set_like(text, uuid, boolean) to anon;

-- ---------------------------------------------------------------------------
-- If it is ever abused.
--
-- Supabase has per-project rate limiting in the dashboard, which is the first
-- and cheapest lever. After that, the honest fix is a cap inside set_like — a
-- count of rows created by one device in the last hour, refused past some
-- number — or Supabase's anonymous sign-in, which gives every install a real
-- JWT and makes the device column a foreign key to auth.users. Neither is worth
-- writing before there is something to point at.
