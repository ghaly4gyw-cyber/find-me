<?php
/**
 * Find Me — usage analytics sync endpoint.
 *
 * Receives {device_id, app_opens, total_ms, features} from the app whenever
 * it's online — a cumulative, device-local snapshot (see UsageTracker in the
 * app), never a log of individual actions. "features" is a small JSON
 * object keyed by screen name, e.g. {"NotesFragment": {"opens": 5, "ms":
 * 120000}, ...}, so the admin dashboard can see which parts of the app
 * actually get used and for how long.
 *
 * Upserted by device_id, same pattern as register.php and
 * location-update.php: the latest snapshot always replaces the stored one,
 * since the app itself already holds the running totals.
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
$appOpens = isset($body['app_opens']) && is_numeric($body['app_opens']) ? (int) $body['app_opens'] : null;
$totalMs = isset($body['total_ms']) && is_numeric($body['total_ms']) ? (int) $body['total_ms'] : null;
$features = isset($body['features']) && is_array($body['features']) ? $body['features'] : null;

$featureJson = $features !== null ? json_encode($features) : null;

// Bounded generously — this is a small counts object (a dozen or so screen
// names, each with two integers), never free text or content from the app.
if ($deviceId === '' || mb_strlen($deviceId) > 64
    || $appOpens === null || $appOpens < 0
    || $totalMs === null || $totalMs < 0
    || ($featureJson !== null && strlen($featureJson) > 20_000)) {
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

// This endpoint doesn't require the device to already be registered — a
// usage snapshot could in principle arrive first (e.g. a dropped
// connection reordering requests) and should still be recorded rather than
// silently dropped.
$stmt = $pdo->prepare(
    'INSERT INTO usage_stats (device_id, app_opens, total_ms, feature_usage)
     VALUES (?, ?, ?, ?)
     ON DUPLICATE KEY UPDATE
        app_opens = VALUES(app_opens),
        total_ms = VALUES(total_ms),
        feature_usage = VALUES(feature_usage)'
);
$stmt->execute([$deviceId, $appOpens, $totalMs, $featureJson]);

echo json_encode(['status' => 'ok']);
