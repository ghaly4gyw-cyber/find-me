<?php
/**
 * Find Me — a supplier's own scoped panel.
 *
 * "This is SAS project at this point. It should be SAS." — a supplier
 * (telecom-shop partner) logs in with their own username/password here,
 * same login.php as everyone else, but sees only their own slice: their
 * code (which they can change themselves), the shared queue of people
 * waiting to be activated (so they can approve one and get credit for it),
 * and the running count/list of everyone they've activated — never the
 * full user base, never other suppliers, never the kill switch or app
 * control. index.php redirects a 'supplier' session here on its own.
 */

require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
if (($admin['role'] ?? '') !== 'supplier') {
    header('Location: index.php');
    exit;
}
require __DIR__ . '/../schema.php';

$configPath = __DIR__ . '/../db-config.php';
$ownAccount = null;
$pendingActivations = [];
$myActivated = [];
$error = null;

if (!file_exists($configPath)) {
    $error = 'الخادم غير مهيأ — ملف db-config.php غير موجود.';
} else {
    try {
        $config = require $configPath;
        $pdo = connectDb($config);
        ensureSchema($pdo);

        $stmt = $pdo->prepare('SELECT username, name, offline_code FROM admin_accounts WHERE username = ?');
        $stmt->execute([$admin['username']]);
        $ownAccount = $stmt->fetch(PDO::FETCH_ASSOC);

        $pendingActivations = $pdo->query(
            "SELECT id, phone, name, device_id, requested_at FROM activation_requests WHERE status = 'pending' ORDER BY requested_at ASC"
        )->fetchAll(PDO::FETCH_ASSOC);

        $stmt = $pdo->prepare(
            'SELECT name, phone, activated_via, updated_at FROM registrations WHERE activated_by_username = ? ORDER BY updated_at DESC'
        );
        $stmt->execute([$admin['username']]);
        $myActivated = $stmt->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        $error = 'تعذّر الاتصال بقاعدة البيانات.';
    }
}

$byCode = count(array_filter($myActivated, fn ($u) => $u['activated_via'] === 'code'));
$byApproval = count(array_filter($myActivated, fn ($u) => $u['activated_via'] === 'approval'));
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>لوحتي — Find Me</title>
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
  .logout { color: var(--danger); text-decoration: none; font-size: 0.85rem; border: 1px solid rgba(255,122,107,0.35); padding: 5px 11px; border-radius: 8px; }
  .error-banner, .success-banner { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.6rem 0.9rem; margin-bottom: 0.9rem; font-size: 0.88rem; }
  .error-banner { color: var(--danger); }
  .success-banner { color: var(--accent-2); }
  .muted { color: var(--muted); }
  .empty { color: var(--muted); padding: 1.2rem 0; text-align: center; font-size: 0.88rem; }
  .section-title { font-size: 0.86rem; font-weight: 700; margin: 1.1rem 0 0.5rem; color: var(--accent-2); }
  .panel-box { background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.8rem; }

  .own-code-box { background: var(--panel-2); border: 1px dashed var(--accent-2); border-radius: 10px; padding: 0.7rem 1rem; display: inline-block; }
  .own-code-box .code { font-family: monospace; font-size: 1.15rem; font-weight: 700; color: var(--accent-2); letter-spacing: 1px; }
  .mini-form input, .mini-form button { margin-top: 0.5rem; display: block; }
  .mini-form input {
    padding: 8px 10px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.86rem;
  }
  .mini-form button { padding: 8px 15px; border-radius: 8px; border: none; background: var(--accent); color: #06130f; font-weight: 700; cursor: pointer; font-size: 0.86rem; }

  .counts { display: flex; gap: 0.5rem; }
  .counts .c { flex: 1; background: var(--panel-2); border-radius: 10px; padding: 0.7rem; text-align: center; }
  .counts .c .n { font-size: 1.4rem; font-weight: 700; color: var(--accent-2); }
  .counts .c .l { font-size: 0.72rem; color: var(--muted); }

  .request-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; padding: 9px 0; border-bottom: 1px solid var(--border); }
  .request-row:last-child { border-bottom: none; }
  .request-info .name { font-weight: 600; }
  .request-info .meta { color: var(--muted); font-size: 0.78rem; }
  .approve-btn, .reject-btn {
    padding: 6px 13px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.82rem; cursor: pointer; font-family: inherit;
  }
  .approve-btn { color: var(--accent-2); border-color: rgba(79,209,197,0.3); }
  .reject-btn { color: var(--danger); border-color: rgba(255,122,107,0.35); margin-inline-start: 6px; }

  .activated-row { display: flex; justify-content: space-between; gap: 0.4rem; font-size: 0.82rem; padding: 6px 0; border-bottom: 1px solid var(--border); }
  .activated-row:last-child { border-bottom: none; }
  .activated-row .via { color: var(--muted); font-size: 0.74rem; }

  @media (max-width: 640px) {
    body { padding: 0.75rem; }
    .top { flex-direction: column; align-items: stretch; }
    .request-row { flex-direction: column; align-items: stretch; }
  }
