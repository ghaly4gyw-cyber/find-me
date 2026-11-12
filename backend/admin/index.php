<?php
require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
$isSuperAdmin = ($admin['role'] ?? '') === 'super_admin';
// Suppliers (telecom-shop partners) get their own small, scoped page —
// not this full dashboard — so each only ever sees their own code and the
// people they activated, never the whole user base. "This is SAS project
// at this point. It should be SAS."
if (($admin['role'] ?? '') === 'supplier') {
    header('Location: supplier-panel.php');
    exit;
}
require __DIR__ . '/../schema.php';

/** اسم عربي مفهوم لمفتاح الشاشة (اسم كلاس Kotlin، مثل "NotesFragment") —
 *  يرجع للاسم الخام بدون "Fragment" لأي شاشة مستقبلية غير مُدرجة هنا، حتى
 *  لا تظهر فارغة أبدًا. */
function friendlyFeatureName(string $key): string
{
    $map = [
        'PeersFragment' => 'القريبون',
        'RadarMapFragment' => 'الخريطة',
        'ChatFragment' => 'الدردشة',
        'SosFragment' => 'الاستغاثة (SOS)',
        'PostsFragment' => 'المنشورات',
        'PostCommentsFragment' => 'تعليقات المنشور',
        'GroupsFragment' => 'المجموعات',
        'GroupChatFragment' => 'دردشة المجموعة',
        'NotesFragment' => 'الملاحظات',
        'CalculatorFragment' => 'الآلة الحاسبة',
        'AccountsFragment' => 'الحسابات / الدفتر',
        'AccountDetailFragment' => 'تفاصيل الحساب',
    ];
    if (isset($map[$key])) return $map[$key];
    return preg_replace('/Fragment$/', '', $key) ?: $key;
}

/** "Wi-Fi — Home Router" / "بيانات الهاتف — Mauritel" / "Bluetooth" /
 *  "غير معروف" — يحوّل عمودي network_type/network_name الخام إلى سطر
 *  واحد مقروء، ليعرف غالي من نظرة واحدة أي اتصال (Starlink، شركة اتصالات،
 *  شبكة محلية) كان يستخدمه الشخص. */
function friendlyNetwork(?string $type, ?string $name): string
{
    $labels = [
        'wifi' => 'Wi-Fi',
        'cellular' => 'بيانات الهاتف',
        'bluetooth' => 'Bluetooth',
        'ethernet' => 'Ethernet',
        'other' => 'أخرى',
        'none' => 'غير متصل',
    ];
    $label = $labels[$type] ?? 'غير معروف';
    if ($name) return "{$label} — {$name}";
    return $label;
}

/** "2س 15د" / "12د" / "أقل من دقيقة" / "0د" — صيغة مقروءة دائمًا، أبدًا
 *  رقم مللي ثانية خام. */
function formatDuration(int $ms): string
{
    if ($ms <= 0) return '0د';
    $totalMinutes = intdiv($ms, 60000);
    $hours = intdiv($totalMinutes, 60);
    $minutes = $totalMinutes % 60;
    if ($hours > 0) return "{$hours}س {$minutes}د";
    if ($totalMinutes > 0) return "{$totalMinutes}د";
    return 'أقل من دقيقة';
}

/** تسمية عربية لإجراءات سجل النشاط — تُبنى من قيمة action المخزنة في
 *  admin_audit_log؛ أي إجراء مستقبلي غير مُدرج يظهر بشكله الخام (استثناء
 *  "ما لا يمكن ترجمته"). */
function friendlyAuditAction(string $action): string
{
    $map = [
        'block_user' => 'حظر مستخدم',
        'unblock_user' => 'فك حظر مستخدم',
        'app_control_save' => 'حفظ إعدادات التحكم بالتطبيق',
        'kill_switch_on' => 'إيقاف التطبيق للجميع',
        'kill_switch_off' => 'إعادة تشغيل التطبيق للجميع',
        'add_admin' => 'إضافة مشرف',
        'reset_password' => 'إعادة تعيين كلمة مرور',
        'set_offline_code' => 'تغيير رمز بدون إنترنت',
        'activation_approve' => 'قبول طلب تفعيل',
        'activation_reject' => 'رفض طلب تفعيل',
    ];
    return $map[$action] ?? str_replace('_', ' ', $action);
}

/** Arabic label for one reclaim_log.result value — see schema.php. */
function friendlyReclaimResult(string $result): string
{
    $map = [
        'success' => '✅ نجحت الاستعادة',
        'no_such_account' => 'الرقم غير مسجّل',
        'no_pin_set' => 'لا يوجد رمز استعادة (PIN) لهذا الحساب',
        'locked_retry' => 'محاولة وهو مقفل مؤقتًا',
        'wrong_pin' => '⚠️ رمز استعادة خاطئ',
        'wrong_pin_locked' => '⚠️ رمز خاطئ — تم القفل 15 دقيقة',
        'device_conflict' => 'الجهاز الجديد مرتبط بحساب آخر أصلًا',
    ];
    return $map[$result] ?? str_replace('_', ' ', $result);
}

$users = [];
$feedback = [];
$publicPlaces = [];
$appControl = null;
$pendingActivations = [];
$adminAccounts = [];
$auditLog = [];
$lastKillSwitchChange = null;
$reclaimLog = [];
$suspiciousReclaimPhones = [];
$supplierPhones = [];
$ownAccount = null;
$error = null;
$dbConfigPath = __DIR__ . '/../db-config.php';

