-- Jiyū Cloud Schema - AKTUÁLNÍ STAV živého projektu (Supabase "jiyu"), stav k 2026-09-20 po migraci
-- 20260920134343_harden_rls_indexes_grants.
--
-- Tenhle soubor je souhrn stavu, ne postup: nové změny se dělají jako migrace ve složce supabase/migrations
-- (a aplikují se na projekt), tenhle přehled se pak aktualizuje. Dřív se v něm popisovala tabulka `profiles` a
-- trigger `handle_new_user`, které v živé databázi vůbec neexistují, a chyběly tabulky, které existují -
-- při obnově projektu z něj by vznikla jiná databáze.
--
-- Skutečně používané appkou: manga_sync, chapter_sync (synchronizace knihovny) a translate_usage (denní strop
-- překladové proxy). library_backups, public_manga_lists a user_settings_sync appka zatím nepoužívá (prázdné).

-- ── manga_sync ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.manga_sync (
  id          TEXT    NOT NULL,
  user_id     UUID    NOT NULL REFERENCES auth.users(id),
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
  user_id         UUID    NOT NULL REFERENCES auth.users(id),
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
  user_id       UUID   PRIMARY KEY REFERENCES auth.users(id),
  settings_json JSONB  NOT NULL DEFAULT '{}'::jsonb,
  updated_at    BIGINT NOT NULL DEFAULT 0
);
ALTER TABLE public.user_settings_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users manage own settings" ON public.user_settings_sync
  FOR ALL TO authenticated
  USING ((select auth.uid()) = user_id) WITH CHECK ((select auth.uid()) = user_id);

-- ── library_backups (zatím nepoužito; user_id je TEXT bez cizího klíče) ───────
CREATE TABLE IF NOT EXISTS public.library_backups (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT        NOT NULL,
  backup_data JSONB       NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT now()
);
ALTER TABLE public.library_backups ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own backups" ON public.library_backups
  FOR ALL TO authenticated
  USING (user_id = (select auth.uid())::text) WITH CHECK (user_id = (select auth.uid())::text);

-- ── public_manga_lists (zatím nepoužito; user_id je TEXT bez cizího klíče) ────
CREATE TABLE IF NOT EXISTS public.public_manga_lists (
  id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT        NOT NULL,
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
  USING (is_public = true OR user_id = (select auth.uid())::text);
CREATE POLICY "Users insert own entries" ON public.public_manga_lists
  FOR INSERT TO authenticated WITH CHECK (user_id = (select auth.uid())::text);
CREATE POLICY "Users update own entries" ON public.public_manga_lists
  FOR UPDATE TO authenticated
  USING (user_id = (select auth.uid())::text) WITH CHECK (user_id = (select auth.uid())::text);
CREATE POLICY "Users delete own entries" ON public.public_manga_lists
  FOR DELETE TO authenticated USING (user_id = (select auth.uid())::text);

-- ── translate_usage ──────────────────────────────────────────────────────────
-- Denní strop pro překladovou proxy (supabase/functions/translate-proxy/index.ts).
-- STROP JE ZÁMĚRNĚ GLOBÁLNÍ (jedna řádka na den za celý projekt), ne per-uživatel: appka je osobní a proxy běží
-- s verify_jwt=false. Kdokoli, kdo z APK vytáhne URL + anon key, může strop vyčerpat; kdyby appku používal někdo
-- další, je tohle první místo k předělání (identifikátor volajícího do klíče).
-- K tabulce smí jen service role (edge funkce): klientským rolím jsou odebrána práva a politika "No client access"
-- je navíc výslovně zakazuje.
CREATE TABLE IF NOT EXISTS public.translate_usage (
  day           DATE PRIMARY KEY,
  request_count INTEGER NOT NULL DEFAULT 0,
  char_count    BIGINT  NOT NULL DEFAULT 0
);
ALTER TABLE public.translate_usage ENABLE ROW LEVEL SECURITY;
CREATE POLICY "No client access" ON public.translate_usage
  FOR ALL TO anon, authenticated USING (false) WITH CHECK (false);

-- Atomicky započítá jeden požadavek a vrátí, jestli se ještě vejde do denního stropu. Limity chodí jako parametry
-- (rozhoduje se o nich v index.ts).
CREATE OR REPLACE FUNCTION public.increment_translate_usage(
  p_chars                INTEGER,
  p_daily_char_limit     BIGINT,
  p_daily_request_limit  INTEGER
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
declare
  v_count integer;
  v_chars bigint;
begin
  insert into public.translate_usage (day, request_count, char_count)
  values (current_date, 1, p_chars)
  on conflict (day) do update
    set request_count = translate_usage.request_count + 1,
        char_count = translate_usage.char_count + excluded.char_count
  returning request_count, char_count into v_count, v_chars;

  return v_count <= p_daily_request_limit and v_chars <= p_daily_char_limit;
end;
$function$;

-- Vrátí do denního ZNAKOVÉHO stropu znaky za pokus, při kterém upstream nic nevygeneroval (viz refundQuota v
-- index.ts). Počet požadavků se ZÁMĚRNĚ nevrací: je to pojistka proti rozjeté smyčce a ta se skládá z neúspěšných
-- pokusů. `greatest(0, ...)` hlídá pokus, který začne před půlnocí a vrátí se po ní.
CREATE OR REPLACE FUNCTION public.refund_translate_usage(p_chars INTEGER)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path TO 'public'
AS $function$
begin
  update public.translate_usage
     set char_count = greatest(0, char_count - p_chars)
   where day = current_date;
end;
$function$;

REVOKE EXECUTE ON FUNCTION public.increment_translate_usage(INTEGER, BIGINT, INTEGER) FROM PUBLIC, anon, authenticated;
REVOKE EXECUTE ON FUNCTION public.refund_translate_usage(INTEGER) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.increment_translate_usage(INTEGER, BIGINT, INTEGER) TO service_role;
GRANT EXECUTE ON FUNCTION public.refund_translate_usage(INTEGER) TO service_role;

-- ── Oprávnění tabulek (viz migrace 20260920134343) ───────────────────────────
-- anon: jen SELECT na public_manga_lists (RLS pouští jen veřejné řádky). authenticated: SELECT/INSERT/UPDATE/DELETE
-- (řádky omezuje RLS), bez TRUNCATE/TRIGGER/REFERENCES. translate_usage: jen service role.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon;
GRANT SELECT ON public.public_manga_lists TO anon;
REVOKE TRUNCATE, TRIGGER, REFERENCES ON ALL TABLES IN SCHEMA public FROM authenticated;
REVOKE ALL ON public.translate_usage FROM anon, authenticated;
