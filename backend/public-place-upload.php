<?php
/**
 * Find Me — public saved-places sync endpoint.
 *
 * Receives {device_id, places: [{place_id, name, lat, lon, shared_by_name,
 * ts}, ...]} from the app whenever it's online — every place on that phone
 * that the person explicitly marked "share with team" at save time (see
 * PublicPlaceUploader.kt). Never anything the person didn't choose to share;
 * a private saved place never reaches this endpoint at all.
 *
 * Upserted by place_id, same idea as register.php/usage-update.php: the
 * latest copy of a place (its name, in case it was renamed) always replaces
 * what's stored, since the app itself is the source of truth.
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

$deviceId = isset($body['device_id']) ? trim((string) $body['device_id']) : '';
$places = isset($body['places']) && is_array($body['places']) ? $body['places'] : null;

if ($deviceId === '' || mb_strlen($deviceId) > 64 || $places === null || count($places) === 0) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

// Bounded generously — this is a handful of named spots per phone, never a
// large or unbounded list.
if (count($places) > 200) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'too_many_places']);
    exit;
}

$rows = [];
foreach ($places as $p) {
    if (!is_array($p)) continue;
    $placeId = isset($p['place_id']) ? trim((string) $p['place_id']) : '';
    $name = isset($p['name']) ? trim((string) $p['name']) : '';
    $lat = isset($p['lat']) && is_numeric($p['lat']) ? (float) $p['lat'] : null;
    $lon = isset($p['lon']) && is_numeric($p['lon']) ? (float) $p['lon'] : null;
    $sharedByName = isset($p['shared_by_name']) ? trim((string) $p['shared_by_name']) : '';
    $ts = isset($p['ts']) && is_numeric($p['ts']) ? (int) $p['ts'] : null;

    if ($placeId === '' || mb_strlen($placeId) > 64
        || $name === '' || mb_strlen($name) > 100
        || $lat === null || $lat < -90 || $lat > 90
        || $lon === null || $lon < -180 || $lon > 180
        || $ts === null) {
        continue; // skip just this one malformed entry, not the whole batch
    }
    $rows[] = [
        'place_id' => $placeId,
        'name' => $name,
        'lat' => $lat,
        'lon' => $lon,
        'shared_by_name' => $sharedByName !== '' ? mb_substr($sharedByName, 0, 100) : null,
        'ts' => $ts,
    ];
}

if (count($rows) === 0) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'no_valid_places']);
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
    'INSERT INTO public_places (place_id, device_id, name, lat, lon, shared_by_name, place_ts)
     VALUES (?, ?, ?, ?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
        name = VALUES(name),
        lat = VALUES(lat),
        lon = VALUES(lon),
        shared_by_name = VALUES(shared_by_name),
        place_ts = VALUES(place_ts)'
);
foreach ($rows as $row) {
    $stmt->execute([
        $row['place_id'], $deviceId, $row['name'], $row['lat'], $row['lon'],
        $row['shared_by_name'], $row['ts'],
    ]);
}

echo json_encode(['status' => 'ok', 'synced' => count($rows)]);
