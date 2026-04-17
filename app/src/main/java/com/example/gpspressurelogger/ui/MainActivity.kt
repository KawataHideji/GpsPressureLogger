package com.example.gpspressurelogger.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.gpspressurelogger.service.LoggingService
import com.example.gpspressurelogger.ui.navigation.AppNavGraph
import com.example.gpspressurelogger.ui.navigation.Screen
import com.example.gpspressurelogger.ui.theme.GpsPressureLoggerTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SCREEN = "open_screen"
        const val SCREEN_MAP = "map"
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 基本的な位置情報権限が得られたらサービスを開始
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLoggingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val startDestination = when (intent.getStringExtra(EXTRA_OPEN_SCREEN)) {
            SCREEN_MAP -> Screen.Map.route
            else       -> Screen.Home.route
        }

        setContent {
            GpsPressureLoggerTheme {
                AppNavGraph(startDestination = startDestination)
            }
        }
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startLoggingService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startLoggingService() {
        ContextCompat.startForegroundService(
            this, Intent(this, LoggingService::class.java)
        )
    }
}
