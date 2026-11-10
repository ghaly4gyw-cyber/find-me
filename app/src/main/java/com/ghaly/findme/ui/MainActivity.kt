package com.ghaly.findme.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ghaly.findme.R
import com.ghaly.findme.mesh.ActivityWatcher
import com.ghaly.findme.mesh.DeviceIdentity
import com.ghaly.findme.mesh.MeshForegroundService
import com.ghaly.findme.mesh.MeshManager
import com.ghaly.findme.mesh.Permissions
import com.ghaly.findme.mesh.ProfileStore
import com.ghaly.findme.update.UpdateChecker
import com.ghaly.findme.update.UpdateInstaller
import com.ghaly.findme.util.AnrWatchdog
import com.ghaly.findme.util.CrashLog
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    var mesh: MeshManager? = null
        private set

    /** Set only while waiting on the "allow installs from this app" settings
     *  screen the person was just sent to; picked back up in onStart(). */
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null

    private var bound = false
    // Guards against calling bindService() more than once per onCreate..onStop
    // window. onCreate's refreshPermissionState() and onStart's own calls can
    // both reach startMesh() back-to-back on the main thread before the async
    // onServiceConnected callback ever fires (so `bound` alone can't catch
    // this) — without the guard, each extra bindService() call needs its own
    // matching unbindService(), but onStop() only ever issues one, leaking a
    // bind reference on every app foreground/background cycle.
    private var meshBindRequested = false
    private lateinit var meshStatus: TextView
    private lateinit var daylightHeaderPill: TextView
    private lateinit var permissionBar: android.view.View
    private lateinit var batteryBar: android.view.View
    private lateinit var locationServicesBar: android.view.View
    // The permission/battery/location banners are important but not urgent —
    // on a shorter screen they can eat enough fixed vertical space that a
    // fragment's own input row (the chat box, say) gets squeezed down to
    // nothing or pushed off the bottom of the window once the keyboard is
    // also open. Collapsing them for as long as the keyboard is up gives that
    // input row back the room it needs; refreshPermissionState() and friends
    // restore them to their real state the moment the keyboard closes.
    private var bannersHiddenForKeyboard = false
    private lateinit var tabButtons: List<Button>
    private var selectedTabIndex = 0

    /** Holds the chosen photo (base64) while the edit-profile dialog is open. */
    private var pendingPhotoBase64: String? = null
    private var pendingPhotoView: ImageView? = null

    // The event log line below ("Searching…", "Connected! Exchanging…", etc.)
    // is transient — it gets overwritten by the next discovery/advertising
    // event within seconds, so a real connection can silently scroll off the
    // header and make the app look dead even while two phones are actually
    // linked. This keeps a live, always-current "N connected" count visible
    // alongside it, refreshed both on every event and on a steady timer, so
    // "why won't my voice message send" has a visible answer right there:
    // if it says 0 connected, nothing CAN send until a direct link exists.
    private var lastMeshEvent: String = ""

    private fun refreshMeshStatusLine() {
        val count = mesh?.directPeerCount() ?: 0
        val countText = if (count > 0) {
            getString(R.string.mesh_connected_count_fmt, count)
        } else {
            getString(R.string.mesh_connected_none)
        }
        meshStatus.text = if (lastMeshEvent.isEmpty()) countText else "$countText · $lastMeshEvent"
    }

    /** Lives in the always-visible header, not floating over the map, so
     *  "how much daylight is left" stays in view no matter which tab is
     *  open — the whole point of a desert-safety readout is that it's
     *  useless if you only see it while you happen to be looking at the
     *  radar. Color escalates the closer sunset gets, same as the map's
     *  own daylight pill used to. */
    private fun refreshDaylightHeaderPill() {
        val mine = mesh?.myLocation()
        if (mine == null) {
            daylightHeaderPill.visibility = android.view.View.GONE
            return
        }
        val state = com.ghaly.findme.util.SunCalculator.compute(mine.first, mine.second, java.time.ZonedDateTime.now())
        when (state) {
            is com.ghaly.findme.util.SunCalculator.DaylightState.Daylight -> {
                val minutes = state.remaining.toMinutes()
                daylightHeaderPill.text = getString(R.string.daylight_header_fmt, com.ghaly.findme.util.SunCalculator.format(state.remaining))
                daylightHeaderPill.setTextColor(
                    resources.getColor(
                        when {
                            minutes <= 30 -> R.color.sos_red
                            minutes <= 90 -> R.color.amber_500
                            else -> R.color.sand_900
                        },
                        null
                    )
                )
                daylightHeaderPill.visibility = android.view.View.VISIBLE
            }
            is com.ghaly.findme.util.SunCalculator.DaylightState.Night -> {
                daylightHeaderPill.text = getString(R.string.night_header_fmt, com.ghaly.findme.util.SunCalculator.format(state.remaining))
                daylightHeaderPill.setTextColor(resources.getColor(R.color.sos_red, null))
                daylightHeaderPill.visibility = android.view.View.VISIBLE
            }
            is com.ghaly.findme.util.SunCalculator.DaylightState.Unknown -> {
                daylightHeaderPill.visibility = android.view.View.GONE
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MeshForegroundService.LocalBinder
            mesh = binder.getMesh()
            bound = true
            refreshMeshStatusLine()
            // Surface every advertising/discovery/connection step (and any failure)
            // right in the header — this is what actually told us what's wrong
            // the first time two real phones couldn't find each other, instead of
            // that only existing in Logcat where nobody could see it.
            mesh?.onStatusChanged = { text ->
                lastMeshEvent = text
                runOnUiThread { refreshMeshStatusLine() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mesh = null
            bound = false
            lastMeshEvent = ""
            meshStatus.text = getString(R.string.mesh_status_off)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            refreshPermissionState()
        }

    private val pickPhotoLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) handlePickedPhoto(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // If the app died last time, this is the one place that can show the
        // real error instead of another guess at it — read once, right after
        // the restart it caused.
        val crashText = CrashLog.consumeLastCrash(this)
        val hangText = AnrWatchdog.consumeLastHang(this)
        val diagnosticText = listOfNotNull(crashText, hangText).takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n——————\n\n")
        diagnosticText?.let { showCrashDialog(it) }

        meshStatus = findViewById(R.id.meshStatus)
        daylightHeaderPill = findViewById(R.id.daylightHeaderPill)
        permissionBar = findViewById(R.id.permissionBar)
        batteryBar = findViewById(R.id.batteryBar)
        locationServicesBar = findViewById(R.id.locationServicesBar)
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            permissionLauncher.launch(Permissions.required())
        }
        findViewById<Button>(R.id.allowBatteryButton).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        findViewById<Button>(R.id.enableLocationButton).setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                // some OEM skins don't have this exact settings screen
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootLayout)) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            if (keyboardVisible && !bannersHiddenForKeyboard) {
                bannersHiddenForKeyboard = true
                permissionBar.visibility = android.view.View.GONE
                batteryBar.visibility = android.view.View.GONE
                locationServicesBar.visibility = android.view.View.GONE
            } else if (!keyboardVisible && bannersHiddenForKeyboard) {
                bannersHiddenForKeyboard = false
                refreshPermissionState()
                refreshBatteryState()
                refreshLocationServicesState()
            }
            insets
        }

        val nameButton = findViewById<TextView>(R.id.myNameButton)
        refreshNameButton(nameButton)
        nameButton.setOnClickListener { showEditProfileDialog(nameButton) }

        findViewById<TextView>(R.id.languageButton).setOnClickListener { showLanguageDialog() }
        findViewById<TextView>(R.id.toolsButton).setOnClickListener { showToolsDialog() }
        findViewById<TextView>(R.id.buddyCheckinButton).setOnClickListener { showBuddyCheckInDialog() }

        tabButtons = listOf(
            findViewById(R.id.tabPeers),
            findViewById(R.id.tabMap),
            findViewById(R.id.tabChat),
            findViewById(R.id.tabSos),
            findViewById(R.id.tabPosts),
            findViewById(R.id.tabGroups)
        )

        tabButtons[0].setOnClickListener { selectTab(0); showFragment(PeersFragment()) }
        tabButtons[1].setOnClickListener { selectTab(1); showFragment(RadarMapFragment()) }
        tabButtons[2].setOnClickListener { selectTab(2); showFragment(ChatFragment()) }
        tabButtons[3].setOnClickListener { selectTab(3); showFragment(SosFragment()) }
        tabButtons[4].setOnClickListener { selectTab(4); showFragment(PostsFragment()) }
        tabButtons[5].setOnClickListener { selectTab(5); showFragment(GroupsFragment()) }

        if (savedInstanceState == null) {
            selectTab(0)
            showFragment(PeersFragment())
        }

        refreshPermissionState()
        checkForUpdates(manual = false)

        lifecycleScope.launch {
            while (true) {
                try {
                    refreshMeshStatusLine()
                    refreshDaylightHeaderPill()
                } catch (e: Throwable) {
                    CrashLog.writeHandled(this@MainActivity, "periodic header refresh", e)
                }
                checkWindowHealth()
                delay(5_000L)
            }
        }
    }

    /** The reported "shrinking" happens on its own a few seconds after
     *  opening any screen at all - Peers, Map, Groups, Accounts - which
     *  points much more at this every-5-seconds loop than at any one
     *  screen's code. Nothing in the loop was ever wrapped in a try/catch
     *  before, so a failure in it could go unlogged. This also checks the
     *  ACTIVITY'S OWN WINDOW, not just a view inside it, since what the
     *  video actually shows - everything relocating to a small band with
     *  black on both sides - looks like the window itself shrinking, not
     *  any one screen's layout. A toast is used instead of the usual inline
     *  banner because a toast draws through the window manager directly,
     *  so it still shows up even if the activity's own content is what's
     *  broken. */
    private fun checkWindowHealth() {
        val decorView = window?.decorView ?: return
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val decorWidth = decorView.width
        val decorHeight = decorView.height
        val shrunkHeight = decorHeight in 1 until (displayHeight / 2)
        val shrunkWidth = decorWidth in 1 until (displayWidth / 2)
        if (shrunkHeight || shrunkWidth) {
            val details = buildString {
                append("Window shrink detected\n")
                append("window: ${decorWidth}x${decorHeight}px\n")
                append("display: ${displayWidth}x${displayHeight}px\n")
                append("multi-window mode: $isInMultiWindowMode\n")
                append("in picture-in-picture: ${isInPictureInPictureMode}")
            }
            CrashLog.writeHandled(this, "window-health", RuntimeException(details))
            android.widget.Toast.makeText(this, details, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onStart() {
        super.onStart()
        refreshPermissionState()
        refreshBatteryState()
        refreshLocationServicesState()
        // refreshPermissionState() above already calls startMesh() when granted.

        // Coming back from the "allow installs from this app" settings
        // screen lands here — pick the update flow back up instead of
        // leaving the person to find "Check for updates" again themselves.
        pendingUpdate?.let { update ->
            if (!UpdateInstaller.needsInstallPermission(this)) {
                pendingUpdate = null
                startUpdateDownload(update)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        meshBindRequested = false
    }

    private fun refreshPermissionState() {
        val granted = Permissions.allGranted(this)
        permissionBar.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        if (granted) startMesh()
    }

    /** Android aggressively kills background work unless the app is exempted from
     *  battery optimization — without this the mesh/GPS can silently stop while the
     *  phone is in someone's pocket, which defeats the whole point of the app. */
    private fun refreshBatteryState() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        batteryBar.visibility = if (ignoring) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** Android's system Location toggle is a DIFFERENT thing from the
     *  ACCESS_FINE_LOCATION permission above — on most phones, Nearby
     *  Connections' Bluetooth/Wi-Fi scanning silently finds nothing at all if
     *  this system toggle is off, even with the permission fully granted. This
     *  is exactly the kind of failure that's invisible unless called out
     *  directly, so it gets its own banner like the other two checks. */
    private fun refreshLocationServicesState() {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val enabled = locationManager?.let {
            androidx.core.location.LocationManagerCompat.isLocationEnabled(it)
        } ?: true
        locationServicesBar.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            // some OEM skins block this intent outright — nothing more we can do here
        }
    }

    private fun startMesh() {
        if (meshBindRequested) return
        meshBindRequested = true
        MeshForegroundService.start(this)
        val intent = Intent(this, MeshForegroundService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    /** Shows exactly what crashed last time, as plain selectable text with a
     *  one-tap "Copy" — so whoever hit the crash can paste the real error
     *  straight into a message instead of describing a black screen and
     *  someone else having to guess at the cause from that description alone. */
    private fun showCrashDialog(crashText: String) {
        val scroll = android.widget.ScrollView(this)
        val textView = TextView(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(padding, padding, padding, padding)
        textView.text = crashText
        textView.textSize = 12f
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.setTextIsSelectable(true)
        scroll.addView(textView)

        AlertDialog.Builder(this)
            .setTitle(R.string.crash_dialog_title)
            .setView(scroll)
            .setPositiveButton(R.string.crash_dialog_copy_button) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Find Me crash", crashText))
                Toast.makeText(this, R.string.crash_copied_toast, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    /** Explicit in-app switcher — doesn't rely on the phone's system language,
     *  since many of these phones are secondhand and set to whatever the
     *  previous owner used. Default on first launch is Arabic (see
     *  FindMeApplication); this just lets anyone change it afterwards. */
    private fun showLanguageDialog() {
        val tags = arrayOf("ar", "en", "fr")
        val names = resources.getStringArray(R.array.language_names)
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val checkedIndex = tags.indexOfFirst { current.startsWith(it) }.let { if (it < 0) 0 else it }

        AlertDialog.Builder(this)
            .setTitle(R.string.choose_language)
            .setSingleChoiceItems(names, checkedIndex) { dialog, index ->
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[index]))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Offline, personal utilities — nothing here touches the mesh or anyone
     *  else's phone. One entry point that opens into its own full screen
     *  (so the list stays short no matter how many little tools get added
     *  here later), rather than a button per tool crowding the top bar. */
    private fun showToolsDialog() {
        val options = arrayOf(
            getString(R.string.tools_notes),
            getString(R.string.tools_calculator),
            getString(R.string.tools_accounts),
            getString(R.string.tools_check_for_updates)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_dialog_title)
            .setItems(options) { _, which ->
                if (which == 3) {
                    checkForUpdates(manual = true)
                    return@setItems
                }
                val fragment = when (which) {
                    0 -> NotesFragment()
                    1 -> CalculatorFragment()
                    else -> AccountsFragment()
                }
                replaceContentSafely(fragment, addToBackStack = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** True only when the phone currently has some live network path at all
     *  (Wi-Fi, mobile data, anything) — lets a startup check skip straight
     *  past a doomed connection attempt instead of waiting out a timeout,
     *  which is the common case for this app out in the desert. */
    private fun hasActiveNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Looks for a newer build on the project's GitHub release and offers to
     *  install it in place, so getting an update no longer means leaving the
     *  app to download an APK from a browser. [manual] is true only when the
     *  person tapped "Check for updates" themselves — it's the only case
     *  that talks back with a "you're up to date" / "no internet" message;
     *  the quiet startup check stays silent unless it actually finds
     *  something. */
    private fun checkForUpdates(manual: Boolean) {
        if (!hasActiveNetwork()) {
            if (manual) Toast.makeText(this, R.string.update_no_internet, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val update = withContext(Dispatchers.IO) { UpdateChecker.check(this@MainActivity) }
            if (update == null) {
                if (manual) Toast.makeText(this@MainActivity, R.string.update_up_to_date, Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_available_title)
                .setMessage(getString(R.string.update_available_message, update.versionName))
                .setPositiveButton(R.string.update_install_button) { _, _ -> startUpdateDownload(update) }
                .setNegativeButton(R.string.update_later_button, null)
                .show()
        }
    }

    private fun startUpdateDownload(update: UpdateChecker.UpdateInfo) {
        if (UpdateInstaller.needsInstallPermission(this)) {
            pendingUpdate = update
            Toast.makeText(this, R.string.update_allow_install_toast, Toast.LENGTH_LONG).show()
            try {
                startActivity(UpdateInstaller.requestInstallPermissionIntent(this))
            } catch (e: Exception) {
                // some OEM skins don't have this exact settings screen
            }
            return
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading_title)
            .setMessage(R.string.update_downloading_message)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            try {
                val apkFile = withContext(Dispatchers.IO) {
                    UpdateChecker.downloadApk(this@MainActivity, update.apkUrl)
                }
                progressDialog.dismiss()
                UpdateInstaller.install(this@MainActivity, apkFile)
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** "Off is just the first option" — no separate toggle needed. Needs
     *  POST_NOTIFICATIONS to actually show the "You OK?" prompt
     *  (requested elsewhere at startup, same as every other alert in this
     *  app), but turning the feature on never depends on that having been
     *  granted — if it's missing, the grace-window alert still fires on
     *  schedule, the person just never saw the prompt that could have headed
     *  it off. */
    private fun showBuddyCheckInDialog() {
        val options = resources.getStringArray(R.array.buddy_checkin_interval_options)
        val minutesForIndex = intArrayOf(0, 60, 90, 120, 180)
        val currentMinutes = if (com.ghaly.findme.mesh.BuddyCheckIn.enabled(this)) {
            com.ghaly.findme.mesh.BuddyCheckIn.intervalMinutes(this)
        } else {
            0
        }
        val checkedIndex = minutesForIndex.indexOf(currentMinutes).let { if (it < 0) 0 else it }

        AlertDialog.Builder(this)
            .setTitle(R.string.buddy_checkin_dialog_title)
            .setSingleChoiceItems(options, checkedIndex) { dialog, index ->
                val minutes = minutesForIndex[index]
                if (minutes == 0) {
                    com.ghaly.findme.mesh.BuddyCheckIn.setEnabled(this, false)
                } else {
                    com.ghaly.findme.mesh.BuddyCheckIn.setIntervalMinutes(this, minutes)
                    com.ghaly.findme.mesh.BuddyCheckIn.setEnabled(this, true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshNameButton(button: TextView) {
        button.text = "${DeviceIdentity.name(this)} ✎"
    }

    /** Opens the full "edit my profile" dialog: name, phone, description, photo. */
    private fun showEditProfileDialog(nameButton: TextView) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val photoView = view.findViewById<ImageView>(R.id.profilePhoto)
        val nameInput = view.findViewById<EditText>(R.id.profileNameInput)
        val phoneInput = view.findViewById<EditText>(R.id.profilePhoneInput)
        val descInput = view.findViewById<EditText>(R.id.profileDescriptionInput)

        val current = ProfileStore.get(this)
        nameInput.setText(current.name)
        phoneInput.setText(current.phone)
        descInput.setText(current.description)
        pendingPhotoBase64 = current.photoBase64
        pendingPhotoView = photoView
        applyPhotoPreview(photoView, current.photoBase64)

        val photoClick = View.OnClickListener { pickPhotoLauncher.launch("image/*") }
        photoView.setOnClickListener(photoClick)
        view.findViewById<TextView>(R.id.choosePhotoText).setOnClickListener(photoClick)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_profile)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = nameInput.text?.toString()?.trim().orEmpty()
                if (newName.isNotEmpty()) {
                    DeviceIdentity.setName(this, newName)
                }
                ProfileStore.save(
                    this,
                    phone = phoneInput.text?.toString()?.trim(),
                    description = descInput.text?.toString()?.trim(),
                    photoBase64 = pendingPhotoBase64
                )
                refreshNameButton(nameButton)
                mesh?.sendProfile()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handlePickedPhoto(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            val resized = Bitmap.createScaledBitmap(original, 128, 128, true)
            val out = ByteArrayOutputStream()
            resized.compress(Bitmap.CompressFormat.JPEG, 70, out)
            val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            pendingPhotoBase64 = base64
            pendingPhotoView?.let { applyPhotoPreview(it, base64) }
        } catch (e: Exception) {
            Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPhotoPreview(view: ImageView, base64: String?) {
        if (base64 == null) {
            view.setImageDrawable(null)
            return
        }
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            view.setImageBitmap(bitmap)
        } catch (e: Exception) {
            view.setImageDrawable(null)
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        ActivityWatcher.poke()
    }

    /** Switches to one of the 6 bottom-tab screens. Also clears any back-stack
     *  entries left over from drilling into a group chat or a post's comments —
     *  without this, switching tabs while inside one of those left it on the
     *  stack, so pressing system Back after hopping tabs could pop you into a
     *  stale screen that no longer matched whichever tab was highlighted. Each
     *  tab switch should start clean. */
    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        replaceContentSafely(fragment, addToBackStack = false)
    }

    /** Every screen reached from a tab or from Tools goes through here. A
     *  screen repeatedly described as "breaking" or "shrinking" the moment
     *  it's opened, with no crash popup and no detected freeze, was the one
     *  combination nothing in this app could explain from a description
     *  alone — so instead of guessing again, this makes the opening itself
     *  impossible to fail silently: executePendingTransactions() forces the
     *  new fragment's view to actually build right here, on this call stack,
     *  instead of some time later — so if building it throws anything at
     *  all, it throws HERE, where it can be caught and put directly on
     *  screen as readable text immediately, with no relaunch, no waiting,
     *  and nothing for whoever's using the app to interpret or describe. */
    private fun replaceContentSafely(fragment: androidx.fragment.app.Fragment, addToBackStack: Boolean) {
        try {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            val transaction = supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_right, R.anim.slide_out_left,
                    R.anim.slide_in_right, R.anim.slide_out_left
                )
                .replace(R.id.contentContainer, fragment)
            if (addToBackStack) transaction.addToBackStack(null)
            transaction.commit()
            supportFragmentManager.executePendingTransactions()
            checkForLayoutShrink(fragment)
        } catch (e: Throwable) {
            showInlineContentError(fragment::class.simpleName ?: "screen", e)
        }
    }

    /** Three different checks (a crash log, a freeze watchdog, and now
     *  catching exceptions the instant a screen opens) have all come back
     *  clean while the reported "shrinking"/black-screen symptom kept
     *  happening — which rules out a thrown exception and a frozen main
     *  thread, and points at the screen's own dimensions instead. Rather
     *  than guess a fourth time, this measures the new screen a moment
     *  after it should have filled the space below the header, and if it
     *  clearly hasn't, writes down the real numbers and puts them directly
     *  on screen — so whatever comes back next is evidence, not another
     *  description to interpret. */
    private fun checkForLayoutShrink(fragment: androidx.fragment.app.Fragment) {
        val container = findViewById<android.widget.FrameLayout>(R.id.contentContainer)
        container.post {
            val fragmentView = fragment.view ?: return@post
            val containerHeight = container.height
            val containerWidth = container.width
            val viewHeight = fragmentView.height
            val viewWidth = fragmentView.width
            val heightShrunk = containerHeight > 0 && viewHeight in 1 until (containerHeight / 4)
            val widthShrunk = containerWidth > 0 && viewWidth in 1 until (containerWidth / 4)
            if (heightShrunk || widthShrunk) {
                val label = fragment::class.simpleName ?: "screen"
                val rootHeight = findViewById<android.view.View>(R.id.rootLayout).height
                val details = buildString {
                    append("Layout shrink detected on $label\n")
                    append("contentContainer: ${containerWidth}x${containerHeight}px\n")
                    append("fragment view: ${viewWidth}x${viewHeight}px\n")
                    append("rootLayout height: ${rootHeight}px\n")
                    append("screen: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}px, density ${resources.displayMetrics.density}")
                }
                val syntheticError = RuntimeException(details)
                CrashLog.writeHandled(this, label, syntheticError)
                showInlineContentError(label, syntheticError)
            }
        }
    }

    /** Puts the exact error directly where the broken screen would have
     *  been — selectable, visible immediately, no dialog or relaunch needed.
     *  Deliberately doesn't go through any Fragment or dialog machinery
     *  itself, since whatever just failed might be something both of those
     *  share. */
    private fun showInlineContentError(label: String, throwable: Throwable) {
        CrashLog.writeHandled(this, label, throwable)
        val container = findViewById<android.widget.FrameLayout>(R.id.contentContainer)
        val scroll = android.widget.ScrollView(this)
        val textView = TextView(this)
        val padding = (16 * resources.displayMetrics.density).toInt()
        textView.setPadding(padding, padding, padding, padding)
        textView.setTextIsSelectable(true)
        textView.textSize = 12f
        textView.typeface = android.graphics.Typeface.MONOSPACE
        textView.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.sos_red))
        textView.text = "$label failed to open:\n\n${android.util.Log.getStackTraceString(throwable)}"
        scroll.addView(textView)
        container.removeAllViews()
        container.addView(scroll)
    }

    /** Highlights the active bottom tab (pill background + brighter text) and dims the rest. */
    private fun selectTab(index: Int) {
        selectedTabIndex = index
        val sosIndex = 3
        tabButtons.forEachIndexed { i, button ->
            val selected = i == index
            button.background = androidx.core.content.ContextCompat.getDrawable(
                this,
                if (selected) R.drawable.bg_tab_item_selected else R.drawable.bg_tab_item
            )
            button.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    when {
                        i == sosIndex -> R.color.sos_red
                        selected -> R.color.teal_400
                        else -> R.color.sand_300
                    }
                )
            )
        }
    }

    /** Jumps straight to the map, already pointed at [deviceId] — the same
     *  "direction line + bearing/distance" state tapping their dot and
     *  choosing Follow would leave the map in, just reached in one tap from
     *  an SOS or inactivity card instead of having to switch tabs and find
     *  them by hand. Map tab index is 1, same as the click listener below. */
    fun followOnMap(deviceId: String) {
        selectTab(1)
        showFragment(RadarMapFragment.newInstance(deviceId))
    }
}
