<?php
session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'secure' => true,
    'httponly' => true,
    'samesite' => 'Strict',
]);
session_start();

function requireAdminLogin(): void
{
    if (empty($_SESSION['admin_logged_in'])) {
        header('Location: login.php');
        exit;
    }
}

function adminConfig(): array
{
    $path = __DIR__ . '/../admin-config.php';
    if (!file_exists($path)) {
        http_response_code(500);
        die('Admin not configured — admin-config.php is missing.');
    }
    return require $path;
}
