<?php
/**
 * Find Me — poll for new online messages addressed to this account.
 *
 * The app calls this periodically while online with the highest message id
 * it's already seen (since_id); only strictly newer rows come back. Simple
 * polling, not push — there's no notification service behind this, so new
 * messages show up the next time the app checks, not instantly while closed.
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
$sinceId = isset($body['since_id']) ? (int) $body['since_id'] : 0;

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || $sinceId < 0) {
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

$stmt = $pdo->prepare(
    'SELECT m.id, m.from_phone, m.body, m.created_at, r.name AS from_name
     FROM messages m
     LEFT JOIN registrations r ON r.phone = m.from_phone
     WHERE m.to_phone = ? AND m.id > ?
     ORDER BY m.id ASC
     LIMIT 200'
);
$stmt->execute([$phone, $sinceId]);
$messages = $stmt->fetchAll(PDO::FETCH_ASSOC);

// Polling for messages is itself a sign of being online — free heartbeat.
$pdo->prepare('UPDATE registrations SET last_seen_at = NOW() WHERE phone = ?')->execute([$phone]);

echo json_encode(['status' => 'ok', 'messages' => $messages]);
