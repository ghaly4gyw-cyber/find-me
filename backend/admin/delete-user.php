<?php
/**
 * Find Me — permanently remove a registration (and everything tied to it).
 *
 * Deliberately separate from everything else, and super-admin only — a
 * mistaken delete here is NOT recoverable (unlike blocking, which just
 * flips a flag back), so this needs its own explicit, confirmed action
 * rather than living next to the ordinary block/unblock buttons. Two modes:
 *
 *   - action=delete: one specific account, by device_id — used from the
 *     🗑️ button on a single user card.
 *   - action=delete_ci_canaries: every account CI's own healthcheck
 *     workflow creates against the LIVE site on every deploy (see
 *     .github/workflows/build.yml) — "a lot of accounts there, they are
 *     tinkers... remove them". Matched only by the fixed device_id prefix
 *     those tests always use (ci-healthcheck-canary...), which a real
 *     person's device (Android's own generated ID) could never collide
 *     with — safe to run as often as wanted, and CI recreates whatever it
 *     needs again on its very next run regardless.
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

/** Deletes one registration and everything keyed to its device_id/phone.
 *  Returns the deleted row's name for logging, or null if it didn't exist. */
function deleteOneRegistration(PDO $pdo, string $deviceId): ?string
{
    $stmt = $pdo->prepare('SELECT name, phone FROM registrations WHERE device_id = ?');
    $stmt->execute([$deviceId]);
    $row = $stmt->fetch(PDO::FETCH_ASSOC);
    if (!$row) {
        return null;
    }
    $phone = $row['phone'];
    $pdo->prepare('DELETE FROM registrations WHERE device_id = ?')->execute([$deviceId]);
    $pdo->prepare('DELETE FROM usage_stats WHERE device_id = ?')->execute([$deviceId]);
    $pdo->prepare('DELETE FROM feedback WHERE device_id = ?')->execute([$deviceId]);
    $pdo->prepare('DELETE FROM public_places WHERE device_id = ?')->execute([$deviceId]);
    $pdo->prepare('DELETE FROM activation_requests WHERE phone = ?')->execute([$phone]);
    $pdo->prepare('DELETE FROM contacts WHERE owner_phone = ? OR contact_phone = ?')->execute([$phone, $phone]);
    $pdo->prepare('DELETE FROM messages WHERE from_phone = ? OR to_phone = ?')->execute([$phone, $phone]);
    $pdo->prepare('DELETE FROM reclaim_log WHERE phone = ?')->execute([$phone]);
    return $row['name'];
}

$action = (string) ($_POST['action'] ?? 'delete');

if ($action === 'delete_ci_canaries') {
    $deviceIds = $pdo->query(
        "SELECT device_id FROM registrations WHERE device_id LIKE 'ci-healthcheck-canary%'"
    )->fetchAll(PDO::FETCH_COLUMN);
    $count = 0;
    foreach ($deviceIds as $deviceId) {
        if (deleteOneRegistration($pdo, (string) $deviceId) !== null) {
            $count++;
        }
    }
    logAdminAction($pdo, $admin, 'delete_ci_canaries', "{$count} حساب");
    header('Location: index.php?control_saved=1');
    exit;
}

$deviceId = trim((string) ($_POST['device_id'] ?? ''));
if ($deviceId === '') {
    header('Location: index.php?admin_error=' . rawurlencode('لم يتم تحديد الحساب.'));
    exit;
}

$name = deleteOneRegistration($pdo, $deviceId);
if ($name === null) {
    header('Location: index.php?admin_error=' . rawurlencode('هذا الحساب غير موجود أصلًا.'));
    exit;
}

logAdminAction($pdo, $admin, 'delete_user', $name);
header('Location: index.php?control_saved=1');
