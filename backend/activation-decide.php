<?php
/**
 * Find Me — admin-only: approve or reject a pending activation request.
 *
 * Approving sets registrations.activated = 1 for that phone — the one thing
 * that actually unlocks the app on the requester's device (see
 * status-check.php, polled periodically, and MainActivity.enforceActivation
 * State). Rejecting just closes out the request; the account stays locked,
 * and the person can always request again later (activate-request.php's
 * upsert puts it straight back to pending).
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
$requestId = isset($body['request_id']) ? (int) $body['request_id'] : 0;
$decision = isset($body['decision']) ? trim((string) $body['decision']) : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || $requestId <= 0 || !in_array($decision, ['approve', 'reject'], true)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

if (!isAdminPhone($phone)) {
    http_response_code(403);
    echo json_encode(['status' => 'error', 'reason' => 'not_an_admin']);
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

$stmt = $pdo->prepare('SELECT phone FROM activation_requests WHERE id = ? AND status = \'pending\'');
$stmt->execute([$requestId]);
$targetPhone = $stmt->fetchColumn();

if ($targetPhone === false) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'request_not_found']);
    exit;
}

$newStatus = $decision === 'approve' ? 'approved' : 'rejected';
$pdo->prepare('UPDATE activation_requests SET status = ?, decided_at = NOW() WHERE id = ?')
    ->execute([$newStatus, $requestId]);

if ($decision === 'approve') {
    $pdo->prepare('UPDATE registrations SET activated = 1 WHERE phone = ?')->execute([$targetPhone]);
}

echo json_encode(['status' => 'ok']);
