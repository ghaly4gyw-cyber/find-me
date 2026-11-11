<?php
/**
 * Find Me — account reclaim (move a phone number to a new phone).
 *
 * The one deliberate override of register.php's "one phone number, one
 * device" rule: proves ownership with the PIN set via set-pin.php, then
 * re-points that phone number's row at the new device — which is also what
 * silently signs the OLD phone out (the next time IT calls register.php or
 * checks its session with its old device_id, that device_id is simply no
 * longer this phone number's row, so it reads as "registered elsewhere" —
 * see RegistrationUploader/MainActivity.enforceSessionState on the app
 * side). On success also hands back everything needed to restore the
 * account on the new phone: name/description/photo and the bundled
 * accounts-ledger/notes/saved-places backup (see backup-upload.php).
 *
 * Rate-limited against PIN guessing (a 4-digit PIN is only 10,000
 * combinations): 5 wrong attempts locks reclaiming that number for 15
 * minutes, reset on the next correct attempt.
 */

header('Content-Type: application/json');
require __DIR__ . '/schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'reason' => 'method_not_allowed']);
    exit;
}

$raw = file_get_contents('php://input');
$body = json_decode($raw, true);
if (!is_array($body)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'invalid_json']);
    exit;
}

$phone = isset($body['phone']) ? trim((string) $body['phone']) : '';
$pin = isset($body['pin']) ? trim((string) $body['pin']) : '';
$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';

if ($phone === '' || $pin === '' || $deviceId === ''
    || !isValidMauritanianPhone($phone) || !preg_match('/^[0-9]{4,8}$/', $pin) || mb_strlen($deviceId) > 64) {
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

$stmt = $pdo->prepare(
    'SELECT device_id, pin_hash, reclaim_attempts, reclaim_locked_until, name, description, photo_base64, backup_json, activated
     FROM registrations WHERE phone = ?'
);
$stmt->execute([$phone]);
$row = $stmt->fetch(PDO::FETCH_ASSOC);

if (!$row) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'no_such_account']);
    exit;
}
if (empty($row['pin_hash'])) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'no_pin_set']);
    exit;
}
if (!empty($row['reclaim_locked_until']) && strtotime($row['reclaim_locked_until']) > time()) {
    http_response_code(423);
    echo json_encode([
        'status' => 'error',
        'reason' => 'locked',
        'retry_after_seconds' => strtotime($row['reclaim_locked_until']) - time(),
    ]);
    exit;
}

if (!password_verify($pin, $row['pin_hash'])) {
    $attempts = (int) $row['reclaim_attempts'] + 1;
    if ($attempts >= 5) {
        $stmt = $pdo->prepare(
            'UPDATE registrations SET reclaim_attempts = ?, reclaim_locked_until = DATE_ADD(NOW(), INTERVAL 15 MINUTE) WHERE phone = ?'
        );
        $stmt->execute([$attempts, $phone]);
    } else {
        $stmt = $pdo->prepare('UPDATE registrations SET reclaim_attempts = ? WHERE phone = ?');
        $stmt->execute([$attempts, $phone]);
    }
    http_response_code(401);
    echo json_encode(['status' => 'error', 'reason' => 'wrong_pin', 'attempts_remaining' => max(0, 5 - $attempts)]);
    exit;
}

// Correct PIN — already the current device? No-op success, same response
// shape either way so the app doesn't need a special case for it.
try {
    $stmt = $pdo->prepare(
        'UPDATE registrations SET device_id = ?, reclaim_attempts = 0, reclaim_locked_until = NULL WHERE phone = ?'
    );
    $stmt->execute([$deviceId, $phone]);
} catch (PDOException $e) {
    // device_id is UNIQUE — this specific physical phone is already bound to
    // a DIFFERENT phone number's row. Deliberately not auto-resolved (that
    // would mean silently stealing this device away from its other
    // account) — surfaced plainly instead.
    http_response_code(409);
    echo json_encode(['status' => 'error', 'reason' => 'device_already_registered_to_another_number']);
    exit;
}

echo json_encode([
    'status' => 'ok',
    'name' => $row['name'],
    'phone' => $phone,
    'description' => $row['description'],
    'photo_base64' => $row['photo_base64'],
    'backup_json' => $row['backup_json'],
    // Activation state travels with the account across a reclaim — an
    // already-activated person moving to a new phone shouldn't have to ask
    // Osama/Ghaly again. See ProfileStore.applyActivationStatus.
    'activated' => (bool) $row['activated'],
    'is_admin' => isAdminPhone($phone),
]);
