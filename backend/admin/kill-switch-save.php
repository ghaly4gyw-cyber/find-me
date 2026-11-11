<?php
/**
 * The global "turn everything off" switch — one click locks every phone
 * that checks in from here on into a full-screen, non-dismissible gate
 * (see RemoteControl.kt / MainActivity.showKillSwitchGate), and the same
 * click flipped back off restores the app instantly for anyone still
 * online. A phone with no signal at all won't get either message until it
 * reconnects — there is no way around that for an offline-first app, and
 * Ghaly was told so plainly before asking for this.
 *
 * Super-admin only, on purpose: this is the one lever bigger than blocking
 * a single person, so it stays Ghaly's alone unless he promotes someone
 * else to super_admin from "Manage admins".
 */

require __DIR__ . '/auth.php';
$admin = requireSuperAdmin();
require __DIR__ . '/../schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: index.php');
    exit;
}

$configPath = __DIR__ . '/../db-config.php';
if (!file_exists($configPath)) {
    http_response_code(500);
    die('الخادم غير مهيأ — ملف db-config.php غير موجود.');
}
$config = require $configPath;

try {
    $pdo = connectDb($config);
} catch (PDOException $e) {
    http_response_code(500);
    die('تعذّر الاتصال بقاعدة البيانات.');
}

ensureSchema($pdo);

$enabled = ($_POST['kill_switch'] ?? '') === '1';
$message = trim((string) ($_POST['kill_switch_message'] ?? ''));
$message = mb_substr($message, 0, 500);

$stmt = $pdo->prepare('UPDATE app_control SET kill_switch = ?, kill_switch_message = ? WHERE id = 1');
$stmt->execute([$enabled ? 1 : 0, $message ?: null]);

logAdminAction($pdo, $admin, $enabled ? 'kill_switch_on' : 'kill_switch_off', $message ?: null);

header('Location: index.php?control_saved=1');
