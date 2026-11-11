<?php
/**
 * Find Me — per-device block-status endpoint.
 *
 * Plain GET, no auth — a device only learns its OWN status by asking with
 * its own device_id, and the answer is a single boolean, nothing else
 * about the account. Ghaly blocks/unblocks a device from the admin
 * dashboard (see admin/block-user.php); this is what the app polls to
 * find out. A device that isn't registered at all is reported as not
 * blocked — there's nothing to block yet.
 */

header('Content-Type: application/json');
require __DIR__ . '/schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'reason' => 'method_not_allowed']);
    exit;
}

$deviceId = isset($_GET['device_id']) ? trim((string) $_GET['device_id']) : '';
if ($deviceId === '' || mb_strlen($deviceId) > 64) {
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

$stmt = $pdo->prepare('SELECT blocked FROM registrations WHERE device_id = ?');
$stmt->execute([$deviceId]);
$blocked = $stmt->fetchColumn();

echo json_encode(['blocked' => $blocked !== false && (bool) $blocked]);
