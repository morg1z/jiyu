-- Explicitní Data API granty na existujících tabulkách.
--
-- Kontext: Supabase od 2026-10-30 prestane automaticky davat API granty novym
-- tabulkam v public schema. Existujici tabulky zustanou beze zmeny (maji granty
-- z doby auto-grantu), takze tahle migrace je na zivem projektu prakticky no-op -
-- ale zaruci, ze rebuild (novy projekt, preview branch, `supabase db reset`)
-- dostane stejne opravneni jako dnesni stav, a dokumentuje je verzi.
--
-- GRANT je idempotentni - bezpecne se spusti i na projektu, kde granty uz jsou.
grant select, insert, update, delete on public.manga_sync          to authenticated;
grant select, insert, update, delete on public.chapter_sync        to authenticated;
grant select, insert, update, delete on public.user_settings_sync  to authenticated;
grant select, insert, update, delete on public.library_backups     to authenticated;
grant select, insert, update, delete on public.public_manga_lists  to authenticated;
grant select                       on public.public_manga_lists    to anon;

-- translate_usage zustava BEZ klientskych grantu - pristupuje se k ni jen pres
-- SECURITY DEFINER funkce pod service_role (viz schema.sql).
--
-- Konvence dal: kazda migrace vytvarejici tabulku v public MUSI obsahovat
-- jeji explicitni GRANT (viz mail Supabase "Data API grants change" 2026-10-20).
