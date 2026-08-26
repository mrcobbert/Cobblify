CREATE TABLE IF NOT EXISTS launcher_update_events (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  occurred_at INTEGER NOT NULL,
  event TEXT NOT NULL CHECK (event IN (
    'check_ok',
    'check_failed',
    'download_started',
    'download_paused',
    'download_verified',
    'install_started',
    'post_update_started'
  )),
  current_version TEXT NOT NULL,
  target_version TEXT,
  platform TEXT NOT NULL,
  arch TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS launcher_update_events_occurred_at
  ON launcher_update_events (occurred_at);
