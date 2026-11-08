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
    private lateinit var meshStatus: TextView
    private lateinit var permissionBar: android.view.View

    /** Holds the chosen photo (base64) while the edit-profile dialog is open. */
    private var pendingPhotoBase64: String? = null
    private var pendingPhotoView: ImageView? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as MeshForegroundService.LocalBinder
            mesh = binder.getMesh()
            bound = true
            meshStatus.text = getString(R.string.mesh_status_on)
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
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            permissionLauncher.launch(Permissions.required())
        }

        val nameButton = findViewById<TextView>(R.id.myNameButton)
        refreshNameButton(nameButton)
        nameButton.setOnClickListener { showEditProfileDialog(nameButton) }

        findViewById<Button>(R.id.tabPeers).setOnClickListener { showFragment(PeersFragment()) }
        findViewById<Button>(R.id.tabMap).setOnClickListener { showFragment(RadarMapFragment()) }
        findViewById<Button>(R.id.tabChat).setOnClickListener { showFragment(ChatFragment()) }
        findViewById<Button>(R.id.tabSos).setOnClickListener { showFragment(SosFragment()) }
        findViewById<Button>(R.id.tabPosts).setOnClickListener { showFragment(PostsFragment()) }
        findViewById<Button>(R.id.tabGroups).setOnClickListener { showFragment(GroupsFragment()) }

        if (savedInstanceState == null) {
            showFragment(PeersFragment())
        }

        refreshPermissionState()
    }

    override fun onStart() {
        super.onStart()
        refreshPermissionState()
        if (Permissions.allGranted(this)) {
            startMesh()
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }

    private fun refreshPermissionState() {
        val granted = Permissions.allGranted(this)
        permissionBar.visibility = if (granted) android.view.View.GONE else android.view.View.VISIBLE
        if (granted) startMesh()
    }

    private fun startMesh() {
        MeshForegroundService.start(this)
        val intent = Intent(this, MeshForegroundService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
            Toast.makeText(this, "Couldn't load that photo", Toast.LENGTH_SHORT).show()
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

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                android.R.anim.fade_in, android.R.anim.fade_out,
                android.R.anim.fade_in, android.R.anim.fade_out
            )
            .replace(R.id.contentContainer, fragment)
            .commit()
    }
}
