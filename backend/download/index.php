<?php
/**
 * Find Me — public download page.
 *
 * No login, nothing sensitive here — just the APK and a plain explanation of
 * how to install it, for anyone Ghaly shares this link with. The actual
 * app-debug.apk, version.json and control.json files in this folder are
 * generated fresh by the deploy-backend CI job on every push (see
 * .github/workflows/build.yml) — never committed to the repo.
 */

$versionName = null;
$apkSize = null;
$versionPath = __DIR__ . '/version.json';
$apkPath = __DIR__ . '/app-debug.apk';

if (file_exists($versionPath)) {
    $decoded = json_decode((string) file_get_contents($versionPath), true);
    if (is_array($decoded) && isset($decoded['versionName'])) {
        $versionName = (string) $decoded['versionName'];
    }
}
if (file_exists($apkPath)) {
    $bytes = filesize($apkPath);
    if ($bytes !== false && $bytes > 0) {
        $apkSize = round($bytes / (1024 * 1024), 1) . ' MB';
    }
}
$apkReady = file_exists($apkPath) && filesize($apkPath) > 0;
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Find Me — تحميل التطبيق</title>
<style>
  :root {
    --bg: #0c1716; --panel: #122220; --panel-2: #16302c; --border: #234743;
    --text: #e9f3f1; --muted: #7fa39d; --accent: #2a9d8f; --accent-2: #4fd1c5;
  }
  * { box-sizing: border-box; }
  body {
    font-family: "Segoe UI", Tahoma, system-ui, sans-serif;
    background: var(--bg); color: var(--text); margin: 0; padding: 1.5rem;
    display: flex; min-height: 100vh; align-items: center; justify-content: center;
  }
  .card {
    background: var(--panel); border: 1px solid var(--border); border-radius: 16px;
    padding: 2rem; max-width: 420px; width: 100%; text-align: center;
  }
  h1 { margin: 0 0 4px; font-size: 1.8rem; color: var(--accent-2); letter-spacing: 0.5px; }
  .tagline { color: var(--muted); margin: 0 0 1.5rem; font-size: 1rem; line-height: 1.6; }
  .download-btn {
    display: inline-block; background: var(--accent); color: #06130f; text-decoration: none;
    font-weight: 700; font-size: 1.05rem; padding: 14px 28px; border-radius: 12px; width: 100%;
  }
  .download-btn:hover { background: var(--accent-2); }
  .download-btn.disabled { background: var(--panel-2); color: var(--muted); pointer-events: none; }
  .meta { color: var(--muted); font-size: 0.82rem; margin-top: 10px; }
  .steps { text-align: right; margin-top: 1.8rem; font-size: 0.92rem; line-height: 1.8; }
  .steps h2 { font-size: 0.95rem; color: var(--accent-2); margin-bottom: 8px; }
  .steps ol { margin: 0; padding-right: 1.2rem; }
  hr { border: none; border-top: 1px solid var(--border); margin: 1.8rem 0; }
  .lang-block { text-align: left; direction: ltr; font-size: 0.85rem; color: var(--muted); line-height: 1.6; margin-bottom: 0.9rem; }
  .lang-block strong { color: var(--text); }
</style>
</head>
<body>
  <div class="card">
    <h1>Find Me</h1>
    <p class="tagline">تطبيق أمان مجاني لعمال الذهب في الصحراء — يعمل بدون إنترنت</p>

    <?php if ($apkReady): ?>
      <a class="download-btn" href="app-debug.apk">⬇️ تحميل التطبيق (APK)</a>
      <div class="meta">
        <?php if ($versionName): ?>الإصدار <?= htmlspecialchars($versionName) ?><?php endif; ?>
        <?php if ($apkSize): ?> · <?= htmlspecialchars($apkSize) ?><?php endif; ?>
      </div>
    <?php else: ?>
      <span class="download-btn disabled">التطبيق غير متوفر للتحميل حاليًا</span>
    <?php endif; ?>

    <div class="steps">
      <h2>طريقة التثبيت</h2>
      <ol>
        <li>اضغط على زر التحميل أعلاه.</li>
        <li>افتح الملف بعد انتهاء التحميل.</li>
        <li>إذا طلب الهاتف السماح بالتثبيت من هذا المصدر، اضغط "موافق" أو "السماح".</li>
        <li>اضغط "تثبيت" وانتظر حتى ينتهي.</li>
      </ol>
    </div>

    <hr>

    <div class="lang-block">
      <strong>English:</strong> Free, offline safety app for desert gold miners. Tap the button above to download, open the file, and allow installation from this source if your phone asks.
    </div>
    <div class="lang-block">
      <strong>Français :</strong> Application de sécurité gratuite et hors ligne pour les mineurs d'or du désert. Appuyez sur le bouton ci-dessus, ouvrez le fichier téléchargé, et autorisez l'installation depuis cette source si demandé.
    </div>
  </div>
</body>
</html>
