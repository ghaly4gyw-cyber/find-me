package com.ghaly.findme.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
import com.ghaly.findme.export.LedgerExport
import com.ghaly.findme.update.AccountReclaimer
import com.ghaly.findme.update.BackupUploader
import com.ghaly.findme.update.BlockChecker
import com.ghaly.findme.update.FeedbackUploader
import com.ghaly.findme.update.ActivationMessenger
import com.ghaly.findme.update.ActivationUploader
import com.ghaly.findme.update.HeartbeatUploader
import com.ghaly.findme.update.PinUploader
import com.ghaly.findme.update.LocationUploader
import com.ghaly.findme.update.PublicPlaceUploader
import com.ghaly.findme.update.RegistrationUploader
import com.ghaly.findme.update.RemoteControl
import com.ghaly.findme.update.UpdateChecker
import com.ghaly.findme.update.UpdateInstaller
import com.ghaly.findme.update.UsageTracker
import com.ghaly.findme.update.UsageUploader
import com.ghaly.findme.util.AnrWatchdog
import com.ghaly.findme.util.AppLog
import com.ghaly.findme.util.CrashLog
import com.ghaly.findme.util.PhoneValidator
import com.ghaly.findme.util.ActivationCodes
import com.ghaly.findme.util.ThemePrefs
import com.ghaly.findme.util.withTapBounce
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    /** Public, no-login fallback download page for the forced-update gate's
     *  neutral button — same APK the in-app installer fetches, just reachable
     *  from any browser. See download/index.php. */
    private val DOWNLOAD_PAGE_URL = "https://patrons.digital/findme-api/download/"

    var mesh: MeshManager? = null
        private set

    /** Set only while waiting on the "allow installs from this app" settings
     *  screen the person was just sent to; picked back up in onStart(). */
    private var pendingUpdate: UpdateChecker.UpdateInfo? = null

    /** The non-dismissible "this device has been blocked" gate — tracked so
     *  it can be dismissed again automatically if a later check confirms
     *  the device was unblocked (see enforceBlockState). */
    private var blockedGateDialog: AlertDialog? = null

    /** The non-dismissible "not activated yet" gate (see
     *  enforceActivationGate) — tracked the same way as blockedGateDialog,
     *  so it clears itself the moment an admin approves the request or a
     *  valid code gets entered, with no need to restart the app. */
    private var activationGateDialog: AlertDialog? = null

    /** The non-dismissible global "turned off" gate (see RemoteControl's
     *  killSwitch and showKillSwitchGate) — tracked the same way as
     *  blockedGateDialog, so it clears itself the moment a super admin
     *  flips the switch back off, next time this device checks in. */
    private var killSwitchGateDialog: AlertDialog? = null

    /** Set once in onCreate — needed outside it by signOutDueToAccountMoved,
     *  which can fire from the periodic background tick, not just from a
     *  tap on the button itself. */
    private var nameButtonView: TextView? = null

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
    private var lastLoggedChildHeights: List<Int>? = null
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

    /** TakePicturePreview hands back a small in-memory thumbnail Bitmap
     *  directly — no FileProvider/temp-file plumbing needed — which is all
     *  a 128x128 profile photo needs anyway (see applyPickedBitmap). */
    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap: Bitmap? ->
            if (bitmap != null) {
                try {
                    applyPickedBitmap(bitmap)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                takePhotoLauncher.launch(null)
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.log("MainActivity", "onCreate (savedInstanceState=${savedInstanceState != null})")
        setContentView(R.layout.activity_main)

        // Counts a fresh process start as one "app open" for the usage
        // dashboard — guarded so an Activity recreation (rotation, etc.)
        // within the same process never inflates it.
        if (savedInstanceState == null) {
            UsageTracker.recordAppOpen(this)
        }

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

        val rootLayoutView = findViewById<android.view.ViewGroup>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayoutView) { _, insets ->
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            AppLog.log("Insets", "dispatch — imeVisible=$keyboardVisible, bannersHiddenForKeyboard=$bannersHiddenForKeyboard")
            if (keyboardVisible && !bannersHiddenForKeyboard) {
                bannersHiddenForKeyboard = true
                permissionBar.visibility = android.view.View.GONE
                batteryBar.visibility = android.view.View.GONE
                locationServicesBar.visibility = android.view.View.GONE
                AppLog.log("Insets", "ime became visible — hid all 3 banners")
            } else if (!keyboardVisible && bannersHiddenForKeyboard) {
                bannersHiddenForKeyboard = false
                AppLog.log("Insets", "ime became hidden — re-checking the 3 banners")
                refreshPermissionState()
                refreshBatteryState()
                refreshLocationServicesState()
            }
            insets
        }

        // Fine-grained, event-driven companion to the every-5-seconds check
        // below — this fires on every actual layout pass, so it catches a
        // collapse that comes and goes faster than a 5-second tick could
        // ever see, and logs only when something actually changed so it
        // doesn't flood the file.
        rootLayoutView.viewTreeObserver.addOnGlobalLayoutListener {
            val heights = (0 until rootLayoutView.childCount).map { rootLayoutView.getChildAt(it).height }
            if (heights != lastLoggedChildHeights) {
                lastLoggedChildHeights = heights
                logRootLayoutChildren()
            }
        }

        val nameButton = findViewById<TextView>(R.id.myNameButton)
        nameButtonView = nameButton
        refreshNameButton(nameButton)
        nameButton.setOnClickListener {
            if (ProfileStore.isRegistered(this)) {
                showProfileInfoDialog()
            } else {
                showEditProfileDialog(nameButton, isFirstRunGate = true)
            }
        }
        // Mandatory first-run registration: name + phone are required before the
        // person can use the app at all, per "everyone when he open the
        // application, ask for his name, his number". Works fully offline —
        // nothing here depends on the network. The dialog below the normal UI
        // is already inflated at this point, but setCancelable(false) means it
        // can't be dismissed or worked around until both fields are filled in.
        if (!ProfileStore.isRegistered(this)) {
            showEditProfileDialog(nameButton, isFirstRunGate = true)
        }

        findViewById<TextView>(R.id.languageButton).setOnClickListener { showLanguageDialog() }
        findViewById<TextView>(R.id.toolsButton).setOnClickListener { showToolsDialog() }
        findViewById<TextView>(R.id.buddyCheckinButton).setOnClickListener { showBuddyCheckInDialog() }

        // Promoted straight to the top bar instead of hiding behind the
        // wrench icon — "the other tools like accounts, calculator, notice,
        // all of them to be there also showing". Same destinations the old
        // Tools dialog used to send these three to.
        findViewById<TextView>(R.id.notesButton).setOnClickListener {
            replaceContentSafely(NotesFragment(), addToBackStack = true)
        }
        findViewById<TextView>(R.id.calculatorButton).setOnClickListener {
            replaceContentSafely(CalculatorFragment(), addToBackStack = true)
        }
        findViewById<TextView>(R.id.accountsButton).setOnClickListener {
            replaceContentSafely(AccountsFragment(), addToBackStack = true)
        }
        // "the application can work, if it is online... it can send message
        // for each, and also add their code" — the online contacts/messaging
        // screen, same promoted-top-bar treatment as Notes/Calculator/Accounts.
        findViewById<TextView>(R.id.onlineContactsButton).setOnClickListener {
            replaceContentSafely(OnlineContactsFragment(), addToBackStack = true)
        }
        // "He will be has a list there of the requests" — only Osama/Ghaly
        // ever see this icon at all; re-checked once more by every admin
        // endpoint itself, so this is never the only gate.
        val pendingActivationsButton = findViewById<TextView>(R.id.pendingActivationsButton)
        pendingActivationsButton.visibility = if (ProfileStore.isAdmin(this)) View.VISIBLE else View.GONE
        pendingActivationsButton.setOnClickListener {
            replaceContentSafely(PendingActivationsFragment(), addToBackStack = true)
        }
        findViewById<TextView>(R.id.feedbackButton).setOnClickListener { showFeedbackDialog() }

        // A light press animation on every quick-access icon in the top bar
        // plus the bottom tab row — the "dynamic"/"some animation" ask.
        listOf(
            R.id.notesButton, R.id.calculatorButton, R.id.accountsButton, R.id.onlineContactsButton,
            R.id.pendingActivationsButton, R.id.feedbackButton, R.id.buddyCheckinButton, R.id.languageButton,
            R.id.toolsButton, R.id.myNameButton
        ).forEach { id -> findViewById<View>(id).withTapBounce() }

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
        tabButtons.forEach { it.withTapBounce() }

        if (savedInstanceState == null) {
            selectTab(0)
            showFragment(PeersFragment())
        }

        refreshPermissionState()
        checkForUpdates(manual = false)
        checkRemoteControl()
        checkRegistrationUpload()
        // Shows the block gate immediately from the last confirmed state,
        // even before a fresh network check — a device last confirmed
        // blocked stays blocked if it's opened offline, rather than getting
        // a free pass until it happens to reconnect.
        enforceBlockState()
        // Same "show the last confirmed state immediately, even offline"
        // reasoning as enforceBlockState above — someone who closed the app
        // mid-request shouldn't get a free pass back in until a check (or a
        // code) actually confirms activation.
        enforceActivationGate()
        maybeCheckBlocked()
        if (savedInstanceState == null) {
            maybePromptForFeedback()
        }

        lifecycleScope.launch {
            while (true) {
                try {
                    refreshMeshStatusLine()
                    refreshDaylightHeaderPill()
                } catch (e: Throwable) {
                    AppLog.log("MainActivity", "periodic header refresh threw: ${e}")
                    CrashLog.writeHandled(this@MainActivity, "periodic header refresh", e)
                }
                checkWindowHealth()
                maybeUploadLocation()
                maybeUploadUsage()
                maybeUploadPublicPlaces()
                maybeCheckBlocked()
                enforceActivationGate()
                maybeSyncAccountSecurity()
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
        val contentContainer = findViewById<android.widget.FrameLayout?>(R.id.contentContainer)
        AppLog.log(
            "WindowHealth",
            "window=${decorWidth}x${decorHeight}px display=${displayWidth}x${displayHeight}px " +
                "contentContainer=${contentContainer?.width}x${contentContainer?.height}px " +
                "multiWindow=$isInMultiWindowMode pip=$isInPictureInPictureMode hasFocus=${hasWindowFocus()}"
        )
        logRootLayoutChildren()
        // The real bug, confirmed from a real log: the window itself never
        // shrinks, but contentContainer — the part the current screen
        // actually lives in — can drop to 0 while the window stays full
        // size, and it stays that way instead of correcting itself. A fresh
        // requestLayout() is a safe, side-effect-free way to ask Android to
        // re-measure everything from scratch, which is worth trying
        // automatically rather than leaving the screen stuck until a
        // restart, on top of the maxLines caps that should prevent the
        // likely cause (a header text view wrapping to dozens of lines).
        if (contentContainer != null && contentContainer.height == 0 && decorHeight > 0) {
            AppLog.log("WindowHealth", "contentContainer collapsed to 0 — requesting a fresh layout pass to self-heal")
            findViewById<android.view.View>(R.id.rootLayout).requestLayout()
        }
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
            AppLog.log("WindowHealth", "SHRINK DETECTED — $details")
            CrashLog.writeHandled(this, "window-health", RuntimeException(details))
            android.widget.Toast.makeText(this, details, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** The window itself turned out NOT to be shrinking — a real log finally
     *  showed decorView staying at a constant full size the whole time, while
     *  contentContainer (the weight=1 child that the current screen actually
     *  lives in) dropped from a normal height to exactly 0. In a vertical
     *  LinearLayout, a weighted child only loses space to another child that
     *  grew — so this lists every direct child of rootLayout with its own
     *  height, every tick, to catch which one it is in the act rather than
     *  inferring it after the fact. */
    private fun logRootLayoutChildren() {
        val root = findViewById<android.view.ViewGroup>(R.id.rootLayout)
        val sb = StringBuilder("rootLayout height=${root.height}px, ${root.childCount} children:")
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val idName = if (child.id != android.view.View.NO_ID) {
                try { resources.getResourceEntryName(child.id) } catch (e: Exception) { child.id.toString() }
            } else {
                "no-id"
            }
            val vis = when (child.visibility) {
                android.view.View.VISIBLE -> "VISIBLE"
                android.view.View.GONE -> "GONE"
                else -> "INVISIBLE"
            }
            sb.append(" [$i:${child::class.simpleName}/$idName $vis ${child.width}x${child.height}px]")
        }
        AppLog.log("RootLayout", sb.toString())
    }

    override fun onStart() {
        super.onStart()
        AppLog.log("MainActivity", "onStart")
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

        // Re-checked on every return to the app too, not just on a fresh
        // launch — otherwise someone who opened the app offline, then
        // regained signal while it stayed in the background, would only
        // see the forced-update gate the next time the process restarts.
        checkRemoteControl()
        enforceBlockState()
        maybeCheckBlocked()
    }

    override fun onStop() {
        super.onStop()
        AppLog.log("MainActivity", "onStop")
        if (bound) {
            unbindService(connection)
            bound = false
        }
        meshBindRequested = false
    }

    override fun onResume() {
        super.onResume()
        AppLog.log("MainActivity", "onResume")
        UsageTracker.onAppForegrounded(this)
    }

    override fun onPause() {
        super.onPause()
        AppLog.log("MainActivity", "onPause")
        UsageTracker.onAppBackgrounded(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLog.log("MainActivity", "onDestroy (isFinishing=$isFinishing, isChangingConfigurations=$isChangingConfigurations)")
    }

    /** Fires on things like a locale switch (the 🌐 button recreates the
     *  activity) or a rotation — exactly the kind of moment the reported
     *  break seems to cluster around ("it works right after switching the
     *  language, then breaks a second later"), so this is worth a line of
     *  its own rather than folding it into onCreate's. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLog.log("MainActivity", "onConfigurationChanged — orientation=${newConfig.orientation}, screenWidthDp=${newConfig.screenWidthDp}, screenHeightDp=${newConfig.screenHeightDp}, density=${newConfig.densityDpi}")
    }

    /** The window losing/gaining focus is exactly what a system overlay, a
     *  gesture-triggered floating/split-screen mode, or a stuck resize
     *  animation would show up as — this has never been logged before. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        val decorView = window?.decorView
        AppLog.log(
            "MainActivity",
            "onWindowFocusChanged — hasFocus=$hasFocus, decorView=${decorView?.width}x${decorView?.height}px, " +
                "display=${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels}px"
        )
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        AppLog.log("MainActivity", "onMultiWindowModeChanged — isInMultiWindowMode=$isInMultiWindowMode")
    }

    private fun refreshPermissionState() {
        val granted = Permissions.allGranted(this)
        AppLog.log("MainActivity", "refreshPermissionState — granted=$granted")
        permissionBar.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        if (granted) startMesh()
    }

    /** Android aggressively kills background work unless the app is exempted from
     *  battery optimization — without this the mesh/GPS can silently stop while the
     *  phone is in someone's pocket, which defeats the whole point of the app. */
    private fun refreshBatteryState() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) ?: true
        AppLog.log("MainActivity", "refreshBatteryState — ignoringOptimizations=$ignoring")
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
        AppLog.log("MainActivity", "refreshLocationServicesState — enabled=$enabled")
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
                AppLog.log("MainActivity", "language switched to ${tags[index]} — activity recreate expected next")
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags[index]))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Notes, Calculator, Accounts and Suggest-a-feature now have their own
     *  icons in the top bar (see activity_main.xml), so this dialog is left
     *  with only the handful of things that genuinely are occasional/admin
     *  rather than everyday field tools. */
    private fun showToolsDialog() {
        val options = arrayOf(
            getString(R.string.tools_check_for_updates),
            getString(R.string.tools_share_debug_log),
            getString(R.string.tools_about)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tools_dialog_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForUpdates(manual = true)
                    1 -> shareDebugLog()
                    2 -> showAboutDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Hands over the continuous log (see AppLog) instead of asking whoever's
     *  using the app to describe or screenshot what happened — the log
     *  already has the real sequence of events written down as it occurred. */
    private fun shareDebugLog() {
        val file = AppLog.logFile(this)
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, R.string.debug_log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        LedgerExport.shareFile(this, file, "text/plain")
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

    /** Reads control.json (see RemoteControl) whenever the phone happens to
     *  have internet. A plain message still shows once and can just be
     *  dismissed — but a version below [ControlState.minSupportedVersionCode]
     *  is now a hard gate (see [showForcedUpdateGate]), not a nudge: no
     *  "Later" button, not dismissible, shown again every time the app is
     *  opened or returned to until the update actually completes. This is a
     *  deliberate, explicit override of this file's own earlier "nothing
     *  here ever blocks" design, made at Ghaly's direct, repeated request —
     *  he was told plainly that someone who reaches just enough signal to
     *  see the gate but not enough to finish the download will have no
     *  working safety app (mesh/SOS/location, all of it) until they do, and
     *  chose full control over that risk anyway. Still true regardless:
     *  nothing here can touch the mesh, location, or any other safety
     *  feature directly — this only ever gates the UI in front of them. */
    private fun checkRemoteControl() {
        if (!hasActiveNetwork()) return
        lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) { RemoteControl.check(this@MainActivity) } ?: return@launch

            if (state.killSwitch) {
                showKillSwitchGate(state.killSwitchMessage)
                return@launch
            }
            killSwitchGateDialog?.dismiss()
            killSwitchGateDialog = null

            state.message?.let { msg ->
                if (RemoteControl.shouldShowMessage(this@MainActivity, msg)) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(msg.title.ifEmpty { getString(R.string.app_name) })
                        .setMessage(msg.body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }

            if (state.minSupportedVersionCode > 0 &&
                UpdateChecker.currentVersionCode(this@MainActivity) < state.minSupportedVersionCode
            ) {
                showForcedUpdateGate(state.minSupportedMessage)
            }
        }
    }

    /** The global kill-switch gate: not cancelable, no action to offer (not
     *  even the forced-update gate's "check now" button — there's nothing
     *  for the person themselves to do about it). Lifts only when a super
     *  admin flips the switch back off from the dashboard, picked up on
     *  this device's next successful check. */
    private fun showKillSwitchGate(message: String?) {
        if (killSwitchGateDialog?.isShowing == true) return
        killSwitchGateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.kill_switch_gate_title)
            .setMessage(message ?: getString(R.string.kill_switch_gate_default_message))
            .setCancelable(false)
            .create()
        killSwitchGateDialog?.setCanceledOnTouchOutside(false)
        killSwitchGateDialog?.show()
    }

    /** The hard update gate itself: not cancelable, no way to reach the UI
     *  behind it. The positive button fetches the real release info and goes
     *  straight into the same download/install flow as a manual update check
     *  — the gate dialog only dismisses once that's actually underway, not on
     *  tap; if the download can't start (lost signal again in the meantime)
     *  the button re-enables so they can retry rather than getting stuck on a
     *  dead button. The neutral button is the fallback Ghaly asked for: "if
     *  they block their update, provide the link to GitHub… or on the site
     *  they go and download it from there" — opens the public download page
     *  (patrons.digital/findme-api/download/, itself backed by the GitHub
     *  release) in the browser, in case the in-app installer path is ever
     *  stuck (no install-from-unknown-sources permission yet, a corrupt
     *  partial download, etc.). The message text itself is linkified too, so
     *  any link Ghaly types into the dashboard's "min_supported_message"
     *  field is directly tappable. */
    private fun showForcedUpdateGate(message: String?) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.remote_control_update_required_title)
            .setMessage(message ?: getString(R.string.remote_control_update_required_default))
            .setCancelable(false)
            .setPositiveButton(R.string.remote_control_check_now_button, null)
            .setNeutralButton(R.string.remote_control_open_download_page_button, null)
            .show()
        dialog.setCanceledOnTouchOutside(false)
        dialog.findViewById<TextView>(android.R.id.message)?.let { messageView ->
            android.text.util.Linkify.addLinks(messageView, android.text.util.Linkify.WEB_URLS)
            messageView.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            button.isEnabled = false
            lifecycleScope.launch {
                val update = withContext(Dispatchers.IO) { UpdateChecker.check(this@MainActivity) }
                if (update == null) {
                    Toast.makeText(this@MainActivity, R.string.update_no_internet, Toast.LENGTH_SHORT).show()
                    button.isEnabled = true
                    return@launch
                }
                dialog.dismiss()
                startUpdateDownload(update)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE_URL)))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.update_no_internet, Toast.LENGTH_SHORT).show()
            }
            // Deliberately does not dismiss the gate — the app is still on
            // the outdated build until the install actually completes.
        }
    }

    /** Opportunistically syncs the local registration to the backend
     *  whenever the phone happens to be online — same quiet, best-effort
     *  spirit as checkForUpdates/checkRemoteControl. Registering itself never
     *  depends on this; it only uploads what's already saved locally so the
     *  server can enforce "one phone number, one device" across installs. */
    private fun checkRegistrationUpload() {
        if (!ProfileStore.isRegistered(this)) return
        val profile = ProfileStore.get(this)
        val phone = profile.phone
        if (phone.isNullOrBlank()) return
        if (!hasActiveNetwork()) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                RegistrationUploader.uploadIfNeeded(
                    this@MainActivity,
                    profile.name,
                    phone,
                    profile.deviceId,
                    profile.description,
                    profile.photoBase64
                )
            }
            if (RegistrationUploader.consumePendingConflict(this@MainActivity)) {
                signOutDueToAccountMoved()
            }
        }
    }

    /** Fires when either signal says this phone's account now belongs to a
     *  different device — a race on first registration (see
     *  checkRegistrationUpload) or, far more commonly, a deliberate reclaim
     *  from a new phone (see enforceSessionState/AccountReclaimer) — "if he
     *  registered to a new phone, he will be auto required to be logged out
     *  from the other one". Deliberately does NOT touch the ledger/notes/
     *  saved-places repositories (see ProfileStore.clearForSignOut) — only
     *  the account binding is cleared; local data stays on this phone. Clears
     *  the registered flag first, synchronously, so no later tick in the
     *  same session can re-enter this before the gate is back up. */
    private fun signOutDueToAccountMoved() {
        if (!ProfileStore.isRegistered(this)) return // already handled
        ProfileStore.clearForSignOut(this)
        val nameButton = nameButtonView ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.signed_out_title)
            .setMessage(R.string.signed_out_body)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                refreshNameButton(nameButton)
                showEditProfileDialog(nameButton, isFirstRunGate = true)
            }
            .show()
    }

    /** Companion to enforceBlockState: signs this phone out the moment a
     *  status-check confirms its device_id no longer matches any
     *  registration row (see BlockChecker.isStillRegistered/status-check
     *  .php's still_registered field) — the normal, expected result of
     *  someone deliberately reclaiming their number onto a new phone. */
    private fun enforceSessionState() {
        if (!ProfileStore.isRegistered(this)) return
        if (!BlockChecker.isStillRegistered(this)) {
            signOutDueToAccountMoved()
        }
    }

    /** Opportunistically uploads this phone's last-known location — the
     *  anti-theft feature (see LocationUploader). Called from the same
     *  5-second tick as the rest of the header refresh; LocationUploader's
     *  own internal throttle is what keeps this from actually hitting the
     *  network more than once every 15 minutes. Silently does nothing until
     *  registered, online, and a GPS fix exists — never anything the person
     *  needs to wait on or react to. */
    private fun maybeUploadLocation() {
        if (!ProfileStore.isRegistered(this)) return
        if (!hasActiveNetwork()) return
        val location = mesh?.myLocation() ?: return
        val deviceId = ProfileStore.get(this).deviceId
        lifecycleScope.launch(Dispatchers.IO) {
            LocationUploader.uploadIfDue(this@MainActivity, deviceId, location.first, location.second)
        }
    }

    /** Opportunistically syncs the local usage snapshot (see UsageTracker) —
     *  which screens get opened, how often, and for how long — to the
     *  backend so the admin dashboard can show feature-usage breakdowns.
     *  UsageUploader's own internal throttle keeps this to once every 15
     *  minutes; this call itself is silent either way. */
    private fun maybeUploadUsage() {
        if (!ProfileStore.isRegistered(this)) return
        if (!hasActiveNetwork()) return
        val deviceId = ProfileStore.get(this).deviceId
        lifecycleScope.launch(Dispatchers.IO) {
            UsageUploader.uploadIfDue(this@MainActivity, deviceId)
        }
    }

    /** Opportunistically syncs this phone's PUBLIC saved places (marked
     *  "share with team" at save time) to the backend — see
     *  PublicPlaceUploader. This is what lets a place saved at one site show
     *  up on the admin dashboard even for a completely separate site with no
     *  mesh connection between them at all; it never touches a place that
     *  wasn't explicitly marked shared. */
    private fun maybeUploadPublicPlaces() {
        if (!ProfileStore.isRegistered(this)) return
        if (!hasActiveNetwork()) return
        val deviceId = ProfileStore.get(this).deviceId
        val publicPlaces = com.ghaly.findme.repo.SavedPlaceRepository.places.value.filter { it.sharedByName != null }
        lifecycleScope.launch(Dispatchers.IO) {
            PublicPlaceUploader.uploadIfDue(this@MainActivity, deviceId, publicPlaces)
        }
    }

    /** Refreshes the block status from the server (throttled to once every
     *  15 minutes by BlockChecker itself, same as usage/location) and then
     *  re-applies the gate from whatever the cached state ends up being —
     *  so a block that was just lifted clears the gate on the very same
     *  check that confirms it. */
    private fun maybeCheckBlocked() {
        if (!ProfileStore.isRegistered(this)) return
        val deviceId = ProfileStore.get(this).deviceId
        if (hasActiveNetwork()) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    BlockChecker.checkIfDue(this@MainActivity, deviceId)
                }
                enforceBlockState()
                enforceSessionState()
            }
        }
    }

    /** Opportunistically syncs a newly set/changed recovery PIN (see
     *  ProfileStore.setPendingPin/PinUploader) and this phone's accounts-
     *  ledger/notes/saved-places bundle (see BackupUploader) — the other two
     *  halves of "their data should be on the host and auto go with them",
     *  alongside the profile sync checkRegistrationUpload already does. */
    private fun maybeSyncAccountSecurity() {
        if (!ProfileStore.isRegistered(this)) return
        val profile = ProfileStore.get(this)
        val phone = profile.phone
        if (phone.isNullOrBlank()) return
        if (!hasActiveNetwork()) return
        lifecycleScope.launch(Dispatchers.IO) {
            PinUploader.uploadIfPending(this@MainActivity, phone, profile.deviceId)
            BackupUploader.uploadIfDue(this@MainActivity, phone, profile.deviceId)
            // "When they are online, they can do more stuff" — this is the
            // presence signal contacts-status.php reports back as someone's
            // online dot, kept fresh on the same tick as everything else here.
            HeartbeatUploader.sendIfDue(this@MainActivity, phone, profile.deviceId)
            // Tells the server about a local code-activation, if one is
            // still waiting to sync — see ActivationUploader's doc comment.
            ActivationUploader.uploadIfPending(this@MainActivity, phone, profile.deviceId)
        }
    }

    /** Shows or dismisses the non-dismissible "blocked" gate to match the
     *  last confirmed status — never anything the person can work around:
     *  no negative button, not cancelable, nothing behind it reachable.
     *  Unlike the forced-update gate, there's no action to offer here; this
     *  only ever lifts because Ghaly unblocks the device from the
     *  dashboard, confirmed the next time this device checks in. */
    private fun enforceBlockState() {
        if (BlockChecker.isBlocked(this)) {
            if (blockedGateDialog?.isShowing != true) {
                blockedGateDialog = AlertDialog.Builder(this)
                    .setTitle(R.string.blocked_gate_title)
                    .setMessage(R.string.blocked_gate_body)
                    .setCancelable(false)
                    .create()
                blockedGateDialog?.setCanceledOnTouchOutside(false)
                blockedGateDialog?.show()
            }
        } else {
            blockedGateDialog?.dismiss()
            blockedGateDialog = null
        }
    }

    /** Shows or dismisses the non-dismissible "not activated yet" gate —
     *  "the app cannot be activated without that password or accepting the
     *  demand". Skipped entirely for admins (Ghaly/Osama are always
     *  activated server-side) and for anyone not yet past the registration
     *  gate at all (nothing to activate yet). Like enforceBlockState, this
     *  only reflects the last confirmed/locally-set state — it's
     *  enforceActivationGate() that gets called again (from onCreate, the
     *  periodic tick, right after registering, and right after a reclaim)
     *  to pick up a change. */
    private fun enforceActivationGate() {
        // Admin status can only ever turn on after a registration/status
        // sync that happened after onCreate's own one-time visibility set —
        // re-applied here on every call so the icon appears without needing
        // a restart, for the rare case it wasn't already known immediately.
        findViewById<View>(R.id.pendingActivationsButton).visibility =
            if (ProfileStore.isAdmin(this)) View.VISIBLE else View.GONE

        if (!ProfileStore.isRegistered(this)) {
            activationGateDialog?.dismiss()
            activationGateDialog = null
            return
        }
        if (ProfileStore.isActivated(this) || ProfileStore.isAdmin(this)) {
            activationGateDialog?.dismiss()
            activationGateDialog = null
            return
        }
        if (activationGateDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_activation_gate, null)
        view.findViewById<TextView>(R.id.activationAdminContacts).text = getString(
            R.string.activation_admin_contacts_fmt,
            ActivationCodes.GHALY_CONTACT_PHONE,
            ActivationCodes.OSAMA_CONTACT_PHONE
        )
        val statusView = view.findViewById<TextView>(R.id.activationRequestStatus)
        val codeInput = view.findViewById<EditText>(R.id.activationCodeInput)

        view.findViewById<Button>(R.id.activationCodeSubmitButton).setOnClickListener {
            val code = codeInput.text?.toString().orEmpty()
            if (ActivationCodes.isValid(code)) {
                ProfileStore.markActivatedLocally(this)
                val phone = ProfileStore.get(this).phone
                if (phone != null) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        ActivationUploader.uploadIfPending(this@MainActivity, phone, ProfileStore.get(this@MainActivity).deviceId)
                    }
                }
                Toast.makeText(this, R.string.activation_code_accepted, Toast.LENGTH_SHORT).show()
                enforceActivationGate()
            } else {
                Toast.makeText(this, R.string.activation_code_wrong, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<Button>(R.id.activationRequestButton).setOnClickListener {
            val phone = ProfileStore.get(this).phone
            if (phone == null) return@setOnClickListener
            if (!hasActiveNetwork()) {
                statusView.text = getString(R.string.activation_request_no_internet)
                statusView.visibility = View.VISIBLE
                return@setOnClickListener
            }
            val deviceId = ProfileStore.get(this).deviceId
            statusView.text = getString(R.string.activation_request_sending)
            statusView.visibility = View.VISIBLE
            lifecycleScope.launch {
                val success = withContext(Dispatchers.IO) {
                    ActivationMessenger.requestActivation(this@MainActivity, phone, deviceId)
                }
                statusView.text = getString(
                    if (success) R.string.activation_request_sent else R.string.activation_request_failed
                )
            }
        }

        activationGateDialog = AlertDialog.Builder(this)
            .setTitle(R.string.activation_gate_title)
            .setView(view)
            .setCancelable(false)
            .create()
        activationGateDialog?.setCanceledOnTouchOutside(false)
        activationGateDialog?.show()
    }

    /** Occasionally — at most once every 14 days, and only once someone has
     *  already gotten through the mandatory registration gate — offers a
     *  simple "anything you'd like to see added?" prompt. Entirely
     *  dismissible; never blocks anything the way the registration or
     *  forced-update gates do. */
    private fun maybePromptForFeedback() {
        if (!ProfileStore.isRegistered(this)) return
        val prefs = getSharedPreferences("feedback_prompt", Context.MODE_PRIVATE)
        val lastPromptAt = prefs.getLong("last_prompt_at", 0L)
        val fourteenDaysMs = 14L * 24 * 60 * 60 * 1000
        if (System.currentTimeMillis() - lastPromptAt < fourteenDaysMs) return
        prefs.edit().putLong("last_prompt_at", System.currentTimeMillis()).apply()
        showFeedbackDialog(isAutoPrompt = true)
    }

    /** A free-text box for suggestions, reachable any time from Tools, and
     *  occasionally offered on its own (see maybePromptForFeedback). Sends
     *  straight to the admin dashboard's feedback list. Not silent like the
     *  location/usage/registration uploaders — the person chose to say
     *  something here, so they get a clear sent/failed response. */
    private fun showFeedbackDialog(isAutoPrompt: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.dialog_feedback, null)
        val input = view.findViewById<EditText>(R.id.feedbackInput)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.feedback_dialog_title)
            .setMessage(if (isAutoPrompt) getString(R.string.feedback_dialog_auto_prompt) else null)
            .setView(view)
            .setPositiveButton(R.string.feedback_send, null)
            .setNegativeButton(R.string.feedback_not_now, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val message = input.text?.toString()?.trim().orEmpty()
            if (message.isEmpty()) {
                Toast.makeText(this, R.string.feedback_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasActiveNetwork()) {
                Toast.makeText(this, R.string.feedback_offline, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val profile = ProfileStore.get(this)
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    FeedbackUploader.send(profile.deviceId, profile.name, message)
                }
                Toast.makeText(
                    this@MainActivity,
                    if (ok) R.string.feedback_sent else R.string.feedback_failed,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) dialog.dismiss()
            }
        }
    }

    /** "Made by GYW" with a link to his own site — a small, honest credit,
     *  not a dependency: the app works exactly the same whether or not
     *  anyone ever opens it. */
    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_dialog_title)
            .setMessage(getString(R.string.about_dialog_body, versionName))
            .setPositiveButton(R.string.about_visit_website_button) { _, _ ->
                try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://md-ghaly.com")))
                } catch (e: Exception) {
                    // no browser available — nothing more useful to do here
                }
            }
            .setNegativeButton(R.string.about_close_button, null)
            .show()
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

    /** Opens the "edit my profile" dialog: name, phone, description, photo.
     *  With [isFirstRunGate] this becomes the mandatory one-time registration
     *  screen instead: name + phone become required, there's no cancel button
     *  and no way to dismiss it (back button included — see setCancelable
     *  below), and saving marks the profile as registered via
     *  [ProfileStore.markRegistered] so this never shows again and the profile
     *  becomes read-only (see [showProfileInfoDialog]). Everything here works
     *  fully offline — nothing in this dialog talks to the network. */
    private fun showEditProfileDialog(nameButton: TextView, isFirstRunGate: Boolean = false) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_profile, null)
        val photoView = view.findViewById<ImageView>(R.id.profilePhoto)
        val nameInput = view.findViewById<EditText>(R.id.profileNameInput)
        val phoneInput = view.findViewById<EditText>(R.id.profilePhoneInput)
        val descInput = view.findViewById<EditText>(R.id.profileDescriptionInput)
        val pinInput = view.findViewById<EditText>(R.id.profilePinInput)
        val recoverLink = view.findViewById<TextView>(R.id.recoverAccountLink)

        val current = ProfileStore.get(this)
        // For the first-run gate, the name field starts blank rather than the
        // generated "Miner-XXXX" placeholder, so the person has to actually
        // type their real name rather than tapping past a filled-in field.
        nameInput.setText(if (isFirstRunGate) "" else current.name)
        phoneInput.setText(current.phone)
        descInput.setText(current.description)
        pendingPhotoBase64 = current.photoBase64
        pendingPhotoView = photoView
        applyPhotoPreview(photoView, current.photoBase64)

        if (isFirstRunGate) {
            nameInput.hint = getString(R.string.registration_name_hint)
            phoneInput.hint = getString(R.string.registration_phone_hint)
        }
        PhoneValidator.attachLiveValidation(phoneInput) { getString(R.string.phone_invalid_format) }

        // Only meaningful on the first-run gate (a brand-new phone, deciding
        // between registering fresh or recovering an existing account) —
        // showEditProfileDialog is never reached any other way once
        // registered (see showProfileInfoDialog), so this is effectively
        // always true, but guarded anyway for clarity/future-proofing.
        recoverLink.visibility = if (isFirstRunGate) View.VISIBLE else View.GONE

        val photoClick = View.OnClickListener { showPhotoSourceChooser() }
        photoView.setOnClickListener(photoClick)
        view.findViewById<TextView>(R.id.choosePhotoText).setOnClickListener(photoClick)

        val builder = AlertDialog.Builder(this)
            .setTitle(if (isFirstRunGate) R.string.registration_title else R.string.edit_profile)
            // No explanatory body text here anymore — it pushed the actual
            // input fields and buttons down far enough to clip off-screen
            // on a short dialog (per feedback with a screenshot showing the
            // PIN field's hint cut off). The hint text on each field below
            // already says what it needs.
            .setMessage(null)
            .setView(view)
            .setPositiveButton(if (isFirstRunGate) R.string.registration_continue else R.string.save, null)
            .setCancelable(!isFirstRunGate)
        if (!isFirstRunGate) {
            builder.setNegativeButton(android.R.string.cancel, null)
        }

        val dialog = builder.show()
        if (isFirstRunGate) {
            dialog.setCanceledOnTouchOutside(false)
        }
        recoverLink.setOnClickListener { showRecoverAccountDialog(nameButton, dialog) }
        // Overriding the positive button's click after show(), instead of the
        // usual setPositiveButton lambda, is what lets the gate reject an
        // empty name/phone without the dialog auto-dismissing on tap.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newName = nameInput.text?.toString()?.trim().orEmpty()
            val newPhone = phoneInput.text?.toString()?.trim().orEmpty()
            val newPin = pinInput.text?.toString()?.trim().orEmpty()
            if (isFirstRunGate && (newName.isEmpty() || newPhone.isEmpty())) {
                Toast.makeText(this, R.string.registration_missing_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPhone.isNotEmpty() && !PhoneValidator.isValid(newPhone)) {
                Toast.makeText(this, R.string.phone_invalid_format, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newPin.isNotEmpty() && !Regex("^[0-9]{4,8}$").matches(newPin)) {
                Toast.makeText(this, R.string.pin_invalid_length, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (newName.isNotEmpty()) {
                DeviceIdentity.setName(this, newName)
            }
            ProfileStore.save(
                this,
                phone = newPhone.ifEmpty { null },
                description = descInput.text?.toString()?.trim(),
                photoBase64 = pendingPhotoBase64
            )
            if (newPin.isNotEmpty()) {
                ProfileStore.setPendingPin(this, newPin)
            }
            if (isFirstRunGate) {
                ProfileStore.markRegistered(this)
                checkRegistrationUpload()
                maybeSyncAccountSecurity()
            }
            refreshNameButton(nameButton)
            mesh?.sendProfile()
            dialog.dismiss()
            if (isFirstRunGate) {
                // Right behind the registration gate — "the app cannot be
                // activated without that password or accepting the demand".
                enforceActivationGate()
            }
        }
    }

    /** "Already registered? Recover with phone + PIN" — the escape hatch from
     *  the mandatory first-run gate for someone on a NEW phone who already
     *  has an account elsewhere, rather than being forced to create a second
     *  one. Needs a live connection (see AccountReclaimer); shows a plain
     *  progress toast rather than a spinner, since this can take a few
     *  seconds out in the field. On success, replaces the first-run gate
     *  outright — no need to also fill in name/phone, reclaim.php already
     *  returned them. */
    private fun showRecoverAccountDialog(nameButton: TextView, parentGateDialog: AlertDialog?) {
        val view = layoutInflater.inflate(R.layout.dialog_recover_account, null)
        val phoneInput = view.findViewById<EditText>(R.id.recoverPhoneInput)
        val pinInput = view.findViewById<EditText>(R.id.recoverPinInput)
        PhoneValidator.attachLiveValidation(phoneInput) { getString(R.string.phone_invalid_format) }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.recover_account_title)
            .setView(view)
            .setPositiveButton(R.string.recover_account_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val phone = phoneInput.text?.toString()?.trim().orEmpty()
            val pin = pinInput.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || pin.isEmpty()) {
                Toast.makeText(this, R.string.recover_missing_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!PhoneValidator.isValid(phone)) {
                Toast.makeText(this, R.string.phone_invalid_format, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!hasActiveNetwork()) {
                Toast.makeText(this, R.string.update_no_internet, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            button.isEnabled = false
            val deviceId = ProfileStore.get(this).deviceId
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    AccountReclaimer.reclaim(this@MainActivity, phone, pin, deviceId)
                }
                button.isEnabled = true
                when (result) {
                    is AccountReclaimer.Result.Success -> {
                        Toast.makeText(this@MainActivity, R.string.recover_success_toast, Toast.LENGTH_LONG).show()
                        refreshNameButton(nameButton)
                        mesh?.sendProfile()
                        dialog.dismiss()
                        // Dismiss the first-run gate behind this one too —
                        // reclaim.php already supplied everything it asked for.
                        parentGateDialog?.dismiss()
                        // Reclaim.php carried this account's real activation
                        // state across — show (or skip) the activation gate
                        // accordingly, rather than waiting for the next tick.
                        enforceActivationGate()
                    }
                    AccountReclaimer.Result.NoSuchAccount ->
                        Toast.makeText(this@MainActivity, R.string.recover_no_such_account, Toast.LENGTH_SHORT).show()
                    AccountReclaimer.Result.NoPinSet ->
                        Toast.makeText(this@MainActivity, R.string.recover_no_pin_set, Toast.LENGTH_SHORT).show()
                    is AccountReclaimer.Result.WrongPin ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.recover_wrong_pin_fmt, result.attemptsRemaining),
                            Toast.LENGTH_SHORT
                        ).show()
                    is AccountReclaimer.Result.Locked ->
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.recover_locked_fmt, (result.retryAfterSeconds / 60).coerceAtLeast(1)),
                            Toast.LENGTH_LONG
                        ).show()
                    AccountReclaimer.Result.DeviceAlreadyBoundElsewhere ->
                        Toast.makeText(this@MainActivity, R.string.recover_device_already_bound, Toast.LENGTH_LONG).show()
                    AccountReclaimer.Result.NetworkError ->
                        Toast.makeText(this@MainActivity, R.string.recover_network_error, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Read-only view of my own profile, shown once registration is complete —
     *  there is deliberately no edit affordance anywhere in here, per "he
     *  can't also edit his information" once registered. */
    private fun showProfileInfoDialog() {
        val current = ProfileStore.get(this)
        val view = layoutInflater.inflate(R.layout.dialog_profile_info, null)
        applyPhotoPreview(view.findViewById(R.id.profileInfoPhoto), current.photoBase64)
        view.findViewById<TextView>(R.id.profileInfoName).text =
            getString(R.string.profile_info_name_fmt, current.name)
        view.findViewById<TextView>(R.id.profileInfoPhone).text =
            getString(R.string.profile_info_phone_fmt, current.phone ?: getString(R.string.profile_info_none))
        val descView = view.findViewById<TextView>(R.id.profileInfoDescription)
        if (!current.description.isNullOrBlank()) {
            descView.text = getString(R.string.profile_info_description_fmt, current.description)
            descView.visibility = View.VISIBLE
        }
        view.findViewById<TextView>(R.id.profileInfoPinStatus).text = getString(
            if (ProfileStore.isPinSet(this)) R.string.profile_pin_status_set else R.string.profile_pin_status_unset
        )
        view.findViewById<TextView>(R.id.profileInfoPinButton).setOnClickListener {
            showSetPinDialog(current.phone)
        }
        val themeButton = view.findViewById<TextView>(R.id.profileInfoThemeButton)
        themeButton.text = getString(
            R.string.profile_theme_button_fmt,
            ThemePrefs.labelFor(this, ThemePrefs.currentMode(this))
        )
        themeButton.setOnClickListener {
            val newMode = ThemePrefs.cycle(this)
            Toast.makeText(
                this,
                getString(R.string.theme_changed_toast_fmt, ThemePrefs.labelFor(this, newMode)),
                Toast.LENGTH_SHORT
            ).show()
            // ThemePrefs.cycle() already triggered AppCompatDelegate to
            // recreate this Activity with the new theme applied — nothing
            // else to do here.
        }
        val onlineCodeView = view.findViewById<TextView>(R.id.profileInfoOnlineCode)
        val onlineCode = com.ghaly.findme.update.RegistrationUploader.getContactCode(this)
        onlineCodeView.text = if (onlineCode != null) {
            getString(R.string.online_my_code_fmt, onlineCode)
        } else {
            getString(R.string.online_my_code_pending)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.profile_info_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Set/change/clear the recovery PIN after registration — the one thing
     *  that's still editable once the rest of the profile is locked (see
     *  showProfileInfoDialog's doc comment). Saves locally immediately
     *  (works fully offline, same as everything else); PinUploader syncs it
     *  to the server on the next connectivity check. */
    private fun showSetPinDialog(phone: String?) {
        if (phone.isNullOrBlank()) return // can't set a PIN with no phone number on file
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(8))
            hint = getString(R.string.profile_pin_hint)
        }
        val padding = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_pin_dialog_title)
            .setMessage(R.string.set_pin_dialog_message)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val pin = input.text?.toString()?.trim().orEmpty()
                if (pin.isNotEmpty() && !Regex("^[0-9]{4,8}$").matches(pin)) {
                    Toast.makeText(this, R.string.pin_invalid_length, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                ProfileStore.setPendingPin(this, pin)
                maybeSyncAccountSecurity()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun handlePickedPhoto(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val original = BitmapFactory.decodeStream(input)
            input.close()
            applyPickedBitmap(original)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.photo_load_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Shared by both photo sources (gallery pick and camera capture) —
     *  resize to a small square thumbnail, compress, and stage it as the
     *  pending profile photo. */
    private fun applyPickedBitmap(original: Bitmap) {
        val resized = Bitmap.createScaledBitmap(original, 128, 128, true)
        val out = ByteArrayOutputStream()
        resized.compress(Bitmap.CompressFormat.JPEG, 70, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        pendingPhotoBase64 = base64
        pendingPhotoView?.let { applyPhotoPreview(it, base64) }
    }

    /** Tapping the photo/"Choose photo" area now offers both sources instead
     *  of jumping straight to the gallery picker. */
    private fun showPhotoSourceChooser() {
        val options = arrayOf(
            getString(R.string.photo_source_camera),
            getString(R.string.photo_source_gallery)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_photo)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchCamera()
                    1 -> pickPhotoLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            takePhotoLauncher.launch(null)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    /** Opens a 1:1 online chat thread with an online contact — called from
     *  [OnlineContactsFragment] when the person taps a contact row. */
    fun openOnlineChat(phone: String, name: String) {
        replaceContentSafely(OnlineChatFragment.newInstance(phone, name), addToBackStack = true)
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
        AppLog.log("MainActivity", "switching screen to ${fragment::class.simpleName}")
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
            // Usage analytics: every screen reached from a tab or from Tools
            // comes through here, so this single point is enough to track
            // which features get opened and for how long — see UsageTracker.
            UsageTracker.onScreenShown(this, fragment::class.simpleName ?: "unknown")
        } catch (e: Throwable) {
            AppLog.log("MainActivity", "replaceContentSafely threw for ${fragment::class.simpleName}: $e")
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
            AppLog.log(
                "MainActivity",
                "${fragment::class.simpleName} opened — container=${containerWidth}x${containerHeight}px view=${viewWidth}x${viewHeight}px shrunk=${heightShrunk || widthShrunk}"
            )
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
