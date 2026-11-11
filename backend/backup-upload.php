<?php
/**
 * Find Me — account data backup.
 *
 * Opportunistically uploads a bundle of this phone's own accounts ledger,
 * notes and saved places (see BackupUploader.kt) — "their data should not be
 * auto-deleted... their data should be on the host and auto go with them" —
 * so a lost, broken, or reinstalled phone doesn't lose any of it, as long as
 * the person set a PIN and can reclaim their number on the new phone (see
 * reclaim.php). Only the device currently bound to the phone number can
 * upload its backup — same ownership check as set-pin.php — so this can
 * never be used to overwrite someone else's backup.
 */

header('Content-Type: application/json');
require __DIR__ . '/schema.php';

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

$phone = isset($body['phone']) ? trim((string) $body['phone']) : '';
$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';
// The bundle itself travels as a raw JSON string the app already built
// (books+accounts+notes+places) — this endpoint doesn't need to understand
// its shape, just store and hand it back unchanged.
$backupJson = isset($body['backup_json']) ? (string) $body['backup_json'] : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || $backupJson === '' || strlen($backupJson) > 3_000_000) {
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

$stmt = $pdo->prepare('SELECT device_id FROM registrations WHERE phone = ?');
$stmt->execute([$phone]);
$currentDeviceId = $stmt->fetchColumn();

if ($currentDeviceId === false) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'not_registered']);
    exit;
}
if ($currentDeviceId !== $deviceId) {
    http_response_code(403);
    echo json_encode(['status' => 'error', 'reason' => 'not_the_registered_device']);
    exit;
}

$stmt = $pdo->prepare('UPDATE registrations SET backup_json = ?, backup_updated_at = NOW() WHERE phone = ?');
$stmt->execute([$backupJson, $phone]);

echo json_encode(['status' => 'ok']);
