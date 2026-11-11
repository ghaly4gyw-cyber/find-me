<?php
require __DIR__ . '/auth.php';
requireAdminLogin();
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
$appControl = null;
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
                    r.description, r.photo_base64, r.blocked, r.created_at,
                    u.app_opens, u.total_ms, u.feature_usage
             FROM registrations r
             LEFT JOIN usage_stats u ON u.device_id = r.device_id
             ORDER BY r.updated_at DESC'
        )->fetchAll(PDO::FETCH_ASSOC);
        $feedback = $pdo->query(
            'SELECT device_id, name, message, created_at FROM feedback ORDER BY created_at DESC LIMIT 200'
        )->fetchAll(PDO::FETCH_ASSOC);
        $appControl = $pdo->query(
            'SELECT message_title, message_body, min_supported_version, min_supported_message FROM app_control WHERE id = 1'
        )->fetch(PDO::FETCH_ASSOC);
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
$aggregateFeatures = []; // key => ['opens' => n, 'ms' => n]
$detailsByDevice = [];

foreach ($users as $u) {
    if ($u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff) {
        $onlineCount++;
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
</style>
</head>
<body x-data="{ search: '', selected: null }">
<div class="top">
  <h1>Find Me <span>— Admin</span></h1>
  <div>
    <a class="details-link" href="https://github.com/ghaly4gyw-cyber/find-me/releases/download/latest/app-debug.apk" style="margin-top: 0; margin-right: 8px;" download>⬇️ Download APK</a>
    <a class="details-link" href="../download/" target="_blank" rel="noopener" style="margin-top: 0; margin-right: 8px;">🔗 Download page</a>
    <a class="logout" href="logout.php">Log out</a>
  </div>
</div>

<?php if (isset($_GET['control_saved'])): ?>
  <p style="color: var(--accent-2); background: var(--panel); border: 1px solid var(--border); border-radius: 10px; padding: 0.7rem 1rem; margin-bottom: 1rem;">✅ Saved — installs will pick this up next time they're online.</p>
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
      <button class="details-link" @click="selected = USER_DETAILS[<?= json_encode($u['device_id'], JSON_HEX_TAG | JSON_HEX_APOS | JSON_HEX_QUOT | JSON_HEX_AMP) ?>]">👤 Details</button>
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
          <div class="modal-detail-row" x-show="selected.blocked"><span class="muted">Status</span><span style="color: var(--danger);">🚫 Blocked</span></div>
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
