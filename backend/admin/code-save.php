<?php
/**
 * Changes an admin's offline activation code (see ActivationCodes.kt) —
 * self-service for your own, same rule as reset_password: a super_admin
 * can also change someone else's. The new value only reaches devices the
 * next time they're online (register.php / status-check.php / reclaim.php
 * now hand out the current list) — there's no way to push it out faster
 * than that, and a device that already cached the old code may keep
 * accepting it until its own next sync. The panel warns about this before
 * submitting; this endpoint just makes the actual change and logs it.
 */

require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
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

$targetUsername = strtolower(trim((string) ($_POST['username'] ?? '')));
$code = strtoupper(trim((string) ($_POST['code'] ?? '')));

$isSelf = $targetUsername === ($admin['username'] ?? '');
$isSuperAdmin = ($admin['role'] ?? '') === 'super_admin';
if (!$isSelf && !$isSuperAdmin) {
    http_response_code(403);
    die('يمكنك فقط تغيير رمزك أنت.');
}

if (!preg_match('/^[A-Z0-9]{6,16}$/', $code)) {
    header('Location: index.php?admin_error=' . rawurlencode('الرمز يجب أن يكون 6-16 حرفًا/رقمًا.'));
    exit;
}

$targetExists = $pdo->prepare('SELECT 1 FROM admin_accounts WHERE username = ?');
$targetExists->execute([$targetUsername]);
if (!$targetExists->fetchColumn()) {
    header('Location: index.php?admin_error=' . rawurlencode('حساب المشرف هذا غير موجود.'));
    exit;
}

$taken = $pdo->prepare('SELECT 1 FROM admin_accounts WHERE offline_code = ? AND username != ?');
$taken->execute([$code, $targetUsername]);
if ($taken->fetchColumn()) {
    header('Location: index.php?admin_error=' . rawurlencode('That code is already in use by another admin.'));
    exit;
}

$pdo->prepare('UPDATE admin_accounts SET offline_code = ? WHERE username = ?')
    ->execute([$code, $targetUsername]);

// Never logs the code value itself — same restraint as passwords.
logAdminAction($pdo, $admin, 'set_offline_code', $targetUsername);

header('Location: index.php?control_saved=1');
