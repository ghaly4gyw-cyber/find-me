<?php
session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'secure' => true,
    'httponly' => true,
    'samesite' => 'Strict',
]);
session_start();

/** The logged-in admin's session record — ['username','name','role'] — or
 *  null if nobody's logged in. role is 'super_admin' or 'admin'. */
function currentAdmin(): ?array
{
    return $_SESSION['admin'] ?? null;
}

/** Any logged-in admin (Ghaly or Osama, or anyone Ghaly later adds). */
function requireAdminLogin(): array
{
    $admin = currentAdmin();
    if (!$admin) {
        header('Location: login.php');
        exit;
    }
    return $admin;
}

/** Super-admin only — Ghaly, unless he promotes someone else. Used to gate
 *  the kill switch, the forced-update/message control, and managing other
 *  admins' accounts — "it is up to me" (Ghaly's own words), kept as a real
 *  server-side check rather than just hiding a button in the page. */
function requireSuperAdmin(): array
{
    $admin = requireAdminLogin();
    if (($admin['role'] ?? '') !== 'super_admin') {
        http_response_code(403);
        die('هذا للمشرف العام فقط.');
    }
    return $admin;
}

/** Reads admin-config.php (rendered from the ADMIN_PASSWORD repo secret at
 *  deploy time — same pattern as db-config.php). Only ever used by
 *  ensureInitialAdmin() below, to create Ghaly's own super_admin row the
 *  very first time the panel is used against a fresh database. */
function adminConfig(): array
{
    $path = __DIR__ . '/../admin-config.php';
    if (!file_exists($path)) {
        http_response_code(500);
        die('الإدارة غير مهيأة — ملف admin-config.php غير موجود.');
    }
    return require $path;
}

/** No-ops once admin_accounts has at least one row — so a redeploy, or a
 *  password Ghaly has since changed from the panel itself, is never
 *  silently overwritten. Only a genuinely empty table (a brand-new
 *  database) gets seeded, and only with Ghaly's own super_admin account;
 *  Osama's account is added later from the "Manage admins" section, by
 *  Ghaly, from the panel — never from a secret, so there's nothing new to
 *  configure in GitHub for him. */
function ensureInitialAdmin(PDO $pdo): void
{
    $count = (int) $pdo->query('SELECT COUNT(*) FROM admin_accounts')->fetchColumn();
    if ($count > 0) {
        return;
    }
    $config = adminConfig();
    $hash = password_hash((string) $config['password'], PASSWORD_DEFAULT);
    // Seeded with the same code the Android app originally shipped with
    // (YAKMP5MS), so this move to a database-backed, panel-changeable code
    // doesn't itself change what already works on devices on day one.
    $stmt = $pdo->prepare(
        'INSERT INTO admin_accounts (username, name, password_hash, role, offline_code) VALUES (?, ?, ?, ?, ?)'
    );
    $stmt->execute(['ghaly', 'Mohamed Ghaly', $hash, 'super_admin', 'YAKMP5MS']);
}

/** One line in admin_audit_log per panel action that changes something —
 *  never the password itself, just that one changed. $detail is a short,
 *  already-safe-to-display string (a phone number, a username, a message
 *  snippet) — callers truncate/sanitize before passing it in. */
function logAdminAction(PDO $pdo, array $admin, string $action, ?string $detail = null): void
{
    $stmt = $pdo->prepare(
        'INSERT INTO admin_audit_log (admin_username, admin_name, action, detail) VALUES (?, ?, ?, ?)'
    );
    $stmt->execute([$admin['username'] ?? '', $admin['name'] ?? '', $action, $detail]);
}
