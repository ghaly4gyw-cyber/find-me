<?php
/**
 * Find Me — remote control endpoint.
 *
 * Replaces the old "edit control.json, commit, push to GitHub" workflow:
 * the app now reads this instead of a GitHub release asset, and the admin
 * dashboard edits the same row this reads from (see admin/control-save.php).
 * Public, read-only, no auth — every install that happens to have internet
 * reads the same thing, exactly like the old file did. Started out limited
 * to a one-time message and flagging that a version is too old; kill_switch
 * was added later, at Ghaly's explicit, repeated request, as a global
 * version of the same kind of gate — see RemoteControl.kt for exactly what
 * each of these does and does not touch.
 */

header('Content-Type: application/json');
require __DIR__ . '/schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'reason' => 'method_not_allowed']);
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

$row = $pdo->query('SELECT message_id, message_title, message_body, min_supported_version, min_supported_message, kill_switch, kill_switch_message FROM app_control WHERE id = 1')
    ->fetch(PDO::FETCH_ASSOC);

$message = null;
if ($row && !empty($row['message_id']) && (!empty($row['message_title']) || !empty($row['message_body']))) {
    $message = [
        'id' => $row['message_id'],
        'title' => $row['message_title'] ?? '',
        'body' => $row['message_body'] ?? '',
    ];
}

echo json_encode([
    'message' => $message,
    'min_supported_version' => $row ? (int) $row['min_supported_version'] : 0,
    'min_supported_message' => $row['min_supported_message'] ?? null,
    'kill_switch' => $row ? (bool) $row['kill_switch'] : false,
    'kill_switch_message' => $row['kill_switch_message'] ?? null,
]);
