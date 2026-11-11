<?php
/**
 * Find Me — send an online text message to another account.
 *
 * Only reaches the recipient once THEY next call fetch-messages.php while
 * online (polling, not push) — this is separate from, and doesn't touch, the
 * offline mesh Bluetooth/Wi-Fi chat, which never goes through the server at
 * all. "If online, it can send messages" — this is that path, for reaching
 * someone who isn't in mesh range right now.
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
$toPhone = isset($body['to_phone']) ? trim((string) $body['to_phone']) : '';
$messageBody = isset($body['body']) ? trim((string) $body['body']) : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || !isValidMauritanianPhone($toPhone)
    || $messageBody === '' || mb_strlen($messageBody) > 2000) {
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

$recipientExists = $pdo->prepare('SELECT 1 FROM registrations WHERE phone = ?');
$recipientExists->execute([$toPhone]);
if ($recipientExists->fetchColumn() === false) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'recipient_not_registered']);
    exit;
}

$pdo->prepare('INSERT INTO messages (from_phone, to_phone, body) VALUES (?, ?, ?)')
    ->execute([$phone, $toPhone, $messageBody]);
$messageId = (int) $pdo->lastInsertId();

// Sending a message is itself a sign of being online — free heartbeat.
$pdo->prepare('UPDATE registrations SET last_seen_at = NOW() WHERE phone = ?')->execute([$phone]);

echo json_encode(['status' => 'ok', 'id' => $messageId]);
