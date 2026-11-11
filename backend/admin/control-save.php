<?php
/**
 * Saves the "App control" form from the dashboard (one-time message +
 * forced-update settings) into app_control — read live by control.php, and
 * from there by every installed copy of the app (see RemoteControl.kt).
 * This is the whole point of moving control off a file in the GitHub repo:
 * Ghaly sets this from the dashboard, no git/GitHub involved.
 */

require __DIR__ . '/auth.php';
requireAdminLogin();
require __DIR__ . '/../schema.php';

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    header('Location: index.php');
    exit;
}

$configPath = __DIR__ . '/../db-config.php';
if (!file_exists($configPath)) {
    http_response_code(500);
    die('Server not configured — db-config.php is missing.');
}
$config = require $configPath;

try {
    $pdo = connectDb($config);
} catch (PDOException $e) {
    http_response_code(500);
    die('Could not reach the database.');
}

ensureSchema($pdo);

$messageTitle = trim((string) ($_POST['message_title'] ?? ''));
$messageBody = trim((string) ($_POST['message_body'] ?? ''));
$minSupportedVersion = (int) ($_POST['min_supported_version'] ?? 0);
$minSupportedMessage = trim((string) ($_POST['min_supported_message'] ?? ''));

if ($minSupportedVersion < 0) $minSupportedVersion = 0;
$messageTitle = mb_substr($messageTitle, 0, 200);
$messageBody = mb_substr($messageBody, 0, 1000);
$minSupportedMessage = mb_substr($minSupportedMessage, 0, 500);

// A message only goes out (and only shows again on devices that already
// saw an earlier one) when its text actually changed from what's already
// saved — saving the form without touching the message fields should
// never needlessly re-show something people already dismissed.
$current = $pdo->query('SELECT message_title, message_body FROM app_control WHERE id = 1')->fetch(PDO::FETCH_ASSOC);
$textChanged = !$current
    || (string) $current['message_title'] !== $messageTitle
    || (string) $current['message_body'] !== $messageBody;

$hasMessage = $messageTitle !== '' || $messageBody !== '';
$messageId = $hasMessage
    ? ($textChanged ? bin2hex(random_bytes(8)) : null) // null below means "keep existing id"
    : null;

if ($hasMessage && $textChanged) {
    $stmt = $pdo->prepare(
        'UPDATE app_control SET message_id = ?, message_title = ?, message_body = ?,
            min_supported_version = ?, min_supported_message = ? WHERE id = 1'
    );
    $stmt->execute([$messageId, $messageTitle, $messageBody, $minSupportedVersion, $minSupportedMessage ?: null]);
} elseif ($hasMessage) {
    // Text unchanged — keep the existing message_id so it doesn't re-show.
    $stmt = $pdo->prepare(
        'UPDATE app_control SET message_title = ?, message_body = ?,
            min_supported_version = ?, min_supported_message = ? WHERE id = 1'
    );
    $stmt->execute([$messageTitle, $messageBody, $minSupportedVersion, $minSupportedMessage ?: null]);
} else {
    // Message cleared entirely.
    $stmt = $pdo->prepare(
        'UPDATE app_control SET message_id = NULL, message_title = NULL, message_body = NULL,
            min_supported_version = ?, min_supported_message = ? WHERE id = 1'
    );
    $stmt->execute([$minSupportedVersion, $minSupportedMessage ?: null]);
}

header('Location: index.php?control_saved=1');
