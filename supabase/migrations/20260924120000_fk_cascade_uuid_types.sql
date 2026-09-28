-- Audit 2026-09-24: dvě opravy konzistence schématu.
--
-- 1) FK user_id -> auth.users(id) neměly ON DELETE CASCADE. Výchozí NO ACTION znamená, že
--    smazání uživatele (dashboard, případný budoucí "smazat účet" flow, GDPR vyřízení) skončí
--    chybou, dokud ručně nejdou pryč jeho řádky v manga_sync/chapter_sync/user_settings_sync.
--    S kaskádou se smažou atomicky s účtem - žádné osiřelé řádky.
--
-- 2) library_backups.user_id a public_manga_lists.user_id byly TEXT bez FK - jediné tabulky,
--    kde se identita uživatele držela jako volný řetězec (žádná kontrola, žádná kaskáda).
--    Obě tabulky jsou prázdné (appka je zatím nepoužívá), převod na UUID + FK je bezpečný.
--    POZOR: jejich politiky porovnávaly TEXT sloupec s auth.uid()::text - po převodu na UUID
--    se musí přesměrovat na uuid-vs-uuid, jinak dotazy spadnou na "operator does not exist".

-- ── 1) ON DELETE CASCADE ─────────────────────────────────────────────────────
alter table public.manga_sync
  drop constraint manga_sync_user_id_fkey,
  add constraint manga_sync_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

alter table public.chapter_sync
  drop constraint chapter_sync_user_id_fkey,
  add constraint chapter_sync_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

alter table public.user_settings_sync
  drop constraint user_settings_sync_user_id_fkey,
  add constraint user_settings_sync_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

-- ── 2) TEXT -> UUID + FK + opravené politiky ────────────────────────────────
-- Politiky se musí dropnout PŘED změnou typu - Postgres odmítne ALTER COLUMN TYPE,
-- když na sloupci visí politika (SQLSTATE 0A000).
drop policy if exists "Users can manage own backups" on public.library_backups;
drop policy if exists "Public or own lists are readable" on public.public_manga_lists;
drop policy if exists "Users insert own entries" on public.public_manga_lists;
drop policy if exists "Users update own entries" on public.public_manga_lists;
drop policy if exists "Users delete own entries" on public.public_manga_lists;

alter table public.library_backups
  alter column user_id type uuid using nullif(user_id, '')::uuid,
  add constraint library_backups_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

create policy "Users can manage own backups" on public.library_backups
  for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

alter table public.public_manga_lists
  alter column user_id type uuid using nullif(user_id, '')::uuid,
  add constraint public_manga_lists_user_id_fkey
    foreign key (user_id) references auth.users(id) on delete cascade;

create policy "Public or own lists are readable" on public.public_manga_lists
  for select to anon, authenticated
  using (is_public = true or user_id = (select auth.uid()));
create policy "Users insert own entries" on public.public_manga_lists
  for insert to authenticated with check (user_id = (select auth.uid()));
create policy "Users update own entries" on public.public_manga_lists
  for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));
create policy "Users delete own entries" on public.public_manga_lists
  for delete to authenticated using (user_id = (select auth.uid()));