if (!file_exists($dbConfigPath)) {
    $error = 'الخادم غير مهيأ — ملف db-config.php غير موجود.';
} else {
    try {
        $config = require $dbConfigPath;
        $pdo = connectDb($config);
        ensureSchema($pdo);
        $users = $pdo->query(
            'SELECT r.name, r.phone, r.device_id, r.last_lat, r.last_lng, r.last_location_at,
                    r.description, r.photo_base64, r.blocked, r.activated, r.created_at,
                    r.device_model, r.android_version, r.app_version, r.network_type, r.network_name,
                    u.app_opens, u.total_ms, u.feature_usage
             FROM registrations r
             LEFT JOIN usage_stats u ON u.device_id = r.device_id
             ORDER BY r.updated_at DESC'
        )->fetchAll(PDO::FETCH_ASSOC);
        $feedback = $pdo->query(
            'SELECT device_id, name, message, created_at FROM feedback ORDER BY created_at DESC LIMIT 200'
        )->fetchAll(PDO::FETCH_ASSOC);
        $publicPlaces = $pdo->query(
            'SELECT place_id, device_id, name, lat, lon, shared_by_name, updated_at
             FROM public_places ORDER BY updated_at DESC LIMIT 500'
        )->fetchAll(PDO::FETCH_ASSOC);
        $appControl = $pdo->query(
            'SELECT message_title, message_body, min_supported_version, min_supported_message, kill_switch, kill_switch_message FROM app_control WHERE id = 1'
        )->fetch(PDO::FETCH_ASSOC);
        $pendingActivations = $pdo->query(
            "SELECT id, phone, name, device_id, requested_at FROM activation_requests WHERE status = 'pending' ORDER BY requested_at ASC"
        )->fetchAll(PDO::FETCH_ASSOC);
        $adminAccounts = array_values(array_filter(
            $pdo->query(
                'SELECT username, name, role, offline_code, created_at FROM admin_accounts ORDER BY created_at ASC'
            )->fetchAll(PDO::FETCH_ASSOC),
            fn ($a) => $a['role'] !== 'supplier'
        ));
        // Phone → supplier name, so a supplier's card in "المستخدمون" below
        // can carry a "💼 مندوب" badge like the admin-phone "👑" one does.
        $supplierPhones = $pdo->query(
            "SELECT phone, name FROM admin_accounts WHERE role = 'supplier' AND phone IS NOT NULL"
        )->fetchAll(PDO::FETCH_KEY_PAIR);
        $auditLog = $pdo->query(
            'SELECT admin_name, action, detail, created_at FROM admin_audit_log ORDER BY id DESC LIMIT 30'
        )->fetchAll(PDO::FETCH_ASSOC);
        $lastKillSwitchChange = $pdo->query(
            "SELECT admin_name, action, created_at FROM admin_audit_log WHERE action IN ('kill_switch_on', 'kill_switch_off') ORDER BY id DESC LIMIT 1"
        )->fetch(PDO::FETCH_ASSOC);
        // "Someone stole his phone number, and we investigation in that" —
        // every reclaim.php outcome (not just successes) so a specific
        // complaint can actually be traced: which device the number moved
        // from/to and when.
        $reclaimLog = $pdo->query(
            'SELECT phone, old_device_id, new_device_id, result, created_at FROM reclaim_log ORDER BY id DESC LIMIT 50'
        )->fetchAll(PDO::FETCH_ASSOC);
        // A phone with 3+ non-success attempts in the last 24 hours is
        // flagged — repeated wrong-PIN tries are exactly what someone
        // guessing at a stolen number would produce.
        $suspiciousReclaimPhones = $pdo->query(
            "SELECT phone, COUNT(*) AS failures FROM reclaim_log
             WHERE result != 'success' AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
             GROUP BY phone HAVING COUNT(*) >= 3"
        )->fetchAll(PDO::FETCH_KEY_PAIR);
        $ownAccount = null;
        foreach ($adminAccounts as $a) {
            if ($a['username'] === ($admin['username'] ?? '')) {
                $ownAccount = $a;
                break;
            }
        }
    } catch (PDOException $e) {
        $error = 'تعذّر الاتصال بقاعدة البيانات.';
    }
}

// مرجع للقراءة فقط للنموذج أدناه — الإصدار الذي تقدّمه صفحة التحميل
// العامة حاليًا (يكتبه مهمة deploy-backend في CI)، ليعرف غالي أي رقم
// إصدار يعني "الجميع على آخر نسخة" عند ضبط حد التحديث الإجباري.
$publishedVersion = null;
$publishedVersionPath = __DIR__ . '/../download/version.json';
if (file_exists($publishedVersionPath)) {
    $decoded = json_decode((string) file_get_contents($publishedVersionPath), true);
    if (is_array($decoded)) {
        $publishedVersion = $decoded;
    }
}

$onlineCutoff = time() - 15 * 60; // "متصل" = ظهر خلال آخر 15 دقيقة
$onlineCount = 0;
$totalAppOpens = 0;
$totalMsAcrossUsers = 0;
$activatedCount = 0;
$notActivatedCount = 0;
$blockedCount = 0;
$aggregateFeatures = []; // key => ['opens' => n, 'ms' => n]
$detailsByDevice = [];

foreach ($users as $u) {
    if ($u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff) {
        $onlineCount++;
    }
    if (!empty($u['activated'] ?? null) || isAdminPhone((string) $u['phone'])) {
        $activatedCount++;
    } else {
        $notActivatedCount++;
    }
    if (!empty($u['blocked'])) {
        $blockedCount++;
    }
    $appOpens = (int) ($u['app_opens'] ?? 0);
    $totalMs = (int) ($u['total_ms'] ?? 0);
    $totalAppOpens += $appOpens;
    $totalMsAcrossUsers += $totalMs;

    $features = [];
    if (!empty($u['feature_usage'])) {
        $decoded = json_decode($u['feature_usage'], true);
        if (is_array($decoded)) {
            foreach ($decoded as $key => $val) {
                $opens = (int) ($val['opens'] ?? 0);
                $ms = (int) ($val['ms'] ?? 0);
                $features[] = [
                    'key' => $key,
                    'label' => friendlyFeatureName((string) $key),
                    'opens' => $opens,
                    'ms_fmt' => formatDuration($ms),
                ];
                if (!isset($aggregateFeatures[$key])) {
                    $aggregateFeatures[$key] = ['opens' => 0, 'ms' => 0];
                }
                $aggregateFeatures[$key]['opens'] += $opens;
                $aggregateFeatures[$key]['ms'] += $ms;
            }
            usort($features, function ($a, $b) use ($decoded) {
                return ($decoded[$b['key']]['ms'] ?? 0) <=> ($decoded[$a['key']]['ms'] ?? 0);
            });
        }
    }

    $isAdminUser = isAdminPhone((string) $u['phone']);
    $detailsByDevice[$u['device_id']] = [
        'name' => $u['name'],
        'phone' => $u['phone'],
        'device_id' => $u['device_id'],
        'description' => $u['description'],
        'photo' => !empty($u['photo_base64']) ? ('data:image/jpeg;base64,' . $u['photo_base64']) : null,
        'created_at' => $u['created_at'],
        'last_seen' => $u['last_location_at'],
        'app_opens' => $appOpens,
        'time_spent' => formatDuration($totalMs),
        'features' => $features,
        'blocked' => (bool) $u['blocked'],
        'activated' => (bool) $u['activated'] || $isAdminUser,
        'is_admin' => $isAdminUser,
        'device_model' => $u['device_model'] ?: null,
        'android_version' => $u['android_version'] ?: null,
        'app_version' => $u['app_version'] ?: null,
        'network' => friendlyNetwork($u['network_type'] ?: null, $u['network_name'] ?: null),
    ];
}

