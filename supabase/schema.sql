-- Jiyū Cloud Schema - AKTUÁLNÍ STAV živého projektu (Supabase "jiyu"), stav k 2026-09-24 po migracích
-- 20260920134343_harden_rls_indexes_grants a 20260924120000_fk_cascade_uuid_types (FK mají ON DELETE
-- CASCADE, user_id je všude UUID).
--
-- Tenhle soubor je souhrn stavu, ne postup: nové změny se dělají jako migrace ve složce supabase/migrations
-- (a aplikují se na projekt), tenhle přehled se pak aktualizuje. Dřív se v něm popisovala tabulka `profiles` a
-- trigger `handle_new_user`, které v živé databázi vůbec neexistují, a chyběly tabulky, které existují -
-- při obnově projektu z něj by vznikla jiná databáze.
--
-- Skutečně používané appkou: manga_sync, chapter_sync (synchronizace knihovny).
-- library_backups, public_manga_lists a user_settings_sync appka zatím nepoužívá (prázdné).

-- ── manga_sync ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.manga_sync (
  id          TEXT    NOT NULL,
  user_id     UUID    NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  source_id   TEXT    NOT NULL,
  url         TEXT    NOT NULL,
  title       TEXT    NOT NULL,
  cover_url   TEXT,
  in_library  BOOLEAN NOT NULL DEFAULT FALSE,
  updated_at  BIGINT  NOT NULL,
  PRIMARY KEY (id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_manga_sync_user_updated ON public.manga_sync (user_id, updated_at);
ALTER TABLE public.manga_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own manga_sync" ON public.manga_sync
  FOR ALL TO authenticated
  USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id);

-- ── chapter_sync ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.chapter_sync (
  id              TEXT    NOT NULL,
  user_id         UUID    NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  manga_id        TEXT    NOT NULL,
  read            BOOLEAN NOT NULL DEFAULT FALSE,
  last_page_read  INTEGER NOT NULL DEFAULT 0,
  updated_at      BIGINT  NOT NULL,
  PRIMARY KEY (id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_chapter_sync_user_updated ON public.chapter_sync (user_id, updated_at);
ALTER TABLE public.chapter_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own chapter_sync" ON public.chapter_sync
  FOR ALL TO authenticated
  USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id);

-- ── user_settings_sync (zatím nepoužito) ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.user_settings_sync (
  user_id       UUID   PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  settings_json JSONB  NOT NULL DEFAULT '{}'::jsonb,
  updated_at    BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE public.user_settings_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own settings" ON public.user_settings_sync
  FOR ALL TO authenticated
  USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id);

-- ── library_backups (zatím nepoužito) ─────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.library_backups (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  backup_data JSONB       NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT now()
);
ALTER TABLE public.library_backups ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own backups" ON public.library_backups
  FOR ALL TO authenticated
  USING (user_id = (select auth.uid())) WITH CHECK (user_id = (select auth.uid()));

-- ── public_manga_lists (zatím nepoužito) ──────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.public_manga_lists (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID        NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  manga_id    TEXT        NOT NULL,
  manga_title TEXT        NOT NULL,
  manga_cover TEXT,
  is_public   BOOLEAN     DEFAULT FALSE,
  created_at  TIMESTAMPTZ DEFAULT now(),
  UNIQUE (user_id, manga_id)
);
ALTER TABLE public.public_manga_lists ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Public or own lists are readable" ON public.public_manga_lists
  FOR SELECT TO anon, authenticated
  USING (is_public = true OR user_id = (select auth.uid()));
CREATE POLICY "Users insert own entries" ON public.public_manga_lists
  FOR INSERT TO authenticated WITH CHECK (user_id = (select auth.uid()));
CREATE POLICY "Users update own entries" ON public.public_manga_lists
  FOR UPDATE TO authenticated
  USING (user_id = (select auth.uid())) WITH CHECK (user_id = (select auth.uid()));
CREATE POLICY "Users delete own entries" ON public.public_manga_lists
  FOR DELETE TO authenticated USING (user_id = (select auth.uid()));

-- ── Oprávnění tabulek (viz migrace 20260920134343) ───────────────────────────
-- anon: jen SELECT na public_manga_lists (RLS pouští jen veřejné řádky). authenticated: SELECT/INSERT/UPDATE/DELETE
-- (řádky omezuje RLS), bez TRUNCATE/TRIGGER/REFERENCES.
--
-- POZOR (Supabase změna od 2026-10-30): nové tabulky v public NEDOSTÁVAJÍ Data API
-- granty automaticky. Každá nová tabulka i rebuild projektu/preview/local reset
-- MUSÍ dostat explicitní GRANT tady - bez něj vrací PostgREST "permission denied"
-- i přihlášeným uživatelům (RLS pak nepomůže, grant chybí pod ním).
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon;
GRANT SELECT ON public.public_manga_lists TO anon;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.manga_sync TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.chapter_sync TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.user_settings_sync TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.library_backups TO authenticated;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.public_manga_lists TO authenticated;
REVOKE TRUNCATE, TRIGGER, REFERENCES ON ALL TABLES IN SCHEMA public FROM authenticated;
