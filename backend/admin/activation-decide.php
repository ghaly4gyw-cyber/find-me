<?php
/**
 * Web-panel version of approving/rejecting a pending activation request —
 * same effect as the in-app 🛡️ screen's decide action (see the root-level
 * activation-decide.php, which the Android app calls with a phone/device_id
 * instead of a session), but reachable from a browser with no phone needed.
 * Either admin (Ghaly or Osama, any role) can decide a request — unlike the
 * kill switch and app-control settings, this isn't reserved to super_admin.
 */

require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
require __DIR__ . '/../schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: index.php');
    exit;
}

$requestId = (int) ($_POST['request_id'] ?? 0);
$decision = (string) ($_POST['decision'] ?? '');

if ($requestId <= 0 || !in_array($decision, ['approve', 'reject'], true)) {
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

$stmt = $pdo->prepare("SELECT phone FROM activation_requests WHERE id = ? AND status = 'pending'");
$stmt->execute([$requestId]);
$row = $stmt->fetch(PDO::FETCH_ASSOC);

if ($row) {
    $newStatus = $decision === 'approve' ? 'approved' : 'rejected';
    $pdo->prepare('UPDATE activation_requests SET status = ?, decided_at = NOW() WHERE id = ?')
        ->execute([$newStatus, $requestId]);
    if ($decision === 'approve') {
        // Credits whoever approved it — Ghaly, Osama, or a supplier — same
        // attribution a self-activation-by-code gets (see activation-
        // confirm.php), so a supplier's "people I activated" list and count
        // include approvals too, not just codes.
        $pdo->prepare(
            "UPDATE registrations SET activated = 1, activated_by_username = ?, activated_via = 'approval' WHERE phone = ?"
        )->execute([$admin['username'] ?? '', $row['phone']]);
    }
    logAdminAction($pdo, $admin, 'activation_' . $newStatus, $row['phone']);
}

header('Location: index.php');
