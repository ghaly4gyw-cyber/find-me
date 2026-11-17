<?php
/**
 * Find Me — activate a registered account directly from the admin panel,
 * with no activation_requests row needed.
 *
 * The normal path (activation-decide.php) only ever acts on a row in
 * activation_requests — and that row only exists if the person's own
 * device successfully called activate-request.php, which needs internet.
 * This app's whole audience is people who often have none (desert miners,
 * offline Bluetooth/Wi-Fi mesh) — so someone can register, use the app for
 * a while, and simply never be able to phone home a request, even though
 * Ghaly or Osama can already see them sitting right there in the user
 * list, unactivated, with no way to act on it. This is that way: any
 * admin can activate (or, from the same list, is already able to block)
 * any specific registered-but-unactivated account by device_id, the same
 * way an approval would — same effect on `registrations`, just without
 * requiring a pending request to exist first.
 */

require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
require __DIR__ . '/../schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: index.php');
    exit;
}

$deviceId = trim((string) ($_POST['device_id'] ?? ''));
if ($deviceId === '') {
    header('Location: index.php?admin_error=' . rawurlencode('لم يتم تحديد الحساب.'));
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

$stmt = $pdo->prepare('SELECT phone, name, activated FROM registrations WHERE device_id = ?');
$stmt->execute([$deviceId]);
$row = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$row) {
    header('Location: index.php?admin_error=' . rawurlencode('هذا الحساب غير موجود أصلًا.'));
    exit;
}

if (!(bool) $row['activated']) {
    $pdo->prepare(
        "UPDATE registrations SET activated = 1, activated_by_username = ?, activated_via = 'direct' WHERE device_id = ?"
    )->execute([$admin['username'] ?? '', $deviceId]);

    // If a pending request happens to exist for this phone too (e.g. it
    // reached the server moments after this click), close it out as
    // approved instead of leaving it to sit there looking unresolved.
    $pdo->prepare(
        "UPDATE activation_requests SET status = 'approved', decided_at = NOW() WHERE phone = ? AND status = 'pending'"
    )->execute([$row['phone']]);

    logAdminAction($pdo, $admin, 'activation_direct', $row['name']);
}

header('Location: index.php?control_saved=1');
