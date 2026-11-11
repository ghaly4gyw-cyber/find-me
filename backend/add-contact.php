<?php
/**
 * Find Me — add an online contact by their contact code.
 *
 * One-directional, like adding a phonebook entry — it doesn't require the
 * other person to accept anything, and it doesn't make YOU show up in THEIR
 * contacts list. "Add their code" so two miners who aren't near each other on
 * the mesh can still reach each other online (see send-message.php,
 * contacts-status.php).
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
$contactCode = isset($body['contact_code']) ? strtoupper(trim((string) $body['contact_code'])) : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || $contactCode === '' || mb_strlen($contactCode) > 10) {
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

$stmt = $pdo->prepare('SELECT phone, name FROM registrations WHERE contact_code = ?');
$stmt->execute([$contactCode]);
$target = $stmt->fetch(PDO::FETCH_ASSOC);

if ($target === false) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'contact_code_not_found']);
    exit;
}

if ($target['phone'] === $phone) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'cannot_add_self']);
    exit;
}

// ON DUPLICATE KEY (owner_phone, contact_phone already exists) just no-ops —
// adding the same contact twice isn't an error, it's just already done.
$pdo->prepare(
    'INSERT INTO contacts (owner_phone, contact_phone) VALUES (?, ?)
     ON DUPLICATE KEY UPDATE owner_phone = owner_phone'
)->execute([$phone, $target['phone']]);

echo json_encode(['status' => 'ok', 'phone' => $target['phone'], 'name' => $target['name']]);
