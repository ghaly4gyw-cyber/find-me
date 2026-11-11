<?php
/**
 * Blocks or unblocks a single registered device from the dashboard — the
 * "I can block any user I want" control. A blocked device shows a
 * permanent, non-dismissible screen in the app (see MainActivity's
 * showBlockedGate) the next time it checks in; unblocking takes effect the
 * same way, the next time that device is online.
 */

require __DIR__ . '/auth.php';
requireAdminLogin();
require __DIR__ . '/../schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: index.php');
    exit;
}

$deviceId = trim((string) ($_POST['device_id'] ?? ''));
$action = trim((string) ($_POST['action'] ?? ''));

if ($deviceId === '' || !in_array($action, ['block', 'unblock'], true)) {
    header('Location: index.php');
    exit;
}

$configPath = __DIR__ . '/../db-config.php';
if (!file_exists($configPath)) {
    http_response_code(500);
    die('Server not configured — db-config.php is missing.');
}
$config = require $configPath;

try {
    $pdo = connectDb($config);
} catch (PDOException $e) {
    http_response_code(500);
    die('Could not reach the database.');
}

ensureSchema($pdo);

$stmt = $pdo->prepare('UPDATE registrations SET blocked = ? WHERE device_id = ?');
$stmt->execute([$action === 'block' ? 1 : 0, $deviceId]);

header('Location: index.php');
