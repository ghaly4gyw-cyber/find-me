<?php
/**
 * Ensures the registrations table (and any columns later features need)
 * exists — called by every endpoint that touches it, so there's no separate
 * migration step to remember to run on deploy.
 */
function ensureSchema(PDO $pdo): void
{
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS registrations (
            id INT AUTO_INCREMENT PRIMARY KEY,
            device_id VARCHAR(64) NOT NULL UNIQUE,
            phone VARCHAR(32) NOT NULL UNIQUE,
            name VARCHAR(100) NOT NULL,
            last_lat DOUBLE NULL,
            last_lng DOUBLE NULL,
            last_location_at TIMESTAMP NULL,
            description VARCHAR(500) NULL,
            photo_base64 LONGTEXT NULL,
            blocked TINYINT(1) NOT NULL DEFAULT 0,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Kept separate from the CREATE TABLE above so a table created before
    // these features existed still picks up the new columns. Each is safe
    // to run every time — IF NOT EXISTS makes a repeat run a no-op.
    foreach ([
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_lat DOUBLE NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_lng DOUBLE NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMP NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS description VARCHAR(500) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS photo_base64 LONGTEXT NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS blocked TINYINT(1) NOT NULL DEFAULT 0",
    ] as $sql) {
        try {
            $pdo->exec($sql);
        } catch (PDOException $e) {
            // Older MySQL/MariaDB without "ADD COLUMN IF NOT EXISTS" support
            // would throw here instead of no-op'ing — harmless either way
            // for a fresh install, since CREATE TABLE above already includes
            // these columns.
        }
    }

    // One row per device, upserted on every usage sync — cumulative totals,
    // not a log. feature_usage is a small JSON object: {"<ScreenKey>":
    // {"opens": n, "ms": n}, ...}, aggregated across all devices by the
    // admin dashboard to show which parts of the app actually get used.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS usage_stats (
            device_id VARCHAR(64) NOT NULL PRIMARY KEY,
            app_opens BIGINT NOT NULL DEFAULT 0,
            total_ms BIGINT NOT NULL DEFAULT 0,
            feature_usage JSON NULL,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Append-only — every suggestion a person sends is kept, not overwritten,
    // so the admin dashboard can show the running list of what people asked
    // for.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS feedback (
            id INT AUTO_INCREMENT PRIMARY KEY,
            device_id VARCHAR(64) NOT NULL,
            name VARCHAR(100) NULL,
            message VARCHAR(1000) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Single-row table (id always 1) holding the admin-editable remote
    // control settings — replaces the old "edit control.json and push to
    // GitHub" workflow with a form on the dashboard. message_id is
    // regenerated (see admin/control-save.php) every time the one-time
    // message text actually changes, which is what makes it show again on
    // devices that already saw an earlier one. min_supported_version = 0
    // means no forced update is in effect.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS app_control (
            id INT PRIMARY KEY,
            message_id VARCHAR(64) NULL,
            message_title VARCHAR(200) NULL,
            message_body VARCHAR(1000) NULL,
            min_supported_version BIGINT NOT NULL DEFAULT 0,
            min_supported_message VARCHAR(500) NULL,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
    $pdo->exec(
        "INSERT INTO app_control (id, min_supported_version) VALUES (1, 0)
         ON DUPLICATE KEY UPDATE id = id"
    );
}

/** Shared by every endpoint that needs the database — one place to change
 *  the connection logic. Throws PDOException on failure; callers decide how
 *  to report that. */
function connectDb(array $config): PDO
{
    return new PDO(
        "mysql:host={$config['host']};dbname={$config['name']};charset=utf8mb4",
        $config['user'],
        $config['password'],
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION]
    );
}
