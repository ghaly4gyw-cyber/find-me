<?php
/**
 * Find Me — bulk online/offline status for a list of online contacts.
 *
 * Takes the phones from the app's locally-stored online-contacts list and
 * reports each one's current name and whether it's "online right now" — a
 * recent enough heartbeat.php ping (see $onlineWindowSeconds below). This is
 * the "just show who's online now" piece; it carries no location data.
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
$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';
$contactPhones = isset($body['phones']) && is_array($body['phones']) ? $body['phones'] : null;

if ($phone === '' || $deviceId === '' || !isValidMauritanianPhone($phone)
    || mb_strlen($deviceId) > 64 || $contactPhones === null || count($contactPhones) === 0
    || count($contactPhones) > 200) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

// Only valid-looking phone strings make it into the query — anything else is
// silently dropped rather than rejecting the whole request over one bad entry.
$contactPhones = array_values(array_unique(array_filter(
    array_map(static fn($p) => trim((string) $p), $contactPhones),
    static fn($p) => isValidMauritanianPhone($p)
)));
if (count($contactPhones) === 0) {
    echo json_encode(['status' => 'ok', 'contacts' => []]);
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

if (!requireOwnerDevice($pdo, $phone, $deviceId)) {
    exit;
}

$onlineWindowSeconds = 180; // "online now" = heartbeat within the last 3 minutes
$placeholders = implode(',', array_fill(0, count($contactPhones), '?'));
$stmt = $pdo->prepare(
    "SELECT phone, name, last_seen_at,
            TIMESTAMPDIFF(SECOND, last_seen_at, NOW()) AS seconds_since_seen
     FROM registrations WHERE phone IN ($placeholders)"
);
$stmt->execute($contactPhones);

$result = [];
while ($row = $stmt->fetch(PDO::FETCH_ASSOC)) {
    $secondsSinceSeen = $row['last_seen_at'] !== null ? (int) $row['seconds_since_seen'] : null;
    $result[] = [
        'phone' => $row['phone'],
        'name' => $row['name'],
        'online' => $secondsSinceSeen !== null && $secondsSinceSeen < $onlineWindowSeconds,
        'last_seen_seconds_ago' => $secondsSinceSeen,
    ];
}

echo json_encode(['status' => 'ok', 'contacts' => $result]);
