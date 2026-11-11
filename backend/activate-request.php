<?php
/**
 * Find Me — submit (or re-submit) a request for Osama or Ghaly to activate
 * this account.
 *
 * Only reaches an admin once one of them is online and checks
 * activation-requests-list.php — "this only if there is internet was
 * connected online, he get the request and he accept it". Upserts by phone,
 * so requesting again after a rejection just puts the row back to pending
 * instead of piling up history nobody needs.
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

$stmt = $pdo->prepare('SELECT activated, name FROM registrations WHERE phone = ?');
$stmt->execute([$phone]);
$reg = $stmt->fetch(PDO::FETCH_ASSOC);

if ($reg !== false && (bool) $reg['activated']) {
    // Already activated (maybe by code, moments ago) — nothing to request.
    echo json_encode(['status' => 'ok', 'already_activated' => true]);
    exit;
}

$pdo->prepare(
    'INSERT INTO activation_requests (phone, device_id, name, status, requested_at, decided_at)
     VALUES (?, ?, ?, \'pending\', NOW(), NULL)
     ON DUPLICATE KEY UPDATE
        device_id = VALUES(device_id),
        name = VALUES(name),
        status = \'pending\',
        requested_at = NOW(),
        decided_at = NULL'
)->execute([$phone, $deviceId, $reg['name'] ?? '']);

echo json_encode(['status' => 'ok', 'already_activated' => false]);
