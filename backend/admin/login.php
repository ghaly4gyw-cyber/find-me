<?php
require __DIR__ . '/auth.php';
require __DIR__ . '/../schema.php';

$error = null;
if ($_SERVER['REQUEST_METHOD'] === 'POST') {
    $configPath = __DIR__ . '/../db-config.php';
    if (!file_exists($configPath)) {
        http_response_code(500);
        die('الخادم غير مهيأ — ملف db-config.php غير موجود.');
    }
    $config = require $configPath;

    try {
        $pdo = connectDb($config);
        ensureSchema($pdo);
        ensureInitialAdmin($pdo);

        $username = strtolower(trim((string) ($_POST['username'] ?? '')));
        $submitted = (string) ($_POST['password'] ?? '');

        $stmt = $pdo->prepare('SELECT username, name, password_hash, role FROM admin_accounts WHERE username = ?');
        $stmt->execute([$username]);
        $row = $stmt->fetch(PDO::FETCH_ASSOC);

        if ($row && password_verify($submitted, (string) $row['password_hash'])) {
            session_regenerate_id(true);
            $_SESSION['admin'] = [
                'username' => $row['username'],
                'name' => $row['name'],
                'role' => $row['role'],
            ];
            header('Location: index.php');
            exit;
        }
        $error = 'اسم المستخدم أو كلمة المرور غير صحيحة.';
    } catch (PDOException $e) {
        $error = 'تعذّر الاتصال بقاعدة البيانات.';
    }
}
?>
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8">
<title>Find Me — لوحة التحكم</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
  :root {
    --bg: #0c1716; --panel: #122220; --border: #234743;
    --text: #e9f3f1; --muted: #7fa39d; --accent: #2a9d8f; --accent-2: #4fd1c5; --danger: #ff7a6b;
  }
  * { box-sizing: border-box; }
  body {
    font-family: "Segoe UI", Tahoma, system-ui, -apple-system, sans-serif;
    background: var(--bg); color: var(--text); display: flex; align-items: center;
    justify-content: center; min-height: 100vh; margin: 0; padding: 1rem;
  }
  form {
    background: var(--panel); border: 1px solid var(--border); padding: 1.6rem;
    border-radius: 14px; width: 100%; max-width: 300px; box-shadow: 0 8px 32px rgba(0,0,0,0.4);
  }
  h1 { font-size: 1.2rem; margin: 0 0 1rem; text-align: center; }
  h1 span { color: var(--accent-2); }
  label { font-size: 0.8rem; color: var(--muted); }
  input {
    width: 100%; padding: 10px 12px; margin-top: 5px; margin-bottom: 14px;
    border-radius: 8px; border: 1px solid var(--border); background: var(--bg);
    color: var(--text); box-sizing: border-box; font-size: 1rem; direction: ltr; text-align: right;
  }
  input:focus { outline: none; border-color: var(--accent); }
  button {
    width: 100%; padding: 11px; border: 0; border-radius: 8px; background: var(--accent);
    color: #fff; font-weight: 600; cursor: pointer; font-size: 0.98rem;
  }
  button:hover { background: var(--accent-2); color: #0c1716; }
  .error { color: var(--danger); margin: -0.4rem 0 1rem; font-size: 0.85rem; }
</style>
</head>
<body>
<form method="post">
  <h1>Find Me <span>— لوحة التحكم</span></h1>
  <?php if ($error): ?><p class="error"><?= htmlspecialchars($error) ?></p><?php endif; ?>
  <label for="username">اسم المستخدم</label>
  <input type="text" id="username" name="username" autocomplete="username" autofocus required>
  <label for="password">كلمة المرور</label>
  <input type="password" id="password" name="password" autocomplete="current-password" required>
  <button type="submit">تسجيل الدخول</button>
</form>
</body>
</html>
