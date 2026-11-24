package dev.heatcontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import dev.heatcontrol.ble.HeaterRepository

class MainActivity : ComponentActivity() {
    private val repo by lazy { HeaterRepository.get(this) }
    private var permissionsGranted by mutableStateOf(false)

    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permissionsGranted = res.values.all { it }
        if (permissionsGranted) repo.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionsGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        setContent {
            HeatTheme {
                App(repo, permissionsGranted, onRequestPermissions = { request.launch(permissions) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (permissionsGranted) repo.start() else request.launch(permissions)
    }

    override fun onStop() {
        super.onStop()
        // Heaters usually accept only one connection; release them when we're in the background.
        if (permissionsGranted) repo.stop()
    }
}
