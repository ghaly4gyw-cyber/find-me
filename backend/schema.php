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
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Kept separate from the CREATE TABLE above so a table created before
    // the location feature existed still picks up these columns. Each is
    // safe to run every time — IF NOT EXISTS makes a repeat run a no-op.
    foreach ([
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_lat DOUBLE NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_lng DOUBLE NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_location_at TIMESTAMP NULL",
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
