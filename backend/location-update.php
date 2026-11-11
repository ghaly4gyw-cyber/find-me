<?php
/**
 * Find Me — opportunistic location upload.
 *
 * Receives {device_id, lat, lng} from an already-registered device whenever
 * it's online. This is the anti-theft feature: if a registered phone is
 * lost or stolen, its owner's last-known location is visible on the admin
 * dashboard (see admin/index.php). Disclosed to the person at registration
 * time — see the app's registration screen copy.
 *
 * Only updates an existing registrations row; a device that hasn't
 * successfully registered server-side yet has nothing to attach a location
 * to, so that's rejected rather than silently creating a bare row.
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
$lat = isset($body['lat']) && is_numeric($body['lat']) ? (float) $body['lat'] : null;
$lng = isset($body['lng']) && is_numeric($body['lng']) ? (float) $body['lng'] : null;

if ($deviceId === '' || mb_strlen($deviceId) > 64
    || $lat === null || $lng === null
    || $lat < -90 || $lat > 90 || $lng < -180 || $lng > 180) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'reason' => 'missing_or_invalid_fields']);
    exit;
}

// Optional device/network snapshot — sent opportunistically by the app
// alongside each location sync so the admin can see what kind of device
// and connection a registered phone is using. All optional and bounded;
// a missing or oversized value is just dropped rather than rejecting the
// whole location update.
$deviceModel = isset($body['device_model']) ? trim((string) $body['device_model']) : '';
$androidVersion = isset($body['android_version']) ? trim((string) $body['android_version']) : '';
$appVersion = isset($body['app_version']) ? trim((string) $body['app_version']) : '';
$networkType = isset($body['network_type']) ? trim((string) $body['network_type']) : '';
$networkName = isset($body['network_name']) ? trim((string) $body['network_name']) : '';

if (mb_strlen($deviceModel) > 150) $deviceModel = mb_substr($deviceModel, 0, 150);
if (mb_strlen($androidVersion) > 50) $androidVersion = mb_substr($androidVersion, 0, 50);
if (mb_strlen($appVersion) > 50) $appVersion = mb_substr($appVersion, 0, 50);
if (mb_strlen($networkType) > 20) $networkType = mb_substr($networkType, 0, 20);
if (mb_strlen($networkName) > 100) $networkName = mb_substr($networkName, 0, 100);

$deviceModel = $deviceModel !== '' ? $deviceModel : null;
$androidVersion = $androidVersion !== '' ? $androidVersion : null;
$appVersion = $appVersion !== '' ? $appVersion : null;
$networkType = $networkType !== '' ? $networkType : null;
$networkName = $networkName !== '' ? $networkName : null;

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

// device_model/android_version/app_version/network_type/network_name only
// overwrite when actually sent (COALESCE keeps the existing value otherwise),
// same pattern as description/photo_base64 in register.php — an older app
// build that doesn't send these yet never wipes out values a newer sync
// already stored.
$stmt = $pdo->prepare(
    'UPDATE registrations SET
        last_lat = ?,
        last_lng = ?,
        last_location_at = NOW(),
        device_model = COALESCE(?, device_model),
        android_version = COALESCE(?, android_version),
        app_version = COALESCE(?, app_version),
        network_type = COALESCE(?, network_type),
        network_name = COALESCE(?, network_name)
     WHERE device_id = ?'
);
$stmt->execute([
    $lat, $lng,
    $deviceModel, $androidVersion, $appVersion, $networkType, $networkName,
    $deviceId,
]);

if ($stmt->rowCount() === 0) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'device_not_registered']);
    exit;
}

echo json_encode(['status' => 'ok']);
