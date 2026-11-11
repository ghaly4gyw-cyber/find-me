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
    'UPDATE registrations SET last_lat = ?, last_lng = ?, last_location_at = NOW() WHERE device_id = ?'
);
$stmt->execute([$lat, $lng, $deviceId]);

if ($stmt->rowCount() === 0) {
    http_response_code(404);
    echo json_encode(['status' => 'error', 'reason' => 'device_not_registered']);
    exit;
}

echo json_encode(['status' => 'ok']);
