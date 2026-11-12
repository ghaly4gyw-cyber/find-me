<?php
/**
 * Find Me — "الموردون" (suppliers / telecom-shop partners).
 *
 * Ghaly's own words for this: "I give a little panel of the app for the
 * telecom shopman... everyone you activated, I will put on your back 500,
 * so on." A supplier gets their own offline code and their own scoped
 * login (see supplier-panel.php) to approve people or hand out their code;
 * this page is Ghaly's side of it — see all of them at a glance (who they
 * are, their code, how many people they've activated and how), and create
 * new ones. Super-admin only, same as "Manage admins" — creating one of
 * these is deliberately not something a plain admin can do.
 */

require __DIR__ . '/auth.php';
$admin = requireSuperAdmin();
require __DIR__ . '/../schema.php';

$configPath = __DIR__ . '/../db-config.php';
$suppliers = [];
$countsByUsername = [];
$activatedByUsername = [];
$error = null;

if (!file_exists($configPath)) {
    $error = 'الخادم غير مهيأ — ملف db-config.php غير موجود.';
} else {
    try {
        $config = require $configPath;
        $pdo = connectDb($config);
        ensureSchema($pdo);

        $suppliers = $pdo->query(
            "SELECT a.username, a.name, a.phone, a.offline_code, a.created_at, r.photo_base64
             FROM admin_accounts a
             LEFT JOIN registrations r ON r.phone = a.phone
             WHERE a.role = 'supplier'
             ORDER BY a.created_at ASC"
        )->fetchAll(PDO::FETCH_ASSOC);

        foreach ($pdo->query(
            "SELECT activated_by_username, activated_via, COUNT(*) AS c
             FROM registrations WHERE activated_by_username IS NOT NULL
             GROUP BY activated_by_username, activated_via"
        )->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $countsByUsername[$row['activated_by_username']][$row['activated_via']] = (int) $row['c'];
        }

        foreach ($pdo->query(
            "SELECT activated_by_username, name, phone, activated_via, updated_at
             FROM registrations WHERE activated_by_username IS NOT NULL
             ORDER BY updated_at DESC"
        )->fetchAll(PDO::FETCH_ASSOC) as $row) {
            $activatedByUsername[$row['activated_by_username']][] = $row;
        }
    } catch (PDOException $e) {
        $error = 'تعذّر الاتصال بقاعدة البيانات.';
    }
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>الموردون — Find Me</title>
<style>
  :root {
    --bg: #0c1716; --panel: #122220; --panel-2: #16302c; --border: #234743;
    --text: #e9f3f1; --muted: #7fa39d; --accent: #2a9d8f; --accent-2: #4fd1c5;
    --warn: #e9c46a; --danger: #ff7a6b;
  }
  * { box-sizing: border-box; }
  body {
    font-family: "Segoe UI", Tahoma, system-ui, -apple-system, sans-serif;
    background: var(--bg); color: var(--text); margin: 0; padding: 1rem; font-size: 0.94rem;
  }
  bdi { unicode-bidi: isolate; }
  .top { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.6rem; margin-bottom: 0.9rem; }
  .top h1 { font-size: 1.1rem; margin: 0; }
  .top h1 span { color: var(--accent-2); }
  .back, .logout { text-decoration: none; font-size: 0.85rem; border: 1px solid var(--border); padding: 5px 11px; border-radius: 8px; color: var(--accent-2); }
  .logout { color: var(--danger); border-color: rgba(255,122,107,0.35); }
  .error-banner, .success-banner { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.6rem 0.9rem; margin-bottom: 0.9rem; font-size: 0.88rem; }
  .error-banner { color: var(--danger); }
  .success-banner { color: var(--accent-2); }
  .muted { color: var(--muted); }
  .empty { color: var(--muted); padding: 1.2rem 0; text-align: center; font-size: 0.88rem; }

  .supplier-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.6rem; margin-bottom: 1.2rem; }
  .supplier-card { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.7rem 0.85rem; }
  .supplier-card .head { display: flex; align-items: center; gap: 8px; }
  .supplier-card .photo { width: 38px; height: 38px; border-radius: 8px; object-fit: cover; background: var(--panel-2); flex: none; }
  .supplier-card .photo.ph { display: flex; align-items: center; justify-content: center; font-size: 1.1rem; }
  .supplier-card .name { font-weight: 700; font-size: 0.95rem; }
  .supplier-card .username { color: var(--muted); font-size: 0.75rem; }
  .supplier-card .phone { color: var(--muted); font-size: 0.82rem; margin-top: 3px; }
  .supplier-card .code { font-family: monospace; color: var(--accent-2); font-weight: 700; letter-spacing: 1px; }
  .counts { display: flex; gap: 0.5rem; margin: 0.55rem 0; }
  .counts .c { flex: 1; background: var(--panel-2); border-radius: 8px; padding: 0.4rem; text-align: center; }
  .counts .c .n { font-size: 1.05rem; font-weight: 700; color: var(--accent-2); }
  .counts .c .l { font-size: 0.68rem; color: var(--muted); }
  details.activated-list summary { cursor: pointer; font-size: 0.8rem; color: var(--accent-2); }
  .activated-row { display: flex; justify-content: space-between; gap: 0.4rem; font-size: 0.78rem; padding: 4px 0; border-bottom: 1px solid var(--border); }
  .activated-row:last-child { border-bottom: none; }
  .activated-row .via { color: var(--muted); font-size: 0.72rem; }
  .code-form { margin-top: 0.5rem; display: flex; gap: 6px; flex-wrap: wrap; }
  .code-form input, .code-form button, .pw-form input, .pw-form button {
    padding: 6px 9px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.8rem; font-family: inherit;
  }
  .code-form button, .pw-form button { background: var(--accent); color: #06130f; border: none; font-weight: 700; cursor: pointer; }
  .pw-form { margin-top: 4px; display: flex; gap: 6px; flex-wrap: wrap; }

  .section-title { font-size: 0.86rem; font-weight: 700; margin: 1.1rem 0 0.5rem; color: var(--accent-2); }
  .panel-box { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.8rem; }
  .add-form { display: flex; flex-wrap: wrap; gap: 6px; }
  .add-form input {
    padding: 8px 10px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.86rem;
  }
  .add-form button { padding: 8px 15px; border-radius: 8px; border: none; background: var(--accent); color: #06130f; font-weight: 700; cursor: pointer; font-size: 0.86rem; }

  @media (max-width: 640px) {
    body { padding: 0.75rem; }
    .top { flex-direction: column; align-items: stretch; }
    .supplier-grid { grid-template-columns: 1fr; }
    .add-form input, .add-form button { width: 100%; }
  }
</style>
</head>
<body>

<div class="top">
  <h1>Find Me <span>— الموردون</span></h1>
  <div style="display:flex; gap:8px;">
    <a class="back" href="index.php">← لوحة التحكم</a>
    <a class="logout" href="logout.php">تسجيل الخروج</a>
  </div>
</div>

<?php if (isset($_GET['control_saved'])): ?>
  <p class="success-banner">✅ تم الحفظ.</p>
<?php endif; ?>
<?php if (isset($_GET['admin_error'])): ?>
  <p class="error-banner">⚠️ <?= htmlspecialchars($_GET['admin_error']) ?></p>
<?php endif; ?>
<?php if ($error): ?>
  <p class="error-banner"><?= htmlspecialchars($error) ?></p>
<?php else: ?>

<p class="muted" style="margin-bottom:1rem; font-size:0.85rem;">
  كل مورّد له رمز بدون إنترنت خاص به — أي شخص يُفعَّل بهذا الرمز، أو يوافق عليه المورّد من طلبات التفعيل، يُحسب له هنا.
</p>

<?php if (empty($suppliers)): ?>
  <p class="empty">لا يوجد موردون بعد — أضف أول واحد أدناه.</p>
<?php else: ?>
  <div class="supplier-grid">
    <?php foreach ($suppliers as $s):
        $byCode = $countsByUsername[$s['username']]['code'] ?? 0;
        $byApproval = $countsByUsername[$s['username']]['approval'] ?? 0;
        $list = $activatedByUsername[$s['username']] ?? [];
    ?>
    <div class="supplier-card">
      <div class="head">
        <?php if (!empty($s['photo_base64'])): ?>
          <img class="photo" src="data:image/jpeg;base64,<?= htmlspecialchars($s['photo_base64']) ?>" alt="">
        <?php else: ?>
          <div class="photo ph">💼</div>
        <?php endif; ?>
        <div>
          <div class="name"><?= htmlspecialchars($s['name']) ?></div>
          <div class="username"><bdi><?= htmlspecialchars($s['username']) ?></bdi></div>
        </div>
      </div>
      <div class="phone">📞 <bdi><?= htmlspecialchars($s['phone'] ?: 'غير محدد') ?></bdi></div>
      <div class="phone">الرمز: <?php if ($s['offline_code']): ?><bdi class="code"><?= htmlspecialchars($s['offline_code']) ?></bdi><?php else: ?><span class="muted">غير محدد</span><?php endif; ?></div>

      <div class="counts">
        <div class="c"><div class="n"><?= $byCode ?></div><div class="l">بالرمز</div></div>
        <div class="c"><div class="n"><?= $byApproval ?></div><div class="l">بالموافقة</div></div>
        <div class="c"><div class="n"><?= $byCode + $byApproval ?></div><div class="l">الإجمالي</div></div>
      </div>

      <details class="activated-list">
        <summary>من فعّلهم (<?= count($list) ?>)</summary>
        <?php if (empty($list)): ?>
          <p class="muted" style="font-size:0.78rem; margin:0.4rem 0 0;">لم يُفعّل أحد بعد.</p>
        <?php else: foreach ($list as $u): ?>
          <div class="activated-row">
            <span><?= htmlspecialchars($u['name']) ?> — <bdi><?= htmlspecialchars($u['phone']) ?></bdi></span>
            <span class="via"><?= $u['activated_via'] === 'code' ? 'بالرمز' : 'بالموافقة' ?> · <bdi><?= htmlspecialchars($u['updated_at']) ?></bdi></span>
          </div>
        <?php endforeach; endif; ?>
      </details>

      <form method="post" action="code-save.php" class="code-form"
            onsubmit="return confirm('تغيير هذا الرمز لن يصل لأي جهاز إلا عند اتصاله القادم بالإنترنت. هل تريد المتابعة؟');">
        <input type="hidden" name="username" value="<?= htmlspecialchars($s['username']) ?>">
        <input type="text" name="code" placeholder="رمز جديد" pattern="[A-Za-z0-9]{6,16}" required style="text-transform:uppercase; direction: ltr; text-align: right;">
        <button type="submit">تغيير الرمز</button>
      </form>
      <form method="post" action="admin-save.php" class="pw-form">
        <input type="hidden" name="action" value="reset_password">
        <input type="hidden" name="username" value="<?= htmlspecialchars($s['username']) ?>">
        <input type="password" name="password" placeholder="كلمة مرور جديدة" minlength="8" required>
        <button type="submit">إعادة تعيين</button>
      </form>
    </div>
    <?php endforeach; ?>
  </div>
<?php endif; ?>

<div class="section-title">إضافة مورّد جديد</div>
<div class="panel-box">
  <p class="muted" style="font-size: 0.78rem; margin-bottom: 0.6rem;">
    رقم الهاتف مطلوب — يربط حساب المورّد ببطاقته في قائمة المستخدمين (الصورة والاسم) ويظهره بشارة «💼 مندوب». يسجّل الدخول من صفحة الدخول نفسها بهذا الاسم وكلمة المرور.
  </p>
  <form method="post" action="admin-save.php" class="add-form">
    <input type="hidden" name="action" value="add_admin">
    <input type="hidden" name="role" value="supplier">
    <input type="text" name="username" placeholder="اسم المستخدم (إنجليزي)" pattern="[a-z0-9_]{3,32}" required style="direction: ltr; text-align: right;">
    <input type="text" name="name" placeholder="الاسم الكامل" maxlength="100" required>
    <input type="text" name="phone" placeholder="رقم الهاتف" required style="direction: ltr; text-align: right;">
    <input type="password" name="password" placeholder="كلمة المرور (8 أحرف على الأقل)" minlength="8" required>
    <input type="text" name="code" placeholder="رمز بدون إنترنت (اختياري)" pattern="[A-Za-z0-9]{6,16}" style="text-transform:uppercase; direction: ltr; text-align: right;">
    <button type="submit">إضافة مورّد</button>
  </form>
</div>

<?php endif; ?>
</body>
</html>
