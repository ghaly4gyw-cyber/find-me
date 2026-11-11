<?php
// Template only — committed to the public repo with placeholders, never real
// values. The deploy workflow (.github/workflows/build.yml) renders this into
// db-config.php using GitHub Actions secrets, uploads ONLY the rendered file
// to the server via FTP, and never commits the rendered version anywhere.
return [
    'host' => '__DB_HOST__',
    'name' => '__DB_NAME__',
    'user' => '__DB_USER__',
    'password' => '__DB_PASSWORD__',
];
