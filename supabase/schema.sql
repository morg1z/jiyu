-- Jiyu Cloud Schema
-- Spusť v: Supabase Dashboard → SQL Editor

-- ── profiles ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS profiles (
  id           UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
  username     TEXT UNIQUE,
  display_name TEXT,
  avatar_url   TEXT,
  public_library BOOLEAN DEFAULT FALSE,
  created_at   TIMESTAMPTZ DEFAULT NOW(),
  updated_at   TIMESTAMPTZ DEFAULT NOW()
);

-- Auto-vytvoření profilu při registraci
CREATE OR REPLACE FUNCTION handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO profiles (id, display_name, avatar_url)
  VALUES (
    NEW.id,
    NEW.raw_user_meta_data->>'full_name',
    NEW.raw_user_meta_data->>'avatar_url'
  );
  RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
  AFTER INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION handle_new_user();

ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can view own profile" ON profiles FOR SELECT USING (auth.uid() = id);
CREATE POLICY "Users can update own profile" ON profiles FOR UPDATE USING (auth.uid() = id);

-- ── manga_sync ───────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS manga_sync (
  id          TEXT NOT NULL,
  user_id     UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  source_id   TEXT NOT NULL,
  url         TEXT NOT NULL,
  title       TEXT NOT NULL,
  cover_url   TEXT,
  in_library  BOOLEAN NOT NULL DEFAULT TRUE,
  last_read_chapter_id TEXT,
  last_read_at BIGINT DEFAULT 0,
  updated_at  BIGINT NOT NULL,
  PRIMARY KEY (id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_manga_sync_user ON manga_sync(user_id);

ALTER TABLE manga_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own manga_sync" ON manga_sync
  USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);

-- ── chapter_sync ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS chapter_sync (
  id              TEXT NOT NULL,
  user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
  manga_id        TEXT NOT NULL,
  read            BOOLEAN NOT NULL DEFAULT FALSE,
  last_page_read  INTEGER NOT NULL DEFAULT 0,
  updated_at      BIGINT NOT NULL,
  PRIMARY KEY (id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_chapter_sync_user ON chapter_sync(user_id);
CREATE INDEX IF NOT EXISTS idx_chapter_sync_manga ON chapter_sync(manga_id);

ALTER TABLE chapter_sync ENABLE ROW LEVEL SECURITY;
CREATE POLICY "Users can manage own chapter_sync" ON chapter_sync
  USING (auth.uid() = user_id) WITH CHECK (auth.uid() = user_id);
