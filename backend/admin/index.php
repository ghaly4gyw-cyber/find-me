<?php
require __DIR__ . '/auth.php';
$admin = requireAdminLogin();
$isSuperAdmin = ($admin['role'] ?? '') === 'super_admin';
require __DIR__ . '/../schema.php';

/** Friendly label for a screen key (the Kotlin fragment's class name, e.g.
 *  "NotesFragment") — falls back to stripping "Fragment" off the end for
 *  anything not explicitly named here, so a future screen never shows up
 *  blank. */
function friendlyFeatureName(string $key): string
{
    $map = [
        'PeersFragment' => 'Nearby / Peers',
        'RadarMapFragment' => 'Map',
        'ChatFragment' => 'Chat',
        'SosFragment' => 'SOS',
        'PostsFragment' => 'Posts',
        'PostCommentsFragment' => 'Post comments',
        'GroupsFragment' => 'Groups',
        'GroupChatFragment' => 'Group chat',
        'NotesFragment' => 'Notes',
        'CalculatorFragment' => 'Calculator',
        'AccountsFragment' => 'Accounts / Ledger',
        'AccountDetailFragment' => 'Account detail',
    ];
    if (isset($map[$key])) return $map[$key];
    return preg_replace('/Fragment$/', '', $key) ?: $key;
}

/** "Wi-Fi — Home Router" / "Cellular — Mauritel" / "Bluetooth" / "Unknown" —
 *  turns the raw network_type/network_name columns into one readable line
 *  for the dashboard, so Ghaly can tell at a glance what connection someone
 *  (Starlink, a carrier, a local Wi-Fi) was on. */
function friendlyNetwork(?string $type, ?string $name): string
{
    $labels = [
        'wifi' => 'Wi-Fi',
        'cellular' => 'Cellular',
        'bluetooth' => 'Bluetooth',
        'ethernet' => 'Ethernet',
        'other' => 'Other',
        'none' => 'Offline',
    ];
    $label = $labels[$type] ?? 'Unknown';
    if ($name) return "{$label} — {$name}";
    return $label;
}

/** "2h 15m" / "12m" / "<1m" / "0m" — durable, readable, never a raw
 *  millisecond count. */
function formatDuration(int $ms): string
{
    if ($ms <= 0) return '0m';
    $totalMinutes = intdiv($ms, 60000);
    $hours = intdiv($totalMinutes, 60);
    $minutes = $totalMinutes % 60;
    if ($hours > 0) return "{$hours}h {$minutes}m";
    if ($totalMinutes > 0) return "{$totalMinutes}m";
    return '<1m';
}

$users = [];
$feedback = [];
$publicPlaces = [];
$appControl = null;
$pendingActivations = [];
$adminAccounts = [];
$auditLog = [];
$lastKillSwitchChange = null;
$ownAccount = null;
$error = null;
$dbConfigPath = __DIR__ . '/../db-config.php';

if (!file_exists($dbConfigPath)) {
    $error = 'Server not configured — db-config.php is missing.';
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
        $adminAccounts = $pdo->query(
            'SELECT username, name, role, offline_code, created_at FROM admin_accounts ORDER BY created_at ASC'
        )->fetchAll(PDO::FETCH_ASSOC);
        $auditLog = $pdo->query(
            'SELECT admin_name, action, detail, created_at FROM admin_audit_log ORDER BY id DESC LIMIT 30'
        )->fetchAll(PDO::FETCH_ASSOC);
        $lastKillSwitchChange = $pdo->query(
            "SELECT admin_name, action, created_at FROM admin_audit_log WHERE action IN ('kill_switch_on', 'kill_switch_off') ORDER BY id DESC LIMIT 1"
        )->fetch(PDO::FETCH_ASSOC);
        $ownAccount = null;
        foreach ($adminAccounts as $a) {
            if ($a['username'] === ($admin['username'] ?? '')) {
                $ownAccount = $a;
                break;
            }
        }
    } catch (PDOException $e) {
        $error = 'Could not reach the database.';
    }
}

// Read-only reference for the form below — the version the public download
// page is currently serving (written by the deploy-backend CI job), so
// Ghaly knows which version number means "everyone currently on the latest
// build" when setting a forced-update floor.
$publishedVersion = null;
$publishedVersionPath = __DIR__ . '/../download/version.json';
if (file_exists($publishedVersionPath)) {
    $decoded = json_decode((string) file_get_contents($publishedVersionPath), true);
    if (is_array($decoded)) {
        $publishedVersion = $decoded;
    }
}

