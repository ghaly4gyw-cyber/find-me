<?php
/**
 * Find Me — registration upload endpoint.
 *
 * Receives {name, phone, device_id, description?, photo_base64?} from the
 * app whenever it's online and
 * enforces "one phone number can only be bound to one device" server-side —
 * the app itself can only check this once a server exists, which is exactly
 * what this does. Registering stays fully usable offline in the app; this
 * endpoint only gets called opportunistically to sync that registration up.
 *
 * Deliberately minimal: no admin UI, no auth beyond what's here. db-config.php
 * (real credentials) is generated at deploy time from db-config.template.php
 * by GitHub Actions and is NOT part of this public repo — see that workflow.
 */

header('Content-Type: application/json');

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'reason' => 'method_not_allowed']);
    exit;
}

$raw = file_get_contents('php://input');
$body = json_decode($raw, true);
if (!is_array($body)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'invalid_json']);
    exit;
}

$name = isset($body['name']) ? trim((string) $body['name']) : '';
$phone = isset($body['phone']) ? trim((string) $body['phone']) : '';
$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';
// Both optional — the app's registration screen only requires name + phone.
$description = isset($body['description']) ? trim((string) $body['description']) : null;
$photoBase64 = isset($body['photo_base64']) ? trim((string) $body['photo_base64']) : null;
if ($description === '') $description = null;
if ($photoBase64 === '') $photoBase64 = null;

// Keep these generous but bounded — this is a phone number and a person's
// name, not free text, and the columns below are sized to match. The photo
// is already a small resized/compressed thumbnail by the time the app sends
// it (see MainActivity.handlePickedPhoto), so 200,000 base64 characters
// (~150KB raw) is a generous ceiling, not a real-world ask.
require __DIR__ . '/schema.php';

if ($name === '' || $phone === '' || $deviceId === ''
    || mb_strlen($name) > 100 || mb_strlen($deviceId) > 64
    || !isValidMauritanianPhone($phone)
    || ($description !== null && mb_strlen($description) > 500)
    || ($photoBase64 !== null && strlen($photoBase64) > 200_000)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

$configPath = __DIR__ . '/db-config.php';
if (!file_exists($configPath)) {
    http_response_code(500);
    echo json_encode(['status' => 'error', 'reason' => 'server_not_configured']);
    exit;
}
$config = require $configPath;

try {
    $pdo = connectDb($config);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(['status' => 'error', 'reason' => 'db_connection_failed']);
    exit;
}

ensureSchema($pdo);

// Is this phone number already registered to a DIFFERENT device? That's the
// one case this whole endpoint exists to catch — "same account on two
// phones" gets rejected here, server-side, which is the part the app alone
// can never enforce on its own.
$stmt = $pdo->prepare('SELECT device_id FROM registrations WHERE phone = ?');
$stmt->execute([$phone]);
$existing = $stmt->fetchColumn();

if ($existing !== false && $existing !== $deviceId) {
    http_response_code(409);
    echo json_encode(['status' => 'rejected', 'reason' => 'phone_already_registered_elsewhere']);
    exit;
}

// Upsert by device_id: a device re-syncing (app reopened online later, or a
// retry after a dropped connection) just refreshes its own row. description/
// photo_base64 only overwrite when actually sent (COALESCE keeps the
// existing value on an update that doesn't include them), so re-syncing name
// and phone alone never wipes out a photo already on file.
$stmt = $pdo->prepare(
    'INSERT INTO registrations (device_id, phone, name, description, photo_base64)
     VALUES (?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
        phone = VALUES(phone),
        name = VALUES(name),
        description = COALESCE(VALUES(description), description),
        photo_base64 = COALESCE(VALUES(photo_base64), photo_base64)'
);
$stmt->execute([$deviceId, $phone, $name, $description, $photoBase64]);

echo json_encode(['status' => 'ok']);
