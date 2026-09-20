-- Aplikováno na projekt jiyu jako migrace 20260920134343_harden_rls_indexes_grants.
-- Indexy pro dotazy synchronizace (filtr podle user_id, případně přírůstkově podle updated_at).
create index if not exists idx_manga_sync_user_updated   on public.manga_sync   (user_id, updated_at);
create index if not exists idx_chapter_sync_user_updated on public.chapter_sync (user_id, updated_at);

-- RLS: (select auth.uid()) se vyhodnotí jednou na dotaz, ne pro každý řádek; politiky jen pro přihlášené;
-- výslovné WITH CHECK.
drop policy if exists "Users manage own manga_sync" on public.manga_sync;
create policy "Users manage own manga_sync" on public.manga_sync
  for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "Users manage own chapter_sync" on public.chapter_sync;
create policy "Users manage own chapter_sync" on public.chapter_sync
  for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "Users manage own settings" on public.user_settings_sync;
create policy "Users manage own settings" on public.user_settings_sync
  for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "Users can manage own backups" on public.library_backups;
create policy "Users can manage own backups" on public.library_backups
  for all to authenticated
  using (user_id = (select auth.uid())::text) with check (user_id = (select auth.uid())::text);

-- public_manga_lists: jedna SELECT politika (veřejné + vlastní) místo dvou překrývajících se, zápis jen vlastník.
drop policy if exists "Public lists are viewable by everyone" on public.public_manga_lists;
drop policy if exists "Users can manage own entries" on public.public_manga_lists;
create policy "Public or own lists are readable" on public.public_manga_lists
  for select to anon, authenticated
  using (is_public = true or user_id = (select auth.uid())::text);
create policy "Users insert own entries" on public.public_manga_lists
  for insert to authenticated with check (user_id = (select auth.uid())::text);
create policy "Users update own entries" on public.public_manga_lists
  for update to authenticated
  using (user_id = (select auth.uid())::text) with check (user_id = (select auth.uid())::text);
create policy "Users delete own entries" on public.public_manga_lists
  for delete to authenticated using (user_id = (select auth.uid())::text);

-- translate_usage: k tabulce smí jen service role (edge funkce); výslovná politika "nikdo jiný".
create policy "No client access" on public.translate_usage
  for all to anon, authenticated using (false) with check (false);

-- Oprávnění: anon nepotřebuje nic kromě čtení veřejných seznamů; přihlášení nepotřebují TRUNCATE/TRIGGER/REFERENCES.
revoke all on all tables in schema public from anon;
grant select on public.public_manga_lists to anon;
revoke truncate, trigger, references on all tables in schema public from authenticated;
revoke all on public.translate_usage from anon, authenticated;
-- Nové tabulky v public už nedostanou automaticky práva pro anon.
alter default privileges for role postgres in schema public revoke all on tables from anon;
