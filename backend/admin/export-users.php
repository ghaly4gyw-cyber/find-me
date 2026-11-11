<?php
/**
 * CSV export of every registered user — "every single data you can bring
 * it". Either admin can download this; it's read-only and holds nothing
 * more sensitive than what's already on screen in the dashboard.
 */

require __DIR__ . '/auth.php';
requireAdminLogin();
require __DIR__ . '/../schema.php';

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

$users = $pdo->query(
    "SELECT name, phone, device_id, activated, blocked, created_at, last_location_at,
            device_model, android_version, app_version, network_type, network_name
     FROM registrations ORDER BY created_at DESC"
)->fetchAll(PDO::FETCH_ASSOC);

header('Content-Type: text/csv; charset=utf-8');
header('Content-Disposition: attachment; filename="find-me-users.csv"');

$out = fopen('php://output', 'w');
fputcsv($out, [
    'Name', 'Phone', 'Device ID', 'Activated', 'Blocked', 'Registered At',
    'Last Seen', 'Device Model', 'Android Version', 'App Version', 'Network Type', 'Network Name',
]);
foreach ($users as $u) {
    fputcsv($out, [
        $u['name'],
        $u['phone'],
        $u['device_id'],
        $u['activated'] ? 'yes' : 'no',
        $u['blocked'] ? 'yes' : 'no',
        $u['created_at'],
        $u['last_location_at'],
        $u['device_model'],
        $u['android_version'],
        $u['app_version'],
        $u['network_type'],
        $u['network_name'],
    ]);
}
fclose($out);
