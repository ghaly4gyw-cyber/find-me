package com.ghaly.findme.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.ghaly.findme.R
import com.ghaly.findme.mesh.MeshForegroundService
import com.ghaly.findme.mesh.MeshManager
import com.ghaly.findme.mesh.Permissions

class MainActivity : AppCompatActivity() {

    var mesh: MeshManager? = null
        private set

    private var bound = false
    private lateinit var meshStatus: TextView
    private lateinit var permissionBar: android.view.View

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        meshStatus = findViewById(R.id.meshStatus)
        permissionBar = findViewById(R.id.permissionBar)
        findViewById<Button>(R.id.grantPermissionsButton).setOnClickListener {
            permissionLauncher.launch(Permissions.required())
        }

        findViewById<Button>(R.id.tabPeers).setOnClickListener { showFragment(PeersFragment()) }
        findViewById<Button>(R.id.tabChat).setOnClickListener { showFragment(ChatFragment()) }
        findViewById<Button>(R.id.tabSos).setOnClickListener { showFragment(SosFragment()) }

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

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.contentContainer, fragment)
            .commit()
    }
}
