<?php
/**
 * Find Me — set/change/clear the account-recovery PIN.
 *
 * Lets the device that currently owns a phone number set an optional 4–8
 * digit PIN on it (see RegistrationUploader/PinUploader on the app side).
 * That PIN is the ONLY thing that can move a phone number to a different
 * device afterward (see reclaim.php) — register.php itself keeps rejecting
 * a second device for an already-registered number no matter what, so the
 * PIN doesn't weaken that; it's the one deliberate, explicit way around it.
 *
 * Sending an empty "pin" clears it (no more recovery possible for that
 * number until a new one is set).
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
$pin = isset($body['pin']) ? trim((string) $body['pin']) : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone) || mb_strlen($deviceId) > 64) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}
// Optional: either empty (clear) or exactly 4-8 digits — "optional, but the
// minimum is 4 digits", capped at 8 so it stays something a person can
// actually remember and type with cold/gloved hands in the field.
if ($pin !== '' && !preg_match('/^[0-9]{4,8}$/', $pin)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'invalid_pin_format']);
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
// Only the device that currently owns this number may set/change/clear its
// own PIN — otherwise anyone who merely knew the phone number could lock the
// real owner out of ever recovering it.
if ($currentDeviceId !== $deviceId) {
    http_response_code(403);
    echo json_encode(['status' => 'error', 'reason' => 'not_the_registered_device']);
    exit;
}

$pinHash = $pin === '' ? null : password_hash($pin, PASSWORD_DEFAULT);

$stmt = $pdo->prepare(
    'UPDATE registrations SET pin_hash = ?, reclaim_attempts = 0, reclaim_locked_until = NULL WHERE phone = ?'
);
$stmt->execute([$pinHash, $phone]);

echo json_encode(['status' => 'ok', 'pin_set' => $pinHash !== null]);
