<?php
require __DIR__ . '/auth.php';
requireAdminLogin();
require __DIR__ . '/../schema.php';

$users = [];
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
            'SELECT name, phone, device_id, last_lat, last_lng, last_location_at, created_at
             FROM registrations ORDER BY updated_at DESC'
        )->fetchAll(PDO::FETCH_ASSOC);
    } catch (PDOException $e) {
        $error = 'Could not reach the database.';
    }
}

$onlineCutoff = time() - 15 * 60; // "online" = seen in the last 15 minutes
$onlineCount = 0;
foreach ($users as $u) {
    if ($u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff) {
        $onlineCount++;
    }
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Find Me — Admin</title>
<meta name="viewport" content="width=device-width, initial-scale=1">
<script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.14.1/dist/cdn.min.js"></script>
<style>
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

  .toolbar { margin-bottom: 1rem; }
  .toolbar input {
    width: 100%; max-width: 320px; padding: 10px 12px; border-radius: 10px;
    border: 1px solid var(--border); background: var(--panel); color: var(--text); font-size: 0.95rem;
  }
  .toolbar input:focus { outline: none; border-color: var(--accent); }

  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.75rem; }
  .user-card { background: var(--panel); border: 1px solid var(--border); border-radius: 12px; padding: 1rem; }
  .user-card .name { font-weight: 600; font-size: 1.02rem; display: flex; align-items: center; gap: 8px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; flex: none; }
  .dot.online { background: var(--accent-2); box-shadow: 0 0 8px var(--accent-2); }
  .dot.offline { background: #3a5450; }
  .user-card .phone { color: var(--muted); font-size: 0.88rem; margin-top: 2px; }
  .user-card .row { display: flex; justify-content: space-between; align-items: center; margin-top: 0.7rem; font-size: 0.85rem; }
  .user-card .row .muted { color: var(--muted); }
  .map-link {
    display: inline-block; margin-top: 0.6rem; color: var(--accent-2); text-decoration: none;
    font-size: 0.85rem; border: 1px solid rgba(79,209,197,0.3); padding: 5px 10px; border-radius: 8px;
  }
  .map-link:hover { background: rgba(79,209,197,0.08); }
  .empty { color: var(--muted); padding: 2rem 0; text-align: center; }
</style>
</head>
<body x-data="{ search: '' }">
<div class="top">
  <h1>Find Me <span>— Admin</span></h1>
  <a class="logout" href="logout.php">Log out</a>
</div>

<?php if ($error): ?>
  <p style="color: var(--danger);"><?= htmlspecialchars($error) ?></p>
<?php else: ?>

  <div class="stats">
    <div class="stat"><div class="n"><?= count($users) ?></div><div class="l">Registered</div></div>
    <div class="stat"><div class="n"><?= $onlineCount ?></div><div class="l">Seen in last 15 min</div></div>
  </div>

  <div class="toolbar">
    <input type="text" x-model="search" placeholder="Search by name or phone…">
  </div>

  <?php if (empty($users)): ?>
    <p class="empty">No one has registered yet.</p>
  <?php else: ?>
  <div class="card-grid">
    <?php foreach ($users as $u):
        $isOnline = $u['last_location_at'] !== null && strtotime($u['last_location_at']) >= $onlineCutoff;
        $searchBlob = strtolower($u['name'] . ' ' . $u['phone']);
    ?>
    <div class="user-card" x-show="search === '' || <?= htmlspecialchars(json_encode($searchBlob)) ?>.includes(search.toLowerCase())">
      <div class="name">
        <span class="dot <?= $isOnline ? 'online' : 'offline' ?>"></span>
        <?= htmlspecialchars($u['name']) ?>
      </div>
      <div class="phone"><?= htmlspecialchars($u['phone']) ?></div>
      <div class="row">
        <span class="muted">Last seen</span>
        <span><?= htmlspecialchars($u['last_location_at'] ?? 'never') ?></span>
      </div>
      <div class="row">
        <span class="muted">Registered</span>
        <span><?= htmlspecialchars($u['created_at']) ?></span>
      </div>
      <?php if ($u['last_lat'] !== null && $u['last_lng'] !== null): ?>
        <a class="map-link" href="https://maps.google.com/?q=<?= urlencode((string) $u['last_lat']) ?>,<?= urlencode((string) $u['last_lng']) ?>" target="_blank" rel="noopener">📍 View on map</a>
      <?php endif; ?>
    </div>
    <?php endforeach; ?>
  </div>
  <?php endif; ?>
<?php endif; ?>
</body>
</html>