$onlineCutoff = time() - 15 * 60; // "online" = seen in the last 15 minutes
$onlineCount = 0;
$totalAppOpens = 0;
$totalMsAcrossUsers = 0;
$activatedCount = 0;
$notActivatedCount = 0;
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
<html lang="en">
<head>
<meta charset="utf-8">
<title>Find Me — Admin</title>
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
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", system-ui, sans-serif;
    background: var(--bg); color: var(--text); margin: 0; padding: 1.5rem;
  }
  .top { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.75rem; margin-bottom: 1.25rem; }
  .top h1 { font-size: 1.3rem; margin: 0; letter-spacing: 0.2px; }
  .top h1 span { color: var(--accent-2); }
  .logout { color: var(--danger); text-decoration: none; font-size: 0.9rem; border: 1px solid rgba(255,122,107,0.35); padding: 6px 12px; border-radius: 8px; }
  .logout:hover { background: rgba(255,122,107,0.1); }

  .stats { display: flex; gap: 0.75rem; flex-wrap: wrap; margin-bottom: 1.25rem; }
  .stat { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 0.9rem 1.1rem; min-width: 140px; }
  .stat .n { font-size: 1.5rem; font-weight: 700; color: var(--accent-2); }
  .stat .l { font-size: 0.78rem; color: var(--muted); text-transform: uppercase; letter-spacing: 0.4px; }

  .section-title { font-size: 0.95rem; font-weight: 600; margin: 1.6rem 0 0.75rem; color: var(--accent-2); }

  .panel-box { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 1rem; }
  .feature-row { display: flex; justify-content: space-between; font-size: 0.88rem; padding: 6px 0; border-bottom: 1px solid var(--border); }
  .feature-row:last-child { border-bottom: none; }
  .muted { color: var(--muted); }

  .toolbar { margin-bottom: 1rem; }
  .toolbar input {
    width: 100%; max-width: 320px; padding: 10px 12px; border-radius: 10px;
    border: 1px solid var(--border); background: var(--panel); color: var(--text); font-size: 0.95rem;
  }
  .toolbar input:focus { outline: none; border-color: var(--accent); }
  .toolbar .hint { font-size: 0.78rem; color: var(--muted); margin-top: 6px; }

  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.75rem; }
  .user-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 1rem; }
  .user-card .name { font-weight: 600; font-size: 1.02rem; display: flex; align-items: center; gap: 8px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
  .dot.online { background: var(--accent-2); box-shadow: 0 0 8px var(--accent-2); }
  .dot.offline { background: #3a5450; }
  .blocked-badge { background: rgba(255,122,107,0.15); color: var(--danger); font-size: 0.72rem; padding: 2px 7px; border-radius: 6px; margin-left: 6px; }
  .user-card .phone { color: var(--muted); font-size: 0.88rem; margin-top: 2px; }
  .user-card .row { display: flex; justify-content: space-between; align-items: center; margin-top: 0.7rem; font-size: 0.85rem; }
  .user-card .row .muted { color: var(--muted); }
  .map-link, .details-link {
    display: inline-block; margin-top: 0.6rem; margin-right: 0.4rem; color: var(--accent-2); text-decoration: none;
    font-size: 0.85rem; border: 1px solid rgba(79,209,197,0.3); padding: 5px 10px; border-radius: 8px;
    background: none; cursor: pointer; font-family: inherit;
  }
  .map-link:hover, .details-link:hover { background: rgba(79,209,197,0.08); }
  .empty { color: var(--muted); padding: 2rem 0; text-align: center; }

  .feedback-item { border-bottom: 1px solid var(--border); padding: 10px 0; font-size: 0.88rem; }
  .feedback-item:last-child { border-bottom: none; }
  .feedback-item .meta { color: var(--muted); font-size: 0.78rem; margin-top: 3px; }

  .modal-backdrop { position: fixed; inset: 0; background: rgba(0,0,0,0.6); display: flex; align-items: center; justify-content: center; padding: 1rem; z-index: 50; }
  .modal-box { background: var(--panel); border: 1px solid var(--border); border-radius: 14px; padding: 1.5rem; max-width: 420px; width: 100%; max-height: 85vh; overflow-y: auto; }
  .modal-photo { width: 96px; height: 96px; border-radius: 12px; object-fit: cover; display: block; margin: 0 auto 1rem; background: var(--panel-2); }
  .modal-close {
    margin-top: 1.2rem; width: 100%; padding: 10px; border-radius: 10px; border: 1px solid var(--border);
    background: var(--panel-2); color: var(--text); font-size: 0.95rem; cursor: pointer;
  }
  .modal-detail-row { display: flex; justify-content: space-between; padding: 4px 0; font-size: 0.85rem; }

  .role-badge { font-size: 0.72rem; padding: 2px 8px; border-radius: 6px; margin-left: 8px; vertical-align: middle; }
  .role-badge.super { background: rgba(233,196,106,0.18); color: var(--warn); }
  .role-badge.admin { background: rgba(79,209,197,0.15); color: var(--accent-2); }
  .activated-badge { background: rgba(79,209,197,0.15); color: var(--accent-2); font-size: 0.72rem; padding: 2px 7px; border-radius: 6px; margin-left: 6px; }
  .pending-badge { background: rgba(233,196,106,0.18); color: var(--warn); font-size: 0.72rem; padding: 2px 7px; border-radius: 6px; margin-left: 6px; }

  .error-banner { color: var(--danger); background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.7rem 1rem; margin-bottom: 1rem; }
  .success-banner { color: var(--accent-2); background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.7rem 1rem; margin-bottom: 1rem; }

  .kill-box { background: var(--panel); border: 1px solid rgba(255,122,107,0.4); border-radius: 12px; padding: 1rem; }
  .kill-box.active { border-color: var(--danger); background: rgba(255,122,107,0.08); }
  .kill-status { font-weight: 700; font-size: 1.05rem; }
  .kill-status.on { color: var(--danger); }
  .kill-status.off { color: var(--accent-2); }
  .kill-btn {
    padding: 11px 22px; border-radius: 10px; border: none; font-weight: 700; cursor: pointer; font-size: 0.95rem;
  }
  .kill-btn.turn-on { background: var(--danger); color: #1a0806; }
  .kill-btn.turn-off { background: var(--accent); color: #06130f; }

  .request-row { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 0.5rem; padding: 10px 0; border-bottom: 1px solid var(--border); }
  .request-row:last-child { border-bottom: none; }
  .request-info .name { font-weight: 600; }
  .request-info .meta { color: var(--muted); font-size: 0.8rem; }
  .request-actions form { display: inline; }
  .approve-btn, .reject-btn, .small-btn {
    padding: 6px 14px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.85rem; cursor: pointer; font-family: inherit;
  }
  .approve-btn { color: var(--accent-2); border-color: rgba(79,209,197,0.3); }
  .reject-btn { color: var(--danger); border-color: rgba(255,122,107,0.35); margin-left: 6px; }

  .mini-form input, .mini-form select {
    padding: 8px 10px; border-radius: 8px; border: 1px solid var(--border); background: var(--panel-2);
    color: var(--text); font-size: 0.88rem; margin-right: 6px; margin-bottom: 6px;
  }
  .mini-form button {
    padding: 8px 16px; border-radius: 8px; border: none; background: var(--accent); color: #06130f;
    font-weight: 700; cursor: pointer; font-size: 0.88rem;
  }

  .table-scroll { overflow-x: auto; -webkit-overflow-scrolling: touch; }
  .admin-table { width: 100%; border-collapse: collapse; font-size: 0.88rem; min-width: 520px; }
  .admin-table th, .admin-table td { text-align: left; padding: 8px 6px; border-bottom: 1px solid var(--border); }
  .admin-table th { color: var(--muted); font-weight: 600; font-size: 0.78rem; text-transform: uppercase; }

  .audit-row { display: flex; justify-content: space-between; font-size: 0.85rem; padding: 6px 0; border-bottom: 1px solid var(--border); gap: 0.5rem; }
  .audit-row:last-child { border-bottom: none; }
  .audit-row .who { color: var(--accent-2); }
  .audit-row .when { color: var(--muted); font-size: 0.78rem; white-space: nowrap; }

  .own-code-box { background: var(--panel-2); border: 1px dashed var(--accent-2); border-radius: 10px; padding: 0.8rem 1rem; display: inline-block; }
  .own-code-box .code { font-family: monospace; font-size: 1.2rem; font-weight: 700; color: var(--accent-2); letter-spacing: 1px; }

  /* Phone-sized screens — the layout above already wraps and stacks
     reasonably on its own (flex-wrap, auto-fill grid), so this only
     tightens what still looks or behaves like a desktop page on a small
     screen: a cramped top bar, side-by-side form fields that are too
     narrow to tap accurately, and tables that would otherwise force the
     whole page to scroll sideways. */
  @media (max-width: 640px) {
    body { padding: 0.9rem; }
    .top { flex-direction: column; align-items: stretch; gap: 0.6rem; }
    .top h1 { font-size: 1.1rem; }
    .top > div { display: flex; flex-wrap: wrap; gap: 8px; }
    .top > div a { margin: 0 !important; flex: 1; text-align: center; }
    .stats { gap: 0.5rem; }
    .stat { flex: 1 1 calc(50% - 0.5rem); min-width: 0; padding: 0.7rem 0.8rem; }
    .card-grid { grid-template-columns: 1fr; }
    .mini-form input, .mini-form select, .mini-form button { width: 100%; margin-right: 0; }
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
<body x-data="{ search: '', selected: null }">
<div class="top">
  <h1>Find Me <span>— Admin</span>
    <span style="font-size: 0.85rem; color: var(--muted); font-weight: 400; margin-left: 10px;">
      <?= htmlspecialchars($admin['name']) ?>
      <span class="role-badge <?= $isSuperAdmin ? 'super' : 'admin' ?>"><?= $isSuperAdmin ? '👑 Super admin' : 'Admin' ?></span>
    </span>
  </h1>
  <div>
    <a class="details-link" href="https://github.com/ghaly4gyw-cyber/find-me/releases/download/latest/app-debug.apk" style="margin-top: 0; margin-right: 8px;" download>⬇️ Download APK</a>
    <a class="details-link" href="../download/" target="_blank" rel="noopener" style="margin-top: 0; margin-right: 8px;">🔗 Download page</a>
    <a class="details-link" href="export-users.php" style="margin-top: 0; margin-right: 8px;">📄 Export CSV</a>
    <a class="logout" href="logout.php">Log out</a>
  </div>
</div>

<?php if (isset($_GET['control_saved'])): ?>
  <p class="success-banner">✅ Saved — installs will pick this up next time they're online.</p>
<?php endif; ?>
<?php if (isset($_GET['admin_error'])): ?>
  <p class="error-banner">⚠️ <?= htmlspecialchars($_GET['admin_error']) ?></p>
<?php endif; ?>

<?php if ($error): ?>
  <p style="color: var(--danger);"><?= htmlspecialchars($error) ?></p>
<?php else: ?>

  <div class="stats">
    <div class="stat"><div class="n"><?= count($users) ?></div><div class="l">Registered</div></div>
    <div class="stat"><div class="n"><?= $onlineCount ?></div><div class="l">Seen in last 15 min</div></div>
    <div class="stat"><div class="n"><?= $totalAppOpens ?></div><div class="l">App opens (all users)</div></div>
    <div class="stat"><div class="n"><?= htmlspecialchars(formatDuration($totalMsAcrossUsers)) ?></div><div class="l">Time in app (all users)</div></div>
    <div class="stat"><div class="n"><?= count($feedback) ?></div><div class="l">Suggestions received</div></div>
    <div class="stat"><div class="n"><?= $activatedCount ?></div><div class="l">Activated</div></div>
    <div class="stat"><div class="n"><?= $notActivatedCount ?></div><div class="l">Not yet activated</div></div>
    <div class="stat"><div class="n"><?= count($pendingActivations) ?></div><div class="l">Pending requests</div></div>
  </div>

  <div class="section-title">Your account</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0 0 0.6rem; font-size: 0.85rem;">Your offline activation code — entering this in the app unlocks it instantly, no internet needed. Share it only with someone you personally vouch for.</p>
    <div style="margin-bottom: 1.2rem;">
      <?php if (!empty($ownAccount['offline_code'])): ?>
        <div class="own-code-box" style="margin-bottom: 0.8rem;"><span class="code"><?= htmlspecialchars($ownAccount['offline_code']) ?></span></div>
      <?php else: ?>
        <p class="pending-badge" style="margin-bottom: 0.8rem; display: inline-block;">No code set yet</p>
      <?php endif; ?>
      <form method="post" action="code-save.php" class="mini-form"
            onsubmit="return confirm('Changing your code won\'t reach any device until it\'s next online, and a device that already has the old code may keep accepting it until then. Continue?');">
        <input type="hidden" name="username" value="<?= htmlspecialchars($admin['username']) ?>">
        <input type="text" name="code" placeholder="New code (6-16 letters/numbers)" pattern="[A-Za-z0-9]{6,16}" required style="text-transform:uppercase;">
        <button type="submit">Change my code</button>
      </form>
    </div>
    <form method="post" action="admin-save.php" class="mini-form">
      <input type="hidden" name="action" value="reset_password">
      <input type="hidden" name="username" value="<?= htmlspecialchars($admin['username']) ?>">
      <input type="password" name="password" placeholder="New password (min 8 chars)" minlength="8" required>
      <button type="submit">Change my password</button>
    </form>
  </div>

  <?php if ($isSuperAdmin): ?>
  <div class="section-title">Kill switch</div>
  <div class="kill-box <?= !empty($appControl['kill_switch']) ? 'active' : '' ?>">
    <p class="kill-status <?= !empty($appControl['kill_switch']) ? 'on' : 'off' ?>">
      <?= !empty($appControl['kill_switch']) ? '🔴 App is turned OFF for everyone' : '🟢 App is ON — working normally' ?>
    </p>
    <p class="muted" style="font-size: 0.85rem; margin: 4px 0 1rem;">
      Turning this on locks every phone that's online — including SOS, the map, and messaging — behind a full-screen
      notice the next time it checks in. A phone with no signal at all won't know until it reconnects; there's no way
      around that. This is a toggle, not permanent — flip it back off anytime to restore the app instantly.
      <?php if ($lastKillSwitchChange): ?>
        <br>Last changed by <?= htmlspecialchars($lastKillSwitchChange['admin_name']) ?>
        (<?= $lastKillSwitchChange['action'] === 'kill_switch_on' ? 'turned off' : 'turned on' ?>)
        · <?= htmlspecialchars($lastKillSwitchChange['created_at']) ?>
      <?php endif; ?>
    </p>
    <form method="post" action="kill-switch-save.php">
      <div style="margin-bottom: 1rem;">
        <label style="display:block; font-size:0.85rem; color: var(--muted); margin-bottom: 4px;">Message shown on the lock screen (optional)</label>
        <input type="text" name="kill_switch_message" maxlength="500"
               value="<?= htmlspecialchars($appControl['kill_switch_message'] ?? '') ?>"
               placeholder="e.g. App temporarily unavailable — contact Ghaly or Osama"
               style="width:100%; max-width:420px; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
      </div>
      <?php if (!empty($appControl['kill_switch'])): ?>
        <input type="hidden" name="kill_switch" value="0">
        <button type="submit" class="kill-btn turn-off" onclick="return confirm('Turn the app back on for everyone?');">🟢 Turn app back ON</button>
      <?php else: ?>
        <input type="hidden" name="kill_switch" value="1">
        <button type="submit" class="kill-btn turn-on" onclick="return confirm('This locks EVERYONE out of the app, right now. Are you sure?');">🔴 Turn everything OFF</button>
      <?php endif; ?>
    </form>
  </div>

  <div class="section-title">App control</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0 0 1rem; font-size: 0.85rem;">
      <?php if ($publishedVersion): ?>
        Currently published: version <?= htmlspecialchars((string) ($publishedVersion['versionName'] ?? '?')) ?>
        (code <?= htmlspecialchars((string) ($publishedVersion['versionCode'] ?? '?')) ?>).
      <?php endif; ?>
      This replaces editing control.json on GitHub — anything saved here reaches every installed copy the next time it's online.
    </p>
    <form method="post" action="control-save.php">
      <div style="margin-bottom: 1rem;">
        <label style="display:block; font-size:0.85rem; color: var(--muted); margin-bottom: 4px;">One-time message (shown once per device — leave both fields blank to send nothing)</label>
        <input type="text" name="message_title" placeholder="Title" maxlength="200"
               value="<?= htmlspecialchars($appControl['message_title'] ?? '') ?>"
               style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text); margin-bottom:6px;">
        <textarea name="message_body" placeholder="Body" maxlength="1000" rows="2"
                  style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text); resize:vertical;"><?= htmlspecialchars($appControl['message_body'] ?? '') ?></textarea>
      </div>
      <div style="margin-bottom: 1rem; display:flex; gap:1rem; flex-wrap:wrap;">
        <div style="flex:1; min-width:160px;">
          <label style="display:block; font-size:0.85rem; color: var(--muted); margin-bottom: 4px;">Force update at version code (0 = off — never blocks)</label>
          <input type="number" name="min_supported_version" min="0" step="1"
                 value="<?= htmlspecialchars((string) ($appControl['min_supported_version'] ?? 0)) ?>"
                 style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
        </div>
        <div style="flex:2; min-width:220px;">
          <label style="display:block; font-size:0.85rem; color: var(--muted); margin-bottom: 4px;">Forced-update message (shown on the lock screen)</label>
          <input type="text" name="min_supported_message" maxlength="500"
                 value="<?= htmlspecialchars($appControl['min_supported_message'] ?? '') ?>"
                 style="width:100%; padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:var(--panel-2); color:var(--text);">
        </div>
      </div>
      <button type="submit" style="padding:10px 20px; border-radius:10px; border:none; background:var(--accent); color:#06130f; font-weight:700; cursor:pointer;">Save</button>
    </form>
  </div>
  <?php else: ?>
  <div class="section-title">App control</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0; font-size: 0.85rem;">Only a super admin can change the kill switch or the forced-update/message settings.</p>
  </div>
  <?php endif; ?>

  <div class="section-title">Pending activation requests</div>
  <div class="panel-box">
    <?php if (empty($pendingActivations)): ?>
      <p class="empty" style="padding: 0.5rem 0;">No pending requests.</p>
    <?php else: foreach ($pendingActivations as $r): ?>
      <div class="request-row">
        <div class="request-info">
          <div class="name"><?= htmlspecialchars($r['name']) ?></div>
          <div class="meta"><?= htmlspecialchars($r['phone']) ?> · requested <?= htmlspecialchars($r['requested_at']) ?></div>
        </div>
        <div class="request-actions">
          <form method="post" action="activation-decide.php">
            <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
            <input type="hidden" name="decision" value="approve">
            <button type="submit" class="approve-btn">✅ Approve</button>
          </form>
          <form method="post" action="activation-decide.php">
            <input type="hidden" name="request_id" value="<?= (int) $r['id'] ?>">
            <input type="hidden" name="decision" value="reject">
            <button type="submit" class="reject-btn">🚫 Reject</button>
          </form>
        </div>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <?php if ($isSuperAdmin): ?>
  <div class="section-title">Manage admins</div>
  <div class="panel-box">
    <div class="table-scroll">
    <table class="admin-table" style="margin-bottom: 1.2rem;">
      <thead><tr><th>Username</th><th>Name</th><th>Role</th><th>Offline code</th><th>Reset password</th></tr></thead>
      <tbody>
        <?php foreach ($adminAccounts as $a): ?>
        <tr>
          <td><?= htmlspecialchars($a['username']) ?></td>
          <td><?= htmlspecialchars($a['name']) ?></td>
          <td><span class="role-badge <?= $a['role'] === 'super_admin' ? 'super' : 'admin' ?>"><?= $a['role'] === 'super_admin' ? 'Super admin' : 'Admin' ?></span></td>
          <td>
            <?php if (!empty($a['offline_code'])): ?>
              <span style="font-family:monospace; color: var(--accent-2);"><?= htmlspecialchars($a['offline_code']) ?></span><br>
            <?php else: ?>
              <span class="muted">none set</span><br>
            <?php endif; ?>
            <form method="post" action="code-save.php" class="mini-form" style="display:flex; align-items:center; margin-top:4px;"
                  onsubmit="return confirm('Changing this won\'t reach any device until it\'s next online, and a device with the old code may keep accepting it until then. Continue?');">
              <input type="hidden" name="username" value="<?= htmlspecialchars($a['username']) ?>">
              <input type="text" name="code" placeholder="New code" pattern="[A-Za-z0-9]{6,16}" required style="margin-bottom:0; width:120px; text-transform:uppercase;">
              <button type="submit" class="small-btn">Set</button>
            </form>
          </td>
          <td>
            <form method="post" action="admin-save.php" class="mini-form" style="display:flex; align-items:center;">
              <input type="hidden" name="action" value="reset_password">
              <input type="hidden" name="username" value="<?= htmlspecialchars($a['username']) ?>">
              <input type="password" name="password" placeholder="New password" minlength="8" required style="margin-bottom:0;">
              <button type="submit" class="small-btn">Reset</button>
            </form>
          </td>
        </tr>
        <?php endforeach; ?>
      </tbody>
    </table>
    </div>
    <p class="muted" style="font-size: 0.8rem; margin-bottom: 0.6rem;">Add a new admin — they log in with this username and password; "Admin" can block/unblock users and approve/reject activations; "Super admin" also gets the kill switch, app control, and this screen. An initial offline code is optional — you can always set or change it afterward above.</p>
    <form method="post" action="admin-save.php" class="mini-form">
      <input type="hidden" name="action" value="add_admin">
      <input type="text" name="username" placeholder="username" pattern="[a-z0-9_]{3,32}" required>
      <input type="text" name="name" placeholder="Full name" maxlength="100" required>
      <input type="password" name="password" placeholder="Password (min 8 chars)" minlength="8" required>
      <input type="text" name="code" placeholder="Offline code (optional)" pattern="[A-Za-z0-9]{6,16}" style="text-transform:uppercase;">
      <select name="role">
        <option value="admin">Admin</option>
        <option value="super_admin">Super admin</option>
      </select>
      <button type="submit">Add admin</button>
    </form>
  </div>
  <?php endif; ?>

  <div class="section-title">Most used features</div>
  <div class="panel-box">
    <?php if (empty($topFeatures)): ?>
      <p class="empty" style="padding: 0.5rem 0;">No usage data synced yet.</p>
    <?php else: foreach ($topFeatures as $f): ?>
      <div class="feature-row">
        <span><?= htmlspecialchars($f['label']) ?></span>
        <span class="muted"><?= (int) $f['opens'] ?>× · <?= htmlspecialchars($f['ms_fmt']) ?></span>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <div class="section-title">Users</div>
  <div class="toolbar">
    <input type="text" x-model="search" placeholder="Search by name, phone, or device ID…">
    <div class="hint">This searches Find Me's own registered users only — there's no way to look up the location of a phone by carrier IMEI; the app has to be installed and registered for it to show up here.</div>
  </div>

  <?php if (empty($users)): ?>
    <p class="empty">No one has registered yet.</p>
  <?php else: ?>
  <div class="card-grid">
    <?php foreach ($users as $u):
        $isOnline = $u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff;
        $searchBlob = strtolower($u['name'] . ' ' . $u['phone'] . ' ' . $u['device_id']);
    ?>
    <div class="user-card" x-show="search === '' || <?= htmlspecialchars(json_encode($searchBlob)) ?>.includes(search.toLowerCase())">
      <div class="name">
        <span class="dot <?= $isOnline ? 'online' : 'offline' ?>"></span>
        <?= htmlspecialchars($u['name']) ?>
        <?php if ($u['blocked']): ?><span class="blocked-badge">🚫 Blocked</span><?php endif; ?>
        <?php if (isAdminPhone((string) $u['phone'])): ?><span class="role-badge super">👑 Admin</span>
        <?php elseif ($u['activated']): ?><span class="activated-badge">✅ Activated</span>
        <?php else: ?><span class="pending-badge">⏳ Not activated</span><?php endif; ?>
      </div>
      <div class="phone"><?= htmlspecialchars($u['phone']) ?></div>
      <div class="row">
        <span class="muted">App opens</span>
        <span><?= (int) ($u['app_opens'] ?? 0) ?></span>
      </div>
      <div class="row">
        <span class="muted">Time in app</span>
        <span><?= htmlspecialchars(formatDuration((int) ($u['total_ms'] ?? 0))) ?></span>
      </div>
      <div class="row">
        <span class="muted">Last seen</span>
        <span><?= htmlspecialchars($u['last_location_at'] ?? 'never') ?></span>
      </div>
      <?php if ($u['last_lat'] !== null && $u['last_lng'] !== null): ?>
        <a class="map-link" href="https://maps.google.com/?q=<?= urlencode((string) $u['last_lat']) ?>,<?= urlencode((string) $u['last_lng']) ?>" target="_blank" rel="noopener">📍 Map</a>
      <?php endif; ?>
      <button class="details-link" @click="selected = USER_DETAILS[<?= htmlspecialchars(json_encode($u['device_id']), ENT_QUOTES, 'UTF-8') ?>]">👤 Details</button>
      <form method="post" action="block-user.php" style="display:inline;" onsubmit="return confirm(<?= $u['blocked'] ? "'Unblock this person?'" : "'Block this person? They won\\'t be able to use the app anymore until you unblock them.'" ?>);">
        <input type="hidden" name="device_id" value="<?= htmlspecialchars($u['device_id']) ?>">
        <input type="hidden" name="action" value="<?= $u['blocked'] ? 'unblock' : 'block' ?>">
        <button type="submit" class="details-link" style="<?= $u['blocked'] ? 'color: var(--accent-2); border-color: rgba(79,209,197,0.3);' : 'color: var(--danger); border-color: rgba(255,122,107,0.35);' ?>">
          <?= $u['blocked'] ? '✅ Unblock' : '🚫 Block' ?>
        </button>
      </form>
    </div>
    <?php endforeach; ?>
  </div>
  <?php endif; ?>

  <div class="section-title">Public places</div>
  <div class="panel-box">
    <p class="muted" style="margin: 0 0 0.75rem; font-size: 0.85rem;">
      Spots a user marked "share with team" when saving — water points, landmarks, hazards. Shows up here even for a
      site with no mesh connection to any other.
    </p>
    <?php if (empty($publicPlaces)): ?>
      <p class="empty" style="padding: 0.5rem 0;">No public places shared yet.</p>
    <?php else: foreach ($publicPlaces as $pl): ?>
      <div class="feedback-item">
        <div>
          📍 <?= htmlspecialchars($pl['name']) ?>
          <a class="details-link" style="margin: 0 0 0 8px; padding: 2px 8px; font-size: 0.78rem;"
             href="https://maps.google.com/?q=<?= urlencode((string) $pl['lat']) ?>,<?= urlencode((string) $pl['lon']) ?>"
             target="_blank" rel="noopener">Map</a>
        </div>
        <div class="meta">
          — shared by <?= htmlspecialchars($pl['shared_by_name'] ?: 'unknown') ?>
          · <?= htmlspecialchars($pl['updated_at']) ?>
        </div>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <div class="section-title">Suggestions from users</div>
  <div class="panel-box">
    <?php if (empty($feedback)): ?>
      <p class="empty" style="padding: 0.5rem 0;">No suggestions yet.</p>
    <?php else: foreach ($feedback as $f): ?>
      <div class="feedback-item">
        <div><?= htmlspecialchars($f['message']) ?></div>
        <div class="meta">
          — <?= htmlspecialchars($f['name'] ?: 'unnamed') ?>
          · <?= htmlspecialchars($f['created_at']) ?>
        </div>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <div class="section-title">Admin activity</div>
  <div class="panel-box">
    <?php if (empty($auditLog)): ?>
      <p class="empty" style="padding: 0.5rem 0;">Nothing logged yet.</p>
    <?php else: foreach ($auditLog as $log): ?>
      <div class="audit-row">
        <span><span class="who"><?= htmlspecialchars($log['admin_name']) ?></span> — <?= htmlspecialchars(str_replace('_', ' ', $log['action'])) ?><?= $log['detail'] ? ' · ' . htmlspecialchars($log['detail']) : '' ?></span>
        <span class="when"><?= htmlspecialchars($log['created_at']) ?></span>
      </div>
    <?php endforeach; endif; ?>
  </div>

  <div class="modal-backdrop" x-show="selected" x-cloak @click.self="selected = null">
    <div class="modal-box" @click.stop>
      <template x-if="selected">
        <div>
          <img class="modal-photo" :src="selected.photo" x-show="selected.photo" alt="">
          <h2 style="margin:0 0 4px;text-align:center;" x-text="selected.name"></h2>
          <p class="muted" style="text-align:center;margin:0 0 1rem;" x-text="selected.phone"></p>
          <p x-show="selected.description" x-text="selected.description" style="font-size:0.9rem;margin-bottom:1rem;"></p>
          <div class="modal-detail-row"><span class="muted">Device ID</span><span style="word-break:break-all;max-width:220px;text-align:right;" x-text="selected.device_id"></span></div>
          <div class="modal-detail-row"><span class="muted">Registered</span><span x-text="selected.created_at"></span></div>
          <div class="modal-detail-row"><span class="muted">Last seen</span><span x-text="selected.last_seen || 'never'"></span></div>
          <div class="modal-detail-row"><span class="muted">App opened</span><span><span x-text="selected.app_opens"></span> times</span></div>
          <div class="modal-detail-row"><span class="muted">Time in app</span><span x-text="selected.time_spent"></span></div>
          <div class="modal-detail-row" x-show="selected.device_model"><span class="muted">Phone</span><span x-text="selected.device_model"></span></div>
          <div class="modal-detail-row" x-show="selected.android_version"><span class="muted">Android</span><span x-text="selected.android_version"></span></div>
          <div class="modal-detail-row" x-show="selected.app_version"><span class="muted">App version</span><span x-text="selected.app_version"></span></div>
          <div class="modal-detail-row"><span class="muted">Network</span><span x-text="selected.network"></span></div>
          <div class="modal-detail-row" x-show="selected.blocked"><span class="muted">Status</span><span style="color: var(--danger);">🚫 Blocked</span></div>
          <div class="modal-detail-row" x-show="selected.is_admin"><span class="muted">Role</span><span>👑 Admin</span></div>
          <div class="modal-detail-row" x-show="!selected.is_admin"><span class="muted">Activated</span><span x-text="selected.activated ? '✅ Yes' : '⏳ Not yet'"></span></div>
          <template x-if="selected.features && selected.features.length">
            <div style="margin-top:1rem;">
              <div class="muted" style="font-size:0.78rem;text-transform:uppercase;margin-bottom:6px;">Most used by this person</div>
              <template x-for="f in selected.features" :key="f.key">
                <div class="feature-row">
                  <span x-text="f.label"></span>
                  <span class="muted"><span x-text="f.opens"></span>× · <span x-text="f.ms_fmt"></span></span>
                </div>
              </template>
            </div>
          </template>
          <button class="modal-close" @click="selected = null">Close</button>
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
