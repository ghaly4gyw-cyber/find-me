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
            device_model VARCHAR(150) NULL,
            android_version VARCHAR(50) NULL,
            app_version VARCHAR(50) NULL,
            network_type VARCHAR(20) NULL,
            network_name VARCHAR(100) NULL,
            pin_hash VARCHAR(255) NULL,
            reclaim_attempts INT NOT NULL DEFAULT 0,
            reclaim_locked_until TIMESTAMP NULL,
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
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS device_model VARCHAR(150) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS android_version VARCHAR(50) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS app_version VARCHAR(50) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS network_type VARCHAR(20) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS network_name VARCHAR(100) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS pin_hash VARCHAR(255) NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS reclaim_attempts INT NOT NULL DEFAULT 0",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS reclaim_locked_until TIMESTAMP NULL",
        // Bundled backup of this account's local-only data (accounts ledger,
        // notes, saved places) — see backup-upload.php/BackupUploader.kt and
        // reclaim.php. Lets "their data should be on the host and auto go
        // with them" survive a lost/broken/reinstalled phone, not just the
        // name+phone profile row itself.
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS backup_json LONGTEXT NULL",
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS backup_updated_at TIMESTAMP NULL",
        // Short shareable code ("add a contact by their code") so two miners
        // who aren't near each other can still reach each other online — see
        // generateContactCode() below and add-contact.php.
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS contact_code VARCHAR(10) NULL UNIQUE",
        // A lightweight "I'm online right now" heartbeat (heartbeat.php),
        // deliberately separate from last_location_at — online presence
        // shouldn't require sharing location. contacts-status.php reports a
        // phone as online if this is recent enough.
        "ALTER TABLE registrations ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP NULL",
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

    // Places a miner explicitly marked "share with team" at save time —
    // uploaded here (see public-place-upload.php / PublicPlaceUploader.kt) so
    // they show up on the dashboard even for a site with no mesh connection
    // to any other, and so one site's known hazards/water points/landmarks
    // can be seen across the whole project, not just by whoever's in direct
    // range. place_id is the app's own stable id for the place (fixed since
    // the moment it was first shared), so a phone re-uploading the same
    // place after a rename just updates this one row instead of duplicating
    // it.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS public_places (
            place_id VARCHAR(64) PRIMARY KEY,
            device_id VARCHAR(64) NOT NULL,
            name VARCHAR(100) NOT NULL,
            lat DOUBLE NOT NULL,
            lon DOUBLE NOT NULL,
            shared_by_name VARCHAR(100) NULL,
            place_ts BIGINT NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Who each account added by contact code (add-contact.php) — one-directional
    // (like a phonebook entry), not a mutual friend request. Lets two miners who
    // aren't physically near each other stay reachable online by text once one
    // of them has entered the other's code.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS contacts (
            id INT AUTO_INCREMENT PRIMARY KEY,
            owner_phone VARCHAR(32) NOT NULL,
            contact_phone VARCHAR(32) NOT NULL,
            added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            UNIQUE KEY owner_contact (owner_phone, contact_phone)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );

    // Online text messages between two accounts — separate from the mesh's own
    // offline Bluetooth/Wi-Fi chat, which never touches the server at all. Only
    // reached when both the sender (to send) and recipient (to fetch) have
    // internet, per "when they are online they can do more stuff". Polled by
    // the app (fetch-messages.php), not pushed — no notification service here.
    $pdo->exec(
        "CREATE TABLE IF NOT EXISTS messages (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            from_phone VARCHAR(32) NOT NULL,
            to_phone VARCHAR(32) NOT NULL,
            body VARCHAR(2000) NOT NULL,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_to_phone_id (to_phone, id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
    );
}

/** Generates a short, human-typeable contact code (6 characters, no 0/O/1/I
 *  to avoid mix-ups when someone reads it aloud or copies it by hand) and
 *  retries on the astronomically unlikely chance of a collision. Called once
 *  per account, the first time register.php sees it doesn't have one yet. */
function generateContactCode(PDO $pdo): string
{
    $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    for ($attempt = 0; $attempt < 20; $attempt++) {
        $code = '';
        for ($i = 0; $i < 6; $i++) {
            $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
        }
        $check = $pdo->prepare('SELECT 1 FROM registrations WHERE contact_code = ?');
        $check->execute([$code]);
        if ($check->fetchColumn() === false) {
            return $code;
        }
    }
    // Never realistically reached (32^6 ≈ 1 billion combinations), but return
    // something rather than loop forever.
    return strtoupper(bin2hex(random_bytes(4)));
}

/** Shared "does this device currently own this phone number" check, used by
 *  every online-contacts/messaging endpoint. On failure it writes the
 *  matching HTTP status + JSON error itself (404 if the phone isn't
 *  registered at all, 403 if a different device owns it now), so a caller
 *  just does: if (!requireOwnerDevice($pdo, $phone, $deviceId)) exit; */
function requireOwnerDevice(PDO $pdo, string $phone, string $deviceId): bool
{
    $stmt = $pdo->prepare('SELECT device_id FROM registrations WHERE phone = ?');
    $stmt->execute([$phone]);
    $currentDeviceId = $stmt->fetchColumn();
    if ($currentDeviceId === false) {
        http_response_code(404);
        echo json_encode(['status' => 'error', 'reason' => 'not_registered']);
        return false;
    }
    if ($currentDeviceId !== $deviceId) {
        http_response_code(403);
        echo json_encode(['status' => 'error', 'reason' => 'not_the_registered_device']);
        return false;
    }
    return true;
}

/** Mauritanian mobile numbers only — mirrors PhoneValidator.kt on the app
 *  side. Exactly 8 digits, starting with 2 (Mattel), 3 (Mauritel) or 4
 *  (Chinguitel). Checked server-side too since register.php and friends are
 *  public endpoints the app isn't the only possible caller of. */
function isValidMauritanianPhone(string $phone): bool
{
    return (bool) preg_match('/^[234][0-9]{7}$/', $phone);
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