</style>
</head>
<body>

<div class="top">
  <h1>Find Me <span>— لوحتي</span>
    <span style="font-size: 0.8rem; color: var(--muted); font-weight: 400; margin-inline-start: 8px;"><?= htmlspecialchars($admin['name']) ?></span>
  </h1>
  <a class="logout" href="logout.php">تسجيل الخروج</a>
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

<div class="section-title">رمزك بدون إنترنت</div>
<div class="panel-box">
  <p class="muted" style="margin-top:0; font-size:0.85rem;">أي شخص يُدخل هذا الرمز في التطبيق يُفعَّل فورًا ويُحسب لك.</p>
  <?php if (!empty($ownAccount['offline_code'])): ?>
    <div class="own-code-box" style="margin-bottom: 0.7rem;"><bdi class="code"><?= htmlspecialchars($ownAccount['offline_code']) ?></bdi></div>
  <?php else: ?>
    <p class="muted" style="margin-bottom: 0.7rem;">لم يُحدَّد رمز بعد.</p>
  <?php endif; ?>
  <form method="post" action="code-save.php" class="mini-form"
        onsubmit="return confirm('تغيير هذا الرمز لن يصل لأي جهاز إلا عند اتصاله القادم بالإنترنت. هل تريد المتابعة؟');">
    <input type="hidden" name="username" value="<?= htmlspecialchars($admin['username']) ?>">
    <input type="text" name="code" placeholder="رمز جديد (6-16 حرفًا/رقمًا)" pattern="[A-Za-z0-9]{6,16}" required style="text-transform:uppercase; direction: ltr; text-align: right;">
    <button type="submit">تغيير الرمز</button>
  </form>
</div>

<div class="section-title">عدد من فعّلتهم</div>
<div class="counts">
  <div class="c"><div class="n"><?= $byCode ?></div><div class="l">بالرمز</div></div>
  <div class="c"><div class="n"><?= $byApproval ?></div><div class="l">بالموافقة</div></div>
  <div class="c"><div class="n"><?= $byCode + $byApproval ?></div><div class="l">الإجمالي</div></div>
</div>

<div class="section-title">طلبات التفعيل قيد الانتظار</div>
<div class="panel-box">
  <?php if (empty($pendingActivations)): ?>
    <p class="empty">لا توجد طلبات قيد الانتظار.</p>
  <?php else: foreach ($pendingActivations as $r): ?>
    <div class="request-row">
      <div class="request-info">
        <div class="name"><?= htmlspecialchars($r['name']) ?></div>
        <div class="meta"><bdi><?= htmlspecialchars($r['phone']) ?></bdi> · طُلب في <bdi><?= htmlspecialchars($r['requested_at']) ?></bdi></div>
      </div>
      <div>
        <form method="post" action="activation-decide.php" style="display:inline;">
          <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
          <input type="hidden" name="decision" value="approve">
          <button type="submit" class="approve-btn">✅ قبول</button>
        </form>
        <form method="post" action="activation-decide.php" style="display:inline;">
          <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
          <input type="hidden" name="decision" value="reject">
          <button type="submit" class="reject-btn">رفض</button>
        </form>
      </div>
    </div>
  <?php endforeach; endif; ?>
</div>

<div class="section-title">الأشخاص الذين فعّلتهم (<?= count($myActivated) ?>)</div>
<div class="panel-box">
  <?php if (empty($myActivated)): ?>
    <p class="empty">لم تُفعّل أحدًا بعد.</p>
  <?php else: foreach ($myActivated as $u): ?>
    <div class="activated-row">
      <span><?= htmlspecialchars($u['name']) ?> — <bdi><?= htmlspecialchars($u['phone']) ?></bdi></span>
      <span class="via"><?= $u['activated_via'] === 'code' ? 'بالرمز' : 'بالموافقة' ?> · <bdi><?= htmlspecialchars($u['updated_at']) ?></bdi></span>
    </div>
  <?php endforeach; endif; ?>
</div>

<?php endif; ?>
</body>
</html>
