<?php
/**
 * Printable, PDF-ready export of every registered user — "every single
 * data you can bring it". Either admin can open this; it's read-only and
 * holds nothing more sensitive than what's already on screen in the
 * dashboard.
 *
 * This used to be a CSV download, but a .csv file often has no app
 * associated with it on a phone, so Ghaly couldn't open what he exported.
 * A hand-rolled PDF library on shared hosting can't be trusted to render
 * Arabic names correctly (proper Arabic shaping/RTL needs real font
 * engine support), so instead this renders a clean, printable Arabic page
 * and lets the phone's own browser do the PDF conversion via its native
 * "Save as PDF" print target — that always renders Arabic correctly,
 * because it uses the device's own text engine, not a library we wrote.
 */

require __DIR__ . '/auth.php';
requireAdminLogin();
require __DIR__ . '/../schema.php';

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

$users = $pdo->query(
    "SELECT name, phone, device_id, activated, blocked, created_at, last_location_at,
            device_model, android_version, app_version, network_type, network_name
     FROM registrations ORDER BY created_at DESC"
)->fetchAll(PDO::FETCH_ASSOC);

$generatedAt = date('Y-m-d H:i');
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>قائمة مستخدمي Find Me</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  bdi { unicode-bidi: isolate; }
  * { box-sizing: border-box; }
  body {
    font-family: "Segoe UI", Tahoma, system-ui, -apple-system, sans-serif;
    background: #f4f6f5; color: #11201d; margin: 0; padding: 1.2rem;
  }
  .toolbar { display: flex; justify-content: space-between; align-items: center; gap: 0.6rem; margin-bottom: 1rem; flex-wrap: wrap; }
  .toolbar button {
    padding: 10px 20px; border-radius: 10px; border: none; background: #2a9d8f; color: #fff;
    font-weight: 700; font-size: 0.95rem; cursor: pointer;
  }
  .toolbar a { color: #2a9d8f; font-size: 0.88rem; text-decoration: none; }
  h1 { font-size: 1.15rem; margin: 0 0 2px; }
  .meta { color: #5a706c; font-size: 0.82rem; margin: 0 0 1rem; }
  table { width: 100%; border-collapse: collapse; font-size: 0.78rem; background: #fff; }
  th, td { border: 1px solid #d6e0dd; padding: 6px 8px; text-align: start; vertical-align: top; }
  th { background: #e4efec; font-weight: 700; }
  tr:nth-child(even) td { background: #fafcfb; }
  .yes { color: #1f8a6e; font-weight: 700; }
  .no { color: #b7472a; font-weight: 700; }

  @media print {
    body { background: #fff; padding: 0; }
    .toolbar { display: none; }
    table { font-size: 10px; }
    th, td { padding: 4px 6px; }
  }
</style>
</head>
<body>
  <div class="toolbar">
    <div>
      <h1>قائمة مستخدمي Find Me</h1>
      <p class="meta">عدد المستخدمين: <?= count($users) ?> · أُنشئت في <bdi><?= htmlspecialchars($generatedAt) ?></bdi></p>
    </div>
    <div>
      <button type="button" onclick="window.print()">📄 حفظ كـ PDF</button>
      <br><a href="index.php">⬅ رجوع للوحة التحكم</a>
    </div>
  </div>

  <table>
    <thead>
      <tr>
        <th>الاسم</th>
        <th>الهاتف</th>
        <th>رقم الجهاز</th>
        <th>مُفعّل</th>
        <th>محظور</th>
        <th>تاريخ التسجيل</th>
        <th>آخر ظهور</th>
        <th>الهاتف (الموديل)</th>
        <th>أندرويد</th>
        <th>إصدار التطبيق</th>
        <th>الشبكة</th>
      </tr>
    </thead>
    <tbody>
      <?php foreach ($users as $u): ?>
      <tr>
        <td><?= htmlspecialchars($u['name']) ?></td>
        <td><bdi><?= htmlspecialchars($u['phone']) ?></bdi></td>
        <td><bdi style="word-break: break-all;"><?= htmlspecialchars($u['device_id']) ?></bdi></td>
        <td class="<?= $u['activated'] ? 'yes' : 'no' ?>"><?= $u['activated'] ? 'نعم' : 'لا' ?></td>
        <td class="<?= $u['blocked'] ? 'no' : 'yes' ?>"><?= $u['blocked'] ? 'نعم' : 'لا' ?></td>
        <td><bdi><?= htmlspecialchars($u['created_at']) ?></bdi></td>
        <td><bdi><?= htmlspecialchars($u['last_location_at'] ?? 'أبدًا') ?></bdi></td>
        <td><bdi><?= htmlspecialchars($u['device_model'] ?? '—') ?></bdi></td>
        <td><bdi><?= htmlspecialchars($u['android_version'] ?? '—') ?></bdi></td>
        <td><bdi><?= htmlspecialchars($u['app_version'] ?? '—') ?></bdi></td>
        <td><bdi><?= htmlspecialchars(trim(($u['network_type'] ?? '') . ' ' . ($u['network_name'] ?? '')) ?: '—') ?></bdi></td>
      </tr>
      <?php endforeach; ?>
    </tbody>
  </table>

  <script>
    // Opens the native print dialog automatically so "Export" is one tap
    // plus "Save as PDF" — the manual button above still works if this
    // gets dismissed or blocked by the browser.
    window.addEventListener('load', function () {
      setTimeout(function () { window.print(); }, 300);
    });
  </script>
</body>
</html>
