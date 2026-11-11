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
  .steps { text-align: right; margin-top: 1.8rem; font-size: 0.92rem; line-height: 1.7; }
  .steps h2, .faq h2 { font-size: 1rem; color: var(--accent-2); margin: 0 0 10px; }
  .step-item { display: flex; gap: 10px; align-items: flex-start; margin-bottom: 14px; }
  .step-num {
    flex: none; width: 26px; height: 26px; border-radius: 50%; background: var(--accent);
    color: #06130f; font-weight: 700; font-size: 0.85rem; display: flex; align-items: center;
    justify-content: center; margin-top: 1px;
  }
  .step-text { flex: 1; }
  .step-item.warn {
    background: rgba(233,196,106,0.1); border: 1px solid rgba(233,196,106,0.35);
    border-radius: 10px; padding: 10px 12px;
  }
  .step-item.warn .step-num { background: none; font-size: 1.1rem; width: auto; height: auto; }
  .step-item.warn ul { margin: 8px 0 0; padding-right: 1.1rem; }
  .step-item.warn li { margin-bottom: 6px; }
  .faq { text-align: right; margin-top: 1.6rem; font-size: 0.88rem; }
  .faq details {
    background: var(--panel-2); border: 1px solid var(--border); border-radius: 10px;
    padding: 9px 12px; margin-bottom: 8px;
  }
  .faq summary { cursor: pointer; font-weight: 600; }
  .faq p { margin: 8px 0 0; color: var(--muted); line-height: 1.7; }
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
      <h2>🧭 دليل التثبيت خطوة بخطوة</h2>

      <div class="step-item">
        <div class="step-num">1</div>
        <div class="step-text">اضغط على زر <strong>"تحميل التطبيق"</strong> في الأعلى، وانتظر حتى ينتهي التحميل.</div>
      </div>

      <div class="step-item">
        <div class="step-num">2</div>
        <div class="step-text">
          <strong>افتح الملف</strong> بعد انتهاء التحميل. إذا لم يُفتح تلقائيًا: اسحب شريط الإشعارات من أعلى الشاشة
          وابحث عن الملف، أو افتح تطبيق <strong>"الملفات" (Files)</strong> ثم مجلد <strong>"التنزيلات" (Download)</strong>.
        </div>
      </div>

      <div class="step-item warn">
        <div class="step-num">⚠️</div>
        <div class="step-text">
          <strong>قد تظهر رسالة تحذير تقول إن التطبيق "غير معروف" أو أن Play Protect يحظره.</strong>
          هذا طبيعي تمامًا — يحدث مع أي تطبيق لم يُنزَّل من متجر Google Play، وليس دليلًا على وجود مشكلة في هذا التطبيق:
          <ul>
            <li>إذا ظهرت رسالة <em>"هل تريد السماح لهذا التطبيق بتثبيت تطبيقات أخرى؟"</em> اضغط <strong>"السماح" (Allow)</strong>.</li>
            <li>إذا ظهرت رسالة من <strong>Play Protect</strong> تقول <em>"تم الحظر"</em> أو <em>"تطبيق غير آمن"</em>، اضغط
              <strong>"مزيد من التفاصيل" (More details)</strong> ثم <strong>"التثبيت على أي حال" (Install anyway)</strong>.</li>
          </ul>
        </div>
      </div>

      <div class="step-item">
        <div class="step-num">3</div>
        <div class="step-text">اضغط <strong>"تثبيت"</strong>، وانتظر قليلًا حتى تظهر كلمة <strong>"تم"</strong>، ثم اضغط <strong>"فتح"</strong>. انتهى — التطبيق جاهز.</div>
      </div>
    </div>

    <div class="faq">
      <h2>❓ إذا واجهت مشكلة</h2>
      <details>
        <summary>لم يحدث شيء بعد الضغط على زر التحميل</summary>
        <p>تأكد من اتصالك بالإنترنت ثم أعد المحاولة. إذا استمرت المشكلة، جرّب فتح هذه الصفحة بمتصفح Chrome إذا كنت تستخدم متصفحًا آخر.</p>
      </details>
      <details>
        <summary>الهاتف يرفض التثبيت باستمرار ولا تساعد الخطوة ⚠️ أعلاه</summary>
        <p>
          اذهب إلى <strong>الإعدادات ⟵ التطبيقات ⟵ وصول خاص (أو "صلاحيات خاصة")  ⟵ تثبيت التطبيقات غير المعروفة</strong>،
          اختر المتصفح الذي استخدمته للتحميل (مثل Chrome)، وفعّل <strong>"السماح من هذا المصدر"</strong>.
          بعدها ارجع وافتح الملف المحمَّل مرة أخرى وثبّته.
          (قد تختلف أسماء هذه القوائم قليلًا حسب نوع الهاتف.)
        </p>
      </details>
      <details>
        <summary>أحتاج مساعدة شخص آخر</summary>
        <p>أرسل رابط هذه الصفحة لشخص تثق به ليساعدك خطوة بخطوة، أو اطلب من الشخص الذي أرسل لك هذا الرابط أن يساعدك.</p>
      </details>
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
