<?php
/**
 * Find Me — best-effort sync after a LOCAL, fully-offline code activation.
 *
 * Entering the right code (ActivationCodes.kt) unlocks the app on-device
 * immediately, with no server round trip required — it has to work with
 * zero connectivity, ever. This endpoint is only called opportunistically,
 * next time the phone happens to be online, purely so the server's own
 * record (and Osama/Ghaly's pending-requests list) agrees with what the
 * phone already unlocked for itself. If this call never succeeds (the phone
 * never gets online again), the app stays unlocked regardless — that's the
 * whole point of the code path.
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

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone) || mb_strlen($deviceId) > 64) {
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

if (!requireOwnerDevice($pdo, $phone, $deviceId)) {
    exit;
}

$pdo->prepare('UPDATE registrations SET activated = 1 WHERE phone = ?')->execute([$phone]);
// Close out any pending request this account had — it already activated
// itself by code, so it shouldn't still show up waiting on Osama/Ghaly.
$pdo->prepare(
    "UPDATE activation_requests SET status = 'approved', decided_at = NOW()
     WHERE phone = ? AND status = 'pending'"
)->execute([$phone]);

echo json_encode(['status' => 'ok']);
