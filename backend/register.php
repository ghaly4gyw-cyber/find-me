<?php
/**
 * Find Me — registration upload endpoint.
 *
 * Receives {name, phone, device_id} from the app whenever it's online and
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

// Keep these generous but bounded — this is a phone number and a person's
// name, not free text, and the columns below are sized to match.
if ($name === '' || $phone === '' || $deviceId === ''
    || mb_strlen($name) > 100 || mb_strlen($phone) > 32 || mb_strlen($deviceId) > 64) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

require __DIR__ . '/schema.php';

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
// retry after a dropped connection) just refreshes its own row.
$stmt = $pdo->prepare(
    'INSERT INTO registrations (device_id, phone, name) VALUES (?, ?, ?)
     ON DUPLICATE KEY UPDATE phone = VALUES(phone), name = VALUES(name)'
);
$stmt->execute([$deviceId, $phone, $name]);

echo json_encode(['status' => 'ok']);
