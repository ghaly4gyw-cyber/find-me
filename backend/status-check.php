<?php
/**
 * Find Me — per-device block-status endpoint.
 *
 * Plain GET, no auth — a device only learns its OWN status by asking with
 * its own device_id, and the answer is a single boolean, nothing else
 * about the account. Ghaly blocks/unblocks a device from the admin
 * dashboard (see admin/block-user.php); this is what the app polls to
 * find out. A device that isn't registered at all is reported as not
 * blocked — there's nothing to block yet.
 *
 * Also reports "still_registered": whether a registrations row still has
 * THIS device_id at all. A reclaim (see reclaim.php) re-points a phone
 * number's row at a new device's id, which means the old device's id simply
 * stops matching any row — this is how the old phone finds that out and
 * signs itself out (see MainActivity.enforceSessionState). A device that was
 * never registered reads as still_registered: true (nothing to sign out of).
 *
 * Also reports "activated" and "is_admin" — the account-activation gate (see
 * ActivationGate.kt / activate-with-code.php / activate-request.php). Piggy-
 * backed onto this same already-polled endpoint rather than a separate one,
 * since the app already checks in here periodically. A device that isn't
 * registered at all reads as activated: false, is_admin: false (nothing to
 * unlock yet).
 */

header('Content-Type: application/json');
require __DIR__ . '/schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'reason' => 'method_not_allowed']);
    exit;
}

$deviceId = isset($_GET['device_id']) ? trim((string) $_GET['device_id']) : '';
if ($deviceId === '' || mb_strlen($deviceId) > 64) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

$configPath = __DIR__ . '/db-config.php';
if (!file_exists($configPath)) {
    http_response_code(500);
    echo json_encode(['status' => 'error', 'reason' => 'server_not_configured']);
    exit;
}
$config = require $configPath;

try {
    $pdo = connectDb($config);
} catch (PDOException $e) {
    http_response_code(500);
    echo json_encode(['status' => 'error', 'reason' => 'db_connection_failed']);
    exit;
}

ensureSchema($pdo);

$stmt = $pdo->prepare('SELECT blocked, phone, activated FROM registrations WHERE device_id = ?');
$stmt->execute([$deviceId]);
$row = $stmt->fetch(PDO::FETCH_ASSOC);

// A device that was reclaimed away never had "registered" turn false for an
// id that was never registered in the first place — only once it HAD a row
// and that row's device_id moved on to someone else. We can't tell those two
// cases apart from this one query alone, so the app only ever treats this as
// a sign-out when it already believed itself registered (see
// ProfileStore.isRegistered + enforceSessionState) — still_registered here
// just answers "does a row exist with my id", plainly.
echo json_encode([
    'blocked' => $row !== false && (bool) $row['blocked'],
    'still_registered' => $row !== false,
    'activated' => $row !== false && (bool) $row['activated'],
    'is_admin' => $row !== false && isAdminPhone($row['phone']),
]);
