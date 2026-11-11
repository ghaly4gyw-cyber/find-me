<?php
// Template only — same pattern as db-config.template.php. Rendered into
// admin-config.php at deploy time from the ADMIN_PASSWORD repo secret, never
// committed with a real value.
return [
    'password' => '__ADMIN_PASSWORD__',
];
