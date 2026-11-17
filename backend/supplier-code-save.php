<?php
/**
 * Find Me — supplier-only: change my own offline activation code, from
 * inside the app itself (SupplierPanelFragment.kt). Phone/device_id
 * authenticated, same pattern as every other app-facing endpoint — the
 * web's own admin/code-save.php is the session-based twin of this.
 *
 * As with the web version: the new code only reaches other devices the
 * next time they're online (register.php / status-check.php / reclaim.php
 * hand out the current list) — a device that already cached the old code
 * may keep accepting it until its own next sync. That's inherent to an
 * offline-first app and not something this endpoint can change.
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
$code = isset($body['code']) ? strtoupper(trim((string) $body['code'])) : '';

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone) || mb_strlen($deviceId) > 64) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

if (!preg_match('/^[A-Z0-9]{4,16}$/', $code)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'invalid_code_format']);
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

$account = adminAccountForPhone($pdo, $phone);
if ($account === null || $account['role'] !== 'supplier') {
    http_response_code(403);
    echo json_encode(['status' => 'error', 'reason' => 'not_a_supplier']);
    exit;
}

if (!requireOwnerDevice($pdo, $phone, $deviceId)) {
    exit;
}

$taken = $pdo->prepare('SELECT 1 FROM admin_accounts WHERE offline_code = ? AND username != ?');
$taken->execute([$code, $account['username']]);
if ($taken->fetchColumn()) {
    http_response_code(409);
    echo json_encode(['status' => 'error', 'reason' => 'code_already_in_use']);
    exit;
}

$pdo->prepare('UPDATE admin_accounts SET offline_code = ? WHERE username = ?')
    ->execute([$code, $account['username']]);

logAdminAction($pdo, $account, 'set_offline_code', $account['username']);

echo json_encode(['status' => 'ok', 'offline_code' => $code]);
