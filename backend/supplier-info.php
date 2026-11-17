<?php
/**
 * Find Me — supplier-only: this supplier's own in-app panel data.
 *
 * Mirrors admin/supplier-panel.php's own three sections (own code, this-
 * supplier's stats, the pending queue is fetched separately via the shared
 * activation-requests-list.php), but authenticated by phone/device_id like
 * every other app-facing endpoint instead of a web username/password
 * session — this is what SupplierPanelFragment.kt calls so a supplier gets
 * "the same options he had on the web", right inside the app itself.
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

$account = adminAccountForPhone($pdo, $phone);
if ($account === null || $account['role'] !== 'supplier') {
    http_response_code(403);
    echo json_encode(['status' => 'error', 'reason' => 'not_a_supplier']);
    exit;
}

if (!requireOwnerDevice($pdo, $phone, $deviceId)) {
    exit;
}

$stmt = $pdo->prepare(
    'SELECT name, phone, activated_via, updated_at FROM registrations WHERE activated_by_username = ? ORDER BY updated_at DESC LIMIT 200'
);
$stmt->execute([$account['username']]);
$activated = $stmt->fetchAll(PDO::FETCH_ASSOC);

$byCode = count(array_filter($activated, fn ($u) => $u['activated_via'] === 'code'));
$byApproval = count(array_filter($activated, fn ($u) => $u['activated_via'] === 'approval'));

echo json_encode([
    'status' => 'ok',
    'name' => $account['name'],
    'offline_code' => $account['offline_code'],
    'total_activated' => count($activated),
    'activated_by_code' => $byCode,
    'activated_by_approval' => $byApproval,
    'activated_list' => $activated,
]);
