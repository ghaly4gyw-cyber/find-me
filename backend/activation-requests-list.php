<?php
/**
 * Find Me — admin-only: list every pending activation request.
 *
 * "He will be has a list there of the requests" — Osama (or Ghaly) opens
 * the in-app admin screen, this is what fills it. Only a phone in
 * ADMIN_PHONES (see schema.php) can call this; anyone else gets 403, same
 * as requireOwnerDevice's own check still has to pass first (an admin can
 * only act from their own actual registered device).
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

$stmt = $pdo->query(
    "SELECT id, phone, name, device_id, requested_at
     FROM activation_requests
     WHERE status = 'pending'
     ORDER BY requested_at ASC
     LIMIT 200"
);
$requests = $stmt->fetchAll(PDO::FETCH_ASSOC);

echo json_encode(['status' => 'ok', 'requests' => $requests]);
