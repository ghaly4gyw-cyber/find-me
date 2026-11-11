<?php
/**
 * Find Me — feedback endpoint.
 *
 * Receives {device_id, message, name?} whenever someone taps "Send" on the
 * in-app "Suggest a feature" box (see FeedbackUploader in the app). Unlike
 * the other endpoints here, this is append-only: every message is kept as
 * its own row, not upserted, so the admin dashboard can show the running
 * list of what people have asked for.
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

$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';
$message = isset($body['message']) ? trim((string) $body['message']) : '';
$name = isset($body['name']) ? trim((string) $body['name']) : null;
if ($name === '') $name = null;

if ($deviceId === '' || mb_strlen($deviceId) > 64
    || $message === '' || mb_strlen($message) > 1000
    || ($name !== null && mb_strlen($name) > 100)) {
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

$stmt = $pdo->prepare('INSERT INTO feedback (device_id, name, message) VALUES (?, ?, ?)');
$stmt->execute([$deviceId, $name, $message]);

echo json_encode(['status' => 'ok']);