uasort($aggregateFeatures, fn($a, $b) => $b['ms'] <=> $a['ms']);
$topFeatures = [];
foreach ($aggregateFeatures as $key => $agg) {
    $topFeatures[] = [
        'label' => friendlyFeatureName((string) $key),
        'opens' => $agg['opens'],
        'ms_fmt' => formatDuration($agg['ms']),
    ];
}
$topFeatures = array_slice($topFeatures, 0, 10);
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>Find Me — لوحة التحكم</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js"></script>
<style>
  [x-cloak] { display: none !important; }
  :root {
    --bg: #0c1716; --panel: #122220; --panel-2: #16302c; --border: #234743;
    --text: #e9f3f1; --muted: #7fa39d; --accent: #2a9d8f; --accent-2: #4fd1c5;
    --warn: #e9c46a; --danger: #ff7a6b;
  }
  * { box-sizing: border-box; }
  body {
    font-family: "Segoe UI", Tahoma, system-ui, -apple-system, sans-serif;
    background: var(--bg); color: var(--text); margin: 0; padding: 1rem;
    font-size: 0.94rem;
  }
  bdi { unicode-bidi: isolate; }
  .top { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.6rem; margin-bottom: 0.9rem; }
  .top h1 { font-size: 1.15rem; margin: 0; letter-spacing: 0.2px; }
  .top h1 span { color: var(--accent-2); }
  .logout { color: var(--danger); text-decoration: none; font-size: 0.85rem; border: 1px solid rgba(255,122,107,0.35); padding: 5px 11px; border-radius: 8px; }
  .logout:hover { background: rgba(255,122,107,0.1); }

  .stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(110px, 1fr)); gap: 0.5rem; margin-bottom: 0.9rem; }
  .stat { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.6rem 0.7rem; }
  .stat .n { font-size: 1.25rem; font-weight: 700; color: var(--accent-2); }
  .stat .l { font-size: 0.7rem; color: var(--muted); }

  .section-title { font-size: 0.86rem; font-weight: 700; margin: 1.1rem 0 0.5rem; color: var(--accent-2); display: flex; align-items: center; gap: 6px; }

  details.section { margin: 1.1rem 0 0; }
  details.section > summary { list-style: none; cursor: pointer; }
  details.section > summary::-webkit-details-marker { display: none; }
  details.section > summary .section-title { margin: 0 0 0.5rem; }
  details.section > summary .section-title::after { content: '▾'; margin-inline-start: auto; color: var(--muted); font-size: 0.75rem; transition: transform 0.15s; }
  details.section[open] > summary .section-title::after { transform: rotate(180deg); }
  details.section .count-badge { background: var(--panel-2); color: var(--muted); font-size: 0.72rem; padding: 1px 7px; border-radius: 20px; font-weight: 400; }

  .panel-box { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.8rem; }
  .feature-row { display: flex; justify-content: space-between; font-size: 0.85rem; padding: 5px 0; border-bottom: 1px solid var(--border); }
  .feature-row:last-child { border-bottom: none; }
  .muted { color: var(--muted); }

  .toolbar { margin-bottom: 0.6rem; }
  .toolbar input {
    width: 100%; max-width: 320px; padding: 9px 12px; border-radius: 10px;
    border: 1px solid var(--border); background: var(--panel); color: var(--text); font-size: 0.92rem;
  }
  .toolbar input:focus { outline: none; border-color: var(--accent); }
  .toolbar .hint { font-size: 0.74rem; color: var(--muted); margin-top: 5px; }

  .filter-pills { display: flex; gap: 6px; flex-wrap: wrap; margin: 0.5rem 0; }
  .filter-pills button {
    padding: 5px 12px; border-radius: 20px; border: 1px solid var(--border); background: var(--panel);
    color: var(--muted); font-size: 0.8rem; cursor: pointer; font-family: inherit;
  }
  .filter-pills button.active { background: var(--accent); color: #06130f; border-color: var(--accent); font-weight: 700; }

  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 0.6rem; }
  .user-card { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.8rem; }
  .user-card .name { font-weight: 600; font-size: 0.97rem; display: flex; align-items: center; gap: 7px; flex-wrap: wrap; }
  .dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
  .dot.online { background: var(--accent-2); box-shadow: 0 0 8px var(--accent-2); }
  .dot.offline { background: #3a5450; }
  .blocked-badge { background: rgba(255,122,107,0.15); color: var(--danger); font-size: 0.7rem; padding: 2px 7px; border-radius: 6px; }
  .user-card .phone { color: var(--muted); font-size: 0.85rem; margin-top: 2px; }
  .user-card .row { display: flex; justify-content: space-between; align-items: center; margin-top: 0.5rem; font-size: 0.82rem; }
  .user-card .row .muted { color: var(--muted); }
  .map-link, .details-link {
    display: inline-block; margin-top: 0.5rem; margin-inline-end: 0.35rem; color: var(--accent-2); text-decoration: none;
    font-size: 0.82rem; border: 1px solid rgba(79,209,197,0.3); padding: 4px 9px; border-radius: 8px;
    background: none; cursor: pointer; font-family: inherit;
  }
  .map-link:hover, .details-link:hover { background: rgba(79,209,197,0.08); }
  .empty { color: var(--muted); padding: 1.5rem 0; text-align: center; font-size: 0.88rem; }

  .feedback-item { border-bottom: 1px solid var(--border); padding: 8px 0; font-size: 0.85rem; }
  .feedback-item:last-child { border-bottom: none; }
  .feedback-item .meta { color: var(--muted); font-size: 0.75rem; margin-top: 3px; }

  .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; padding: 1rem; z-index: 50; }
  .modal-box { background: var(--panel); border: 1px solid var(--border); border-radius: 14px; padding: 1.4rem; max-width: 420px; width: 100%; max-height: 85vh; overflow-y: auto; }
  .modal-photo { width: 92px; height: 92px; border-radius: 12px; object-fit: cover; display: block; margin: 0 auto 0.9rem; background: var(--panel-2); }
  .modal-close {
    margin-top: 1.1rem; width: 100%; padding: 10px; border-radius: 10px; border: 1px solid var(--border);
    background: var(--panel-2); color: var(--text); font-size: 0.92rem; cursor: pointer;
  }
  .modal-detail-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 0.83rem; gap: 0.5rem; }

  .role-badge { font-size: 0.7rem; padding: 2px 8px; border-radius: 6px; vertical-align: middle; }
  .role-badge.super { background: rgba(233,196,106,0.18); color: var(--warn); }
  .role-badge.admin { background: rgba(79,209,197,0.15); color: var(--accent-2); }
  .activated-badge { background: rgba(79,209,197,0.15); color: var(--accent-2); font-size: 0.7rem; padding: 2px 7px; border-radius: 6px; }
  .pending-badge { background: rgba(233,196,106,0.18); color: var(--warn); font-size: 0.7rem; padding: 2px 7px; border-radius: 6px; }

  .error-banner { color: var(--danger); background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.6rem 0.9rem; margin-bottom: 0.9rem; font-size: 0.88rem; }
  .success-banner { color: var(--accent-2); background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.6rem 0.9rem; margin-bottom: 0.9rem; font-size: 0.88rem; }

  .kill-box { background: var(--panel); border: 1px solid rgba(255,122,107,0.4); border-radius: 10px; padding: 0.8rem; }
  .kill-box.active { border-color: var(--danger); background: rgba(255,122,107,0.08); }
  .kill-status { font-weight: 700; font-size: 1rem; }
  .kill-status.on { color: var(--danger); }
  .kill-status.off { color: var(--accent-2); }
  .kill-btn {
    padding: 10px 20px; border-radius: 10px; border: none; font-weight: 700; cursor: pointer; font-size: 0.92rem;
  }
  .kill-btn.turn-on { background: var(--danger); color: #1a0806; }
  .kill-btn.turn-off { background: var(--accent); color: #06130f; }

  .request-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; padding: 9px 0; border-bottom: 1px solid var(--border); }
  .request-row:last-child { border-bottom: none; }
  .request-info .name { font-weight: 600; }
  .request-info .meta { color: var(--muted); font-size: 0.78rem; }
  .request-actions form { display: inline; }
  .approve-btn, .reject-btn, .small-btn {
    padding: 6px 13px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.82rem; cursor: pointer; font-family: inherit;
  }
  .approve-btn { color: var(--accent-2); border-color: rgba(79,209,197,0.3); }
  .reject-btn { color: var(--danger); border-color: rgba(255,122,107,0.35); margin-inline-start: 6px; }

  .mini-form input, .mini-form select {
    padding: 8px 10px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.86rem; margin-inline-end: 6px; margin-bottom: 6px;
  }
  .mini-form button {
    padding: 8px 15px; border-radius: 8px; border: none; background: var(--accent); color: #06130f;
    font-weight: 700; cursor: pointer; font-size: 0.86rem;
  }

  .table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
  .admin-table { width: 100%; border-collapse: collapse; font-size: 0.86rem; min-width: 520px; }
  .admin-table th, .admin-table td { text-align: start; padding: 7px 6px; border-bottom: 1px solid var(--border); }
  .admin-table th { color: var(--muted); font-weight: 600; font-size: 0.74rem; }

  .audit-row { display: flex; justify-content: space-between; font-size: 0.83rem; padding: 5px 0; border-bottom: 1px solid var(--border); gap: 0.5rem; }
  .audit-row:last-child { border-bottom: none; }
  .audit-row .who { color: var(--accent-2); }
  .audit-row .when { color: var(--muted); font-size: 0.75rem; white-space: nowrap; }

  .own-code-box { background: var(--panel-2); border: 1px dashed var(--accent-2); border-radius: 10px; padding: 0.7rem 1rem; display: inline-block; }
  .own-code-box .code { font-family: monospace; font-size: 1.15rem; font-weight: 700; color: var(--accent-2); letter-spacing: 1px; }

  /* شاشات بحجم الهاتف */
  @media (max-width: 640px) {
    body { padding: 0.75rem; }
    .top { flex-direction: column; align-items: stretch; gap: 0.5rem; }
    .top h1 { font-size: 1.05rem; }
    .top > div { display: flex; flex-wrap: wrap; gap: 8px; }
    .top > div a { margin: 0 !important; flex: 1; text-align: center; }
    .stats { grid-template-columns: repeat(2, 1fr); gap: 0.45rem; }
    .stat { padding: 0.55rem 0.65rem; }
    .card-grid { grid-template-columns: 1fr; }
    .mini-form input, .mini-form select, .mini-form button { width: 100%; margin-inline-end: 0; }
    .mini-form { display: flex; flex-direction: column; }
    .request-row { flex-direction: column; align-items: stretch; }
    .request-actions { display: flex; gap: 6px; }
    .request-actions form { flex: 1; }
    .request-actions button { width: 100%; }
    .admin-table td form.mini-form { flex-direction: row; flex-wrap: wrap; }
    .admin-table td form.mini-form input { width: auto; flex: 1; min-width: 90px; }
    .modal-box { width: calc(100% - 1.5rem); max-width: none; }
    .toolbar input { max-width: 100%; }
  }
</style>
</head>
<body x-data="{ search: '', selected: null, filter: 'all' }">
<div class="top">
  <h1>Find Me <span>— لوحة التحكم</span>
    <span style="font-size: 0.8rem; color: var(--muted); font-weight: 400; margin-inline-start: 8px;">
      <?= htmlspecialchars($admin['name']) ?>
      <span class="role-badge <?= $isSuperAdmin ? 'super' : 'admin' ?>"><?= $isSuperAdmin ? '👑 مشرف عام' : 'مشرف' ?></span>
    </span>
  </h1>
  <div>
    <a class="details-link" href="https://github.com/ghaly4gyw-cyber/find-me/releases/download/latest/app-debug.apk" style="margin-top: 0;" download>⬇️ تحميل APK</a>
    <a class="details-link" href="../download/" target="_blank" rel="noopener" style="margin-top: 0;">🔗 صفحة التحميل</a>
    <a class="details-link" href="export-users.php" style="margin-top: 0;">📄 تصدير PDF</a>
    <?php if ($isSuperAdmin): ?>
    <a class="details-link" href="suppliers.php" style="margin-top: 0;">💼 الموردون</a>
    <?php endif; ?>
    <a class="logout" href="logout.php">تسجيل الخروج</a>
  </div>
</div>

<?php if (isset($_GET['control_saved'])): ?>
  <p class="success-banner">✅ تم الحفظ — ستصل التغييرات لكل التطبيقات عند اتصالها القادم بالإنترنت.</p>
<?php endif; ?>
<?php if (isset($_GET['admin_error'])): ?>
  <p class="error-banner">⚠️ <?= htmlspecialchars($_GET['admin_error']) ?></p>
<?php endif; ?>

<?php if ($error): ?>
  <p style="color: var(--danger);"><?= htmlspecialchars($error) ?></p>
<?php else: ?>

  <div class="stats">
    <div class="stat"><div class="n"><?= count($users) ?></div><div class="l">مسجّلون</div></div>
    <div class="stat"><div class="n"><?= $onlineCount ?></div><div class="l">متصلون الآن</div></div>
    <div class="stat"><div class="n"><?= $activatedCount ?></div><div class="l">مُفعّلون</div></div>
    <div class="stat"><div class="n"><?= $notActivatedCount ?></div><div class="l">غير مُفعّلين</div></div>
    <div class="stat"><div class="n"><?= $blockedCount ?></div><div class="l">محظورون</div></div>
    <div class="stat"><div class="n"><?= count($pendingActivations) ?></div><div class="l">طلبات قيد الانتظار</div></div>
    <div class="stat"><div class="n"><?= $totalAppOpens ?></div><div class="l">مرات الفتح (الكل)</div></div>
    <div class="stat"><div class="n"><?= htmlspecialchars(formatDuration($totalMsAcrossUsers)) ?></div><div class="l">وقت الاستخدام (الكل)</div></div>
  </div>

  <div class="section-title">حسابك</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0 0 0.5rem; font-size: 0.8rem;">رمزك للتفعيل بدون إنترنت — شاركه فقط مع شخص تثق به.</p>
    <div style="margin-bottom: 1.1rem;">
      <?php if (!empty($ownAccount['offline_code'])): ?>
        <div class="own-code-box" style="margin-bottom: 0.7rem;"><bdi class="code"><?= htmlspecialchars($ownAccount['offline_code']) ?></bdi></div>
      <?php else: ?>
        <p class="pending-badge" style="margin-bottom: 0.7rem; display: inline-block;">لم يُحدَّد رمز بعد</p>
      <?php endif; ?>
      <form method="post" action="code-save.php" class="mini-form"
            onsubmit="return confirm('تغيير رمزك لن يصل لأي جهاز إلا عند اتصاله القادم بالإنترنت، وقد يستمر جهاز يملك الرمز القديم بقبوله حتى ذلك الحين. هل تريد المتابعة؟');">
        <input type="hidden" name="username" value="<?= htmlspecialchars($admin['username']) ?>">
        <input type="text" name="code" placeholder="رمز جديد (6-16 حرفًا/رقمًا)" pattern="[A-Za-z0-9]{6,16}" required style="text-transform:uppercase; direction: ltr; text-align: right;">
        <button type="submit">تغيير رمزي</button>
      </form>
    </div>
    <form method="post" action="admin-save.php" class="mini-form">
      <input type="hidden" name="action" value="reset_password">
      <input type="hidden" name="username" value="<?= htmlspecialchars($admin['username']) ?>">
      <input type="password" name="password" placeholder="كلمة مرور جديدة (8 أحرف على الأقل)" minlength="8" required>
      <button type="submit">تغيير كلمة مروري</button>
    </form>
  </div>

  <?php if ($isSuperAdmin): ?>
  <div class="section-title">مفتاح الإيقاف الشامل</div>
  <div class="kill-box <?= !empty($appControl['kill_switch']) ? 'active' : '' ?>">
    <p class="kill-status <?= !empty($appControl['kill_switch']) ? 'on' : 'off' ?>">
      <?= !empty($appControl['kill_switch']) ? '🔴 التطبيق مُتوقف عن الجميع' : '🟢 التطبيق يعمل بشكل طبيعي' ?>
    </p>
    <details style="margin: 4px 0 0.7rem;">
      <summary class="muted" style="font-size: 0.78rem; cursor: pointer;">ما الذي يفعله هذا؟<?php if ($lastKillSwitchChange): ?> · آخر تغيير: <?= htmlspecialchars($lastKillSwitchChange['admin_name']) ?><?php endif; ?></summary>
      <p class="muted" style="font-size: 0.8rem; margin: 6px 0 0;">
        يُقفل كل هاتف متصل بالإنترنت — بما في ذلك الاستغاثة والخريطة والمراسلة — خلف شاشة قفل كاملة عند اتصاله
        القادم. مفتاح تبديل وليس إجراءً دائمًا — أعده لإيقافه فورًا متى شئت.
        <?php if ($lastKillSwitchChange): ?>
          <br>(<?= $lastKillSwitchChange['action'] === 'kill_switch_on' ? 'أوقف التطبيق' : 'أعاد تشغيل التطبيق' ?>)
          · <bdi><?= htmlspecialchars($lastKillSwitchChange['created_at']) ?></bdi>
        <?php endif; ?>
      </p>
    </details>
    <form method="post" action="kill-switch-save.php">
      <div style="margin-bottom: 0.9rem;">
        <label style="display:block; font-size:0.82rem; color: var(--muted); margin-bottom: 4px;">الرسالة المعروضة على شاشة القفل (اختياري)</label>
        <input type="text" name="kill_switch_message" maxlength="500"
               value="<?= htmlspecialchars($appControl['kill_switch_message'] ?? '') ?>"
               placeholder="مثال: التطبيق غير متاح مؤقتًا — تواصل مع غالي أو أسامة"
               style="width:100%; max-width:420px; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
      </div>
      <?php if (!empty($appControl['kill_switch'])): ?>
        <input type="hidden" name="kill_switch" value="0">
        <button type="submit" class="kill-btn turn-off" onclick="return confirm('إعادة تشغيل التطبيق للجميع؟');">🟢 إعادة تشغيل التطبيق</button>
      <?php else: ?>
        <input type="hidden" name="kill_switch" value="1">
        <button type="submit" class="kill-btn turn-on" onclick="return confirm('هذا سيقفل التطبيق عن الجميع فورًا. هل أنت متأكد؟');">🔴 إيقاف كل شيء</button>
      <?php endif; ?>
    </form>
  </div>

  <div class="section-title">التحكم في التطبيق</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0 0 0.7rem; font-size: 0.82rem;">
      <?php if ($publishedVersion): ?>
        الإصدار المنشور حاليًا: <bdi><?= htmlspecialchars((string) ($publishedVersion['versionName'] ?? '?')) ?></bdi>
        (الكود <bdi><?= htmlspecialchars((string) ($publishedVersion['versionCode'] ?? '?')) ?></bdi>)
      <?php endif; ?>
      — يصل لكل نسخة مثبّتة عند اتصالها القادم.
    </p>
    <form method="post" action="control-save.php">
      <div style="margin-bottom: 0.9rem;">
        <label style="display:block; font-size:0.82rem; color: var(--muted); margin-bottom: 4px;">رسالة لمرة واحدة (تظهر مرة واحدة لكل جهاز — اتركهما فارغين لعدم إرسال شيء)</label>
        <input type="text" name="message_title" placeholder="العنوان" maxlength="200"
               value="<?= htmlspecialchars($appControl['message_title'] ?? '') ?>"
               style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text); margin-bottom:6px;">
        <textarea name="message_body" placeholder="النص" maxlength="1000" rows="2"
                  style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text); resize:vertical;"><?= htmlspecialchars($appControl['message_body'] ?? '') ?></textarea>
      </div>
      <div style="margin-bottom: 0.9rem; display:flex; gap:1rem; flex-wrap:wrap;">
        <div style="flex:1; min-width:160px;">
          <label style="display:block; font-size:0.82rem; color: var(--muted); margin-bottom: 4px;">فرض التحديث عند كود الإصدار (0 = معطّل — لا يمنع أحدًا)</label>
          <input type="number" name="min_supported_version" min="0" step="1"
                 value="<?= htmlspecialchars((string) ($appControl['min_supported_version'] ?? 0)) ?>"
                 style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
        </div>
        <div style="flex:2; min-width:220px;">
          <label style="display:block; font-size:0.82rem; color: var(--muted); margin-bottom: 4px;">رسالة التحديث الإجباري (تظهر على شاشة القفل)</label>
          <input type="text" name="min_supported_message" maxlength="500"
                 value="<?= htmlspecialchars($appControl['min_supported_message'] ?? '') ?>"
                 style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
        </div>
      </div>
      <button type="submit" style="padding:10px 20px; border-radius:10px; border:none; background:var(--accent); color:#06130f; font-weight:700; cursor:pointer;">حفظ</button>
    </form>
  </div>
  <?php else: ?>
  <div class="section-title">التحكم في التطبيق</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0; font-size: 0.82rem;">المشرف العام فقط يمكنه تغيير مفتاح الإيقاف أو إعدادات التحديث الإجباري/الرسالة.</p>
  </div>
  <?php endif; ?>

  <div class="section-title">طلبات التفعيل قيد الانتظار <?php if ($pendingActivations): ?><span class="count-badge"><?= count($pendingActivations) ?></span><?php endif; ?></div>
  <div class="panel-box">
    <?php if (empty($pendingActivations)): ?>
      <p class="empty" style="padding: 0.4rem 0;">لا توجد طلبات قيد الانتظار.</p>
    <?php else: foreach ($pendingActivations as $r): ?>
      <div class="request-row">
        <div class="request-info">
          <div class="name"><?= htmlspecialchars($r['name']) ?></div>
          <div class="meta"><bdi><?= htmlspecialchars($r['phone']) ?></bdi> · طُلب في <bdi><?= htmlspecialchars($r['requested_at']) ?></bdi></div>
        </div>
        <div class="request-actions">
          <form method="post" action="activation-decide.php">
            <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
            <input type="hidden" name="decision" value="approve">
            <button type="submit" class="approve-btn">✅ قبول</button>
          </form>
          <form method="post" action="activation-decide.php">
            <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
            <input type="hidden" name="decision" value="reject">
            <button type="submit" class="reject-btn">🚫 رفض</button>
          </form>
        </div>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <?php if ($isSuperAdmin): ?>
  <div class="section-title">إدارة المشرفين</div>
  <div class="panel-box">
    <div class="table-scroll">
    <table class="admin-table" style="margin-bottom: 1.1rem;">
      <thead><tr><th>اسم المستخدم</th><th>الاسم</th><th>الصلاحية</th><th>رمز بدون إنترنت</th><th>إعادة تعيين كلمة المرور</th></tr></thead>
      <tbody>
        <?php foreach ($adminAccounts as $a): ?>
        <tr>
          <td><bdi><?= htmlspecialchars($a['username']) ?></bdi></td>
          <td><?= htmlspecialchars($a['name']) ?></td>
          <td><span class="role-badge <?= $a['role'] === 'super_admin' ? 'super' : 'admin' ?>"><?= $a['role'] === 'super_admin' ? 'مشرف عام' : 'مشرف' ?></span></td>
          <td>
            <?php if (!empty($a['offline_code'])): ?>
              <bdi style="font-family:monospace; color: var(--accent-2);"><?= htmlspecialchars($a['offline_code']) ?></bdi><br>
            <?php else: ?>
              <span class="muted">غير محدد</span><br>
            <?php endif; ?>
            <form method="post" action="code-save.php" class="mini-form" style="display:flex; align-items:center; margin-top:4px;"
                  onsubmit="return confirm('تغيير هذا الرمز لن يصل لأي جهاز إلا عند اتصاله القادم بالإنترنت، وقد يستمر جهاز يملك الرمز القديم بقبوله حتى ذلك الحين. هل تريد المتابعة؟');">
              <input type="hidden" name="username" value="<?= htmlspecialchars($a['username']) ?>">
              <input type="text" name="code" placeholder="رمز جديد" pattern="[A-Za-z0-9]{6,16}" required style="margin-bottom:0; width:110px; text-transform:uppercase; direction: ltr; text-align: right;">
              <button type="submit" class="small-btn">تعيين</button>
            </form>
          </td>
          <td>
            <form method="post" action="admin-save.php" class="mini-form" style="display:flex; align-items:center;">
              <input type="hidden" name="action" value="reset_password">
              <input type="hidden" name="username" value="<?= htmlspecialchars($a['username']) ?>">
              <input type="password" name="password" placeholder="كلمة مرور جديدة" minlength="8" required style="margin-bottom:0;">
              <button type="submit" class="small-btn">إعادة تعيين</button>
            </form>
          </td>
        </tr>
        <?php endforeach; ?>
      </tbody>
    </table>
    </div>
    <p class="muted" style="font-size: 0.78rem; margin-bottom: 0.6rem;">إضافة مشرف جديد — يسجّل الدخول بهذا الاسم وكلمة المرور؛ «مشرف» يمكنه حظر/فك حظر المستخدمين وقبول/رفض طلبات التفعيل؛ «مشرف عام» يحصل أيضًا على مفتاح الإيقاف والتحكم بالتطبيق وهذه الشاشة. رمز التفعيل الأولي اختياري — يمكنك دائمًا تعيينه أو تغييره لاحقًا أعلاه.</p>
    <form method="post" action="admin-save.php" class="mini-form">
      <input type="hidden" name="action" value="add_admin">
      <input type="text" name="username" placeholder="اسم المستخدم (إنجليزي)" pattern="[a-z0-9_]{3,32}" required style="direction: ltr; text-align: right;">
      <input type="text" name="name" placeholder="الاسم الكامل" maxlength="100" required>
      <input type="password" name="password" placeholder="كلمة المرور (8 أحرف على الأقل)" minlength="8" required>
      <input type="text" name="code" placeholder="رمز بدون إنترنت (اختياري)" pattern="[A-Za-z0-9]{6,16}" style="text-transform:uppercase; direction: ltr; text-align: right;">
      <select name="role">
        <option value="admin">مشرف</option>
        <option value="super_admin">مشرف عام</option>
      </select>
      <button type="submit">إضافة مشرف</button>
    </form>
  </div>
  <?php endif; ?>

  <div class="section-title">المستخدمون</div>
  <div class="toolbar">
    <input type="text" x-model="search" placeholder="ابحث بالاسم أو الهاتف أو رقم الجهاز…">
    <div class="hint">هذا البحث يشمل فقط مستخدمي Find Me المسجّلين — لا توجد طريقة للبحث عن موقع هاتف عبر رقم IMEI لدى شركة الاتصالات؛ يجب أن يكون التطبيق مثبّتًا ومسجّلًا ليظهر هنا.</div>
    <div class="filter-pills">
      <button type="button" :class="{ active: filter === 'all' }" @click="filter = 'all'">الكل</button>
      <button type="button" :class="{ active: filter === 'online' }" @click="filter = 'online'">متصل الآن</button>
      <button type="button" :class="{ active: filter === 'pending' }" @click="filter = 'pending'">غير مُفعّل</button>
      <button type="button" :class="{ active: filter === 'blocked' }" @click="filter = 'blocked'">محظور</button>
    </div>
  </div>

  <?php if (empty($users)): ?>
    <p class="empty">لم يسجّل أحد بعد.</p>
  <?php else: ?>
  <div class="card-grid">
    <?php foreach ($users as $u):
        $isOnline = $u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff;
        $isBlocked = (bool) $u['blocked'];
        $isPending = !((bool) $u['activated'] || isAdminPhone((string) $u['phone']));
        $searchBlob = strtolower($u['name'] . ' ' . $u['phone'] . ' ' . $u['device_id']);
        $matchExpr = "(filter === 'all' || (filter === 'online' && " . ($isOnline ? 'true' : 'false')
            . ") || (filter === 'pending' && " . ($isPending ? 'true' : 'false')
            . ") || (filter === 'blocked' && " . ($isBlocked ? 'true' : 'false') . "))"
            . " && (search === '' || " . json_encode($searchBlob) . ".includes(search.toLowerCase()))";
    ?>
    <div class="user-card" x-show="<?= htmlspecialchars($matchExpr) ?>">
      <div class="name">
        <span class="dot <?= $isOnline ? 'online' : 'offline' ?>"></span>
        <?= htmlspecialchars($u['name']) ?>
        <?php if ($u['blocked']): ?><span class="blocked-badge">🚫 محظور</span><?php endif; ?>
        <?php if (isAdminPhone((string) $u['phone'])): ?><span class="role-badge super">👑 مشرف</span>
        <?php elseif (isset($supplierPhones[$u['phone']])): ?><span class="role-badge admin">💼 مندوب</span>
        <?php elseif ($u['activated']): ?><span class="activated-badge">✅ مُفعّل</span>
        <?php else: ?><span class="pending-badge">⏳ غير مُفعّل</span><?php endif; ?>
      </div>
      <div class="phone"><bdi><?= htmlspecialchars($u['phone']) ?></bdi></div>
      <div class="row">
        <span class="muted">مرات الفتح</span>
        <span><?= (int) ($u['app_opens'] ?? 0) ?></span>
      </div>
      <div class="row">
        <span class="muted">الوقت في التطبيق</span>
        <span><?= htmlspecialchars(formatDuration((int) ($u['total_ms'] ?? 0))) ?></span>
      </div>
      <div class="row">
        <span class="muted">آخر ظهور</span>
        <bdi><?= htmlspecialchars($u['last_location_at'] ?? 'أبدًا') ?></bdi>
      </div>
      <?php if ($u['last_lat'] !== null && $u['last_lng'] !== null): ?>
        <a class="map-link" href="https://maps.google.com/?q=<?= urlencode((string) $u['last_lat']) ?>,<?= urlencode((string) $u['last_lng']) ?>" target="_blank" rel="noopener">📍 الخريطة</a>
      <?php endif; ?>
      <button class="details-link" @click="selected = USER_DETAILS[<?= htmlspecialchars(json_encode($u['device_id']), ENT_QUOTES, 'UTF-8') ?>]">👤 التفاصيل</button>
      <form method="post" action="block-user.php" style="display:inline;" onsubmit="return confirm(<?= $u['blocked'] ? "'فك الحظر عن هذا الشخص؟'" : "'حظر هذا الشخص؟ لن يتمكن من استخدام التطبيق حتى تفك الحظر عنه.'" ?>);">
        <input type="hidden" name="device_id" value="<?= htmlspecialchars($u['device_id']) ?>">
        <input type="hidden" name="action" value="<?= $u['blocked'] ? 'unblock' : 'block' ?>">
        <button type="submit" class="details-link" style="<?= $u['blocked'] ? 'color: var(--accent-2); border-color: rgba(79,209,197,0.3);' : 'color: var(--danger); border-color: rgba(255,122,107,0.35);' ?>">
          <?= $u['blocked'] ? '✅ فك الحظر' : '🚫 حظر' ?>
        </button>
      </form>
    </div>
    <?php endforeach; ?>
  </div>
  <?php endif; ?>

  <details class="section">
    <summary><div class="section-title">أكثر الميزات استخدامًا</div></summary>
    <div class="panel-box">
      <?php if (empty($topFeatures)): ?>
        <p class="empty" style="padding: 0.4rem 0;">لا توجد بيانات استخدام بعد.</p>
      <?php else: foreach ($topFeatures as $f): ?>
        <div class="feature-row">
          <span><?= htmlspecialchars($f['label']) ?></span>
          <span class="muted"><?= (int) $f['opens'] ?>× · <?= htmlspecialchars($f['ms_fmt']) ?></span>
        </div>
      <?php endforeach; endif; ?>
    </div>
  </details>

  <details class="section">
    <summary><div class="section-title">الأماكن العامة <?php if ($publicPlaces): ?><span class="count-badge"><?= count($publicPlaces) ?></span><?php endif; ?></div></summary>
    <div class="panel-box">
      <p class="muted" style="margin: 0 0 0.7rem; font-size: 0.82rem;">
        أماكن وضعها مستخدم كـ«مشاركة مع الفريق» عند الحفظ — نقاط ماء، معالم، مخاطر. تظهر هنا حتى لموقع بلا اتصال
        شبكي محلي بأي جهاز آخر.
      </p>
      <?php if (empty($publicPlaces)): ?>
        <p class="empty" style="padding: 0.4rem 0;">لم تُشارك أي أماكن عامة بعد.</p>
      <?php else: foreach ($publicPlaces as $pl): ?>
        <div class="feedback-item">
          <div>
            📍 <?= htmlspecialchars($pl['name']) ?>
            <a class="details-link" style="margin: 0; padding: 2px 8px; font-size: 0.76rem;"
               href="https://maps.google.com/?q=<?= urlencode((string) $pl['lat']) ?>,<?= urlencode((string) $pl['lon']) ?>"
               target="_blank" rel="noopener">الخريطة</a>
          </div>
          <div class="meta">
            — شاركه <?= htmlspecialchars($pl['shared_by_name'] ?: 'غير معروف') ?>
            · <bdi><?= htmlspecialchars($pl['updated_at']) ?></bdi>
          </div>
        </div>
      <?php endforeach; endif; ?>
    </div>
  </details>

  <details class="section">
    <summary><div class="section-title">اقتراحات المستخدمين <?php if ($feedback): ?><span class="count-badge"><?= count($feedback) ?></span><?php endif; ?></div></summary>
    <div class="panel-box">
      <?php if (empty($feedback)): ?>
        <p class="empty" style="padding: 0.4rem 0;">لا توجد اقتراحات بعد.</p>
      <?php else: foreach ($feedback as $f): ?>
        <div class="feedback-item">
          <div><?= htmlspecialchars($f['message']) ?></div>
          <div class="meta">
            — <?= htmlspecialchars($f['name'] ?: 'بدون اسم') ?>
            · <bdi><?= htmlspecialchars($f['created_at']) ?></bdi>
          </div>
        </div>
      <?php endforeach; endif; ?>
    </div>
  </details>

  <details class="section">
    <summary><div class="section-title">سجل نشاط المشرفين</div></summary>
    <div class="panel-box">
      <?php if (empty($auditLog)): ?>
        <p class="empty" style="padding: 0.4rem 0;">لا يوجد نشاط مسجّل بعد.</p>
      <?php else: foreach ($auditLog as $log): ?>
        <div class="audit-row">
          <span><span class="who"><?= htmlspecialchars($log['admin_name']) ?></span> — <?= htmlspecialchars(friendlyAuditAction($log['action'])) ?><?= $log['detail'] ? ' · ' . htmlspecialchars($log['detail']) : '' ?></span>
          <bdi class="when"><?= htmlspecialchars($log['created_at']) ?></bdi>
        </div>
      <?php endforeach; endif; ?>
    </div>
  </details>

  <details class="section">
    <summary><div class="section-title">سجل استعادة الحسابات <?php if ($reclaimLog): ?><span class="count-badge"><?= count($reclaimLog) ?></span><?php endif; ?></div></summary>
    <div class="panel-box">
      <?php if (!empty($suspiciousReclaimPhones)): ?>
        <p class="error-banner" style="margin-bottom: 0.6rem;">
          ⚠️ محاولات استعادة متكررة وفاشلة خلال 24 ساعة على:
          <?php $i = 0; foreach ($suspiciousReclaimPhones as $sPhone => $sCount): if ($i++) echo '، '; ?>
            <bdi><?= htmlspecialchars((string) $sPhone) ?></bdi> (<?= (int) $sCount ?>)
          <?php endforeach; ?>
          — قد يكون شخص يحاول تخمين رمز PIN رقم ليس رقمه.
        </p>
      <?php endif; ?>
      <?php if (empty($reclaimLog)): ?>
        <p class="empty" style="padding: 0.4rem 0;">لا توجد محاولات استعادة مسجّلة بعد.</p>
      <?php else: foreach ($reclaimLog as $r): ?>
        <div class="audit-row">
          <span>
            <bdi style="font-family:monospace;"><?= htmlspecialchars($r['phone']) ?></bdi>
            — <?= htmlspecialchars(friendlyReclaimResult($r['result'])) ?>
            <?php if ($r['old_device_id']): ?>
              <br><span class="muted" style="font-size:0.78rem;">من <bdi style="word-break:break-all;"><?= htmlspecialchars($r['old_device_id']) ?></bdi> إلى <bdi style="word-break:break-all;"><?= htmlspecialchars($r['new_device_id']) ?></bdi></span>
            <?php endif; ?>
          </span>
          <bdi class="when"><?= htmlspecialchars($r['created_at']) ?></bdi>
        </div>
      <?php endforeach; endif; ?>
    </div>
  </details>

  <div class="modal-backdrop" x-show="selected" x-cloak @click.self="selected = null">
    <div class="modal-box" @click.stop>
      <template x-if="selected">
        <div>
          <img class="modal-photo" :src="selected.photo" x-show="selected.photo" alt="">
          <h2 style="margin:0 0 4px;text-align:center;" x-text="selected.name"></h2>
          <p class="muted" style="text-align:center;margin:0 0 1rem;" x-text="selected.phone" dir="ltr"></p>
          <p x-show="selected.description" x-text="selected.description" style="font-size:0.9rem;margin-bottom:1rem;"></p>
          <div class="modal-detail-row"><span class="muted">رقم الجهاز</span><bdi style="word-break:break-all;max-width:220px;text-align:left;" x-text="selected.device_id"></bdi></div>
          <div class="modal-detail-row"><span class="muted">تاريخ التسجيل</span><bdi x-text="selected.created_at"></bdi></div>
          <div class="modal-detail-row"><span class="muted">آخر ظهور</span><bdi x-text="selected.last_seen || 'أبدًا'"></bdi></div>
          <div class="modal-detail-row"><span class="muted">مرات فتح التطبيق</span><span><span x-text="selected.app_opens"></span> مرة</span></div>
          <div class="modal-detail-row"><span class="muted">الوقت في التطبيق</span><span x-text="selected.time_spent"></span></div>
          <div class="modal-detail-row" x-show="selected.device_model"><span class="muted">الهاتف</span><bdi x-text="selected.device_model"></bdi></div>
          <div class="modal-detail-row" x-show="selected.android_version"><span class="muted">أندرويد</span><bdi x-text="selected.android_version"></bdi></div>
          <div class="modal-detail-row" x-show="selected.app_version"><span class="muted">إصدار التطبيق</span><bdi x-text="selected.app_version"></bdi></div>
          <div class="modal-detail-row"><span class="muted">الشبكة</span><span x-text="selected.network"></span></div>
          <div class="modal-detail-row" x-show="selected.blocked"><span class="muted">الحالة</span><span style="color: var(--danger);">🚫 محظور</span></div>
          <div class="modal-detail-row" x-show="selected.is_admin"><span class="muted">الصلاحية</span><span>👑 مشرف</span></div>
          <div class="modal-detail-row" x-show="!selected.is_admin"><span class="muted">مُفعّل</span><span x-text="selected.activated ? '✅ نعم' : '⏳ ليس بعد'"></span></div>
          <template x-if="selected.features && selected.features.length">
            <div style="margin-top:1rem;">
              <div class="muted" style="font-size:0.76rem;margin-bottom:6px;">الأكثر استخدامًا لدى هذا الشخص</div>
              <template x-for="f in selected.features" :key="f.key">
                <div class="feature-row">
                  <span x-text="f.label"></span>
                  <span class="muted"><span x-text="f.opens"></span>× · <span x-text="f.ms_fmt"></span></span>
                </div>
              </template>
            </div>
          </template>
          <button class="modal-close" @click="selected = null">إغلاق</button>
        </div>
      </template>
    </div>
  </div>

  <script>
    window.USER_DETAILS = <?= json_encode($detailsByDevice, JSON_HEX_TAG | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_HEX_AMP) ?>;
  </script>
<?php endif; ?>
</body>
</html>
