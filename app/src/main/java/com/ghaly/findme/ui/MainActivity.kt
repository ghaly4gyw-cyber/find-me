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
import com.ghaly.findme.R
import com.ghaly.findme.mesh.ActivityWatcher
import com.ghaly.findme.mesh.DeviceIdentity
import com.ghaly.findme.mesh.MeshForegroundService
import com.ghaly.findme.mesh.MeshManager
import com.ghaly.findme.mesh.Permissions
import com.ghaly.findme.mesh.ProfileStore
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    var mesh: MeshManager? = null
        private set

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
    private lateinit var permissionBar: android.view.View
    private lateinit var batteryBar: android.view.View
    private lateinit var locationServicesBar: android.view.View
    private lateinit var tabButtons: List<Button>
    private var selectedTabIndex = 0

    /** Holds the chosen photo (base64) while the edit-profile dialog is open. */
    private var pendingPhotoBase64: String? = null
    private var pendingPhotoView: ImageView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MeshForegroundService.LocalBinder
            mesh = binder.getMesh()
            bound = true
            meshStatus.text = getString(R.string.mesh_status_on)
            // Surface every advertising/discovery/connection step (and any failure)
            // right in the header — this is what actually told us what's wrong
            // the first time two real phones couldn't find each other, instead of
            // that only existing in Logcat where nobody could see it.
            mesh?.onStatusChanged = { text -> runOnUiThread { meshStatus.text = text } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mesh = null
            bound = false
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

        meshStatus = findViewById(R.id.meshStatus)
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

        val nameButton = findViewById<TextView>(R.id.myNameButton)
        refreshNameButton(nameButton)
        nameButton.setOnClickListener { showEditProfileDialog(nameButton) }

        findViewById<TextView>(R.id.languageButton).setOnClickListener { showLanguageDialog() }

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
    }

    override fun onStart() {
        super.onStart()
        refreshPermissionState()
        refreshBatteryState()
        refreshLocationServicesState()
        // refreshPermissionState() above already calls startMesh() when granted.
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
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_right, R.anim.slide_out_left,
                R.anim.slide_in_right, R.anim.slide_out_left
            )
            .replace(R.id.contentContainer, fragment)
            .commit()
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
}
