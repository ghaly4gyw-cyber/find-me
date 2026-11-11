<?php
/**
 * "Manage admins" actions:
 *   - add_admin: creates a new panel login (super_admin only — this is how
 *     Ghaly gives Osama his own account, with his own password, straight
 *     from the dashboard; no GitHub secret, no redeploy).
 *   - reset_password: super_admin can reset ANY account's password; a
 *     plain admin can only reset their OWN (self-service), enforced here
 *     server-side, not just by hiding the field in the page.
 * There's deliberately no "remove admin" — a mistaken delete could lock
 * someone out with no way back short of direct database access, which
 * isn't worth it for two or three accounts total.
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

$action = (string) ($_POST['action'] ?? '');

if ($action === 'add_admin') {
    if (($admin['role'] ?? '') !== 'super_admin') {
        http_response_code(403);
        die('هذا للمشرف العام فقط.');
    }
    $username = strtolower(trim((string) ($_POST['username'] ?? '')));
    $name = trim((string) ($_POST['name'] ?? ''));
    $password = (string) ($_POST['password'] ?? '');
    $role = (string) ($_POST['role'] ?? 'admin');

    if (!preg_match('/^[a-z0-9_]{3,32}$/', $username)) {
        header('Location: index.php?admin_error=' . rawurlencode('اسم المستخدم يجب أن يكون 3-32 حرفًا إنجليزيًا صغيرًا أو رقمًا أو شرطة سفلية.'));
        exit;
    }
    if ($name === '' || mb_strlen($name) > 100) {
        header('Location: index.php?admin_error=' . rawurlencode('أدخل الاسم (حتى 100 حرف).'));
        exit;
    }
    if (mb_strlen($password) < 8) {
        header('Location: index.php?admin_error=' . rawurlencode('كلمة المرور يجب ألا تقل عن 8 أحرف.'));
        exit;
    }
    if (!in_array($role, ['admin', 'super_admin'], true)) {
        $role = 'admin';
    }

    $exists = $pdo->prepare('SELECT 1 FROM admin_accounts WHERE username = ?');
    $exists->execute([$username]);
    if ($exists->fetchColumn()) {
        header('Location: index.php?admin_error=' . rawurlencode('اسم المستخدم هذا مُستخدم بالفعل.'));
        exit;
    }

    // Optional initial offline code — same format rule as code-save.php,
    // checked the same way so a duplicate can't slip in through this form.
    $code = strtoupper(trim((string) ($_POST['code'] ?? '')));
    if ($code !== '') {
        if (!preg_match('/^[A-Z0-9]{6,16}$/', $code)) {
            header('Location: index.php?admin_error=' . rawurlencode('الرمز غير المتصل يجب أن يكون 6-16 حرفًا/رقمًا، أو يُترك فارغًا.'));
            exit;
        }
        $codeTaken = $pdo->prepare('SELECT 1 FROM admin_accounts WHERE offline_code = ?');
        $codeTaken->execute([$code]);
        if ($codeTaken->fetchColumn()) {
            header('Location: index.php?admin_error=' . rawurlencode('هذا الرمز مُستخدم بالفعل من قبل مشرف آخر.'));
            exit;
        }
    }

    $hash = password_hash($password, PASSWORD_DEFAULT);
    $pdo->prepare('INSERT INTO admin_accounts (username, name, password_hash, role, offline_code) VALUES (?, ?, ?, ?, ?)')
        ->execute([$username, $name, $hash, $role, $code !== '' ? $code : null]);
    logAdminAction($pdo, $admin, 'add_admin', "{$username} ({$role})");
    header('Location: index.php?control_saved=1');
    exit;
}

if ($action === 'reset_password') {
    $targetUsername = strtolower(trim((string) ($_POST['username'] ?? '')));
    $password = (string) ($_POST['password'] ?? '');

    $isSelf = $targetUsername === ($admin['username'] ?? '');
    $isSuperAdmin = ($admin['role'] ?? '') === 'super_admin';
    if (!$isSelf && !$isSuperAdmin) {
        http_response_code(403);
        die('يمكنك فقط إعادة تعيين كلمة مرورك أنت.');
    }
    if (mb_strlen($password) < 8) {
        header('Location: index.php?admin_error=' . rawurlencode('كلمة المرور يجب ألا تقل عن 8 أحرف.'));
        exit;
    }

    $hash = password_hash($password, PASSWORD_DEFAULT);
    $stmt = $pdo->prepare('UPDATE admin_accounts SET password_hash = ? WHERE username = ?');
    $stmt->execute([$hash, $targetUsername]);
    if ($stmt->rowCount() > 0) {
        logAdminAction($pdo, $admin, 'reset_password', $targetUsername);
    }
    header('Location: index.php?control_saved=1');
    exit;
}

header('Location: index.php');
