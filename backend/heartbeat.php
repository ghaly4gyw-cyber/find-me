<?php
/**
 * Find Me — "I'm online right now" heartbeat.
 *
 * Deliberately tiny and separate from location uploads: online presence
 * (for contacts-status.php's online/offline dot) shouldn't require sharing
 * where someone is. The app pings this every so often while it has internet
 * (see OnlineMessenger.kt) — last_seen_at recency is all contacts-status.php
 * needs to answer "is this contact online right now".
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

$pdo->prepare('UPDATE registrations SET last_seen_at = NOW() WHERE phone = ?')->execute([$phone]);

echo json_encode(['status' => 'ok']);
