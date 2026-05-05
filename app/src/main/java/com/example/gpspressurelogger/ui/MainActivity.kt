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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.example.gpspressurelogger.service.LoggingService
import com.example.gpspressurelogger.ui.navigation.AppNavGraph
import com.example.gpspressurelogger.ui.navigation.Screen
import com.example.gpspressurelogger.ui.theme.GpsPressureLoggerTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_SCREEN = "open_screen"
        const val EXTRA_RESET_GRAPH_WINDOW = "reset_graph_window"
        const val SCREEN_HOME = "home"
        const val SCREEN_MAP = "map"
        const val ACTION_OPEN_GRAPH = "com.example.gpspressurelogger.action.OPEN_GRAPH"
        const val ACTION_OPEN_MAP = "com.example.gpspressurelogger.action.OPEN_MAP"
    }

    data class OpenRequest(
        val route: String,
        val requestId: Long,
        val graphResetRequestId: Long
    )

    private var openRequestId = 0L
    private val openRequests = MutableStateFlow(OpenRequest(Screen.Home.route, 0L, 0L))

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        // 通知権限だけを要求した場合、results には位置権限が含まれない。
        // 現在の許可状態を見直して、記録に必須の権限が揃っていればサービスを開始する。
        if (hasRequiredLoggingPermissions()) {
            startLoggingService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val initialOpenRequest = createOpenRequest(intent)
        openRequests.value = initialOpenRequest

        setContent {
            val openRequest by openRequests.collectAsState()
            GpsPressureLoggerTheme {
                AppNavGraph(
                    startDestination = initialOpenRequest.route,
                    requestedRoute = openRequest.route,
                    requestId = openRequest.requestId,
                    graphResetRequestId = openRequest.graphResetRequestId
                )
            }
        }
        checkAndRequestPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequests.value = createOpenRequest(intent)
    }

    private fun createOpenRequest(intent: Intent?): OpenRequest {
        val route = when (intent?.getStringExtra(EXTRA_OPEN_SCREEN)) {
            SCREEN_MAP -> Screen.Map.route
            SCREEN_HOME -> Screen.Home.route
            else -> Screen.Home.route
        }
        val requestId = ++openRequestId
        val shouldResetGraph = route == Screen.Home.route &&
            intent?.getBooleanExtra(EXTRA_RESET_GRAPH_WINDOW, false) == true
        return OpenRequest(
            route = route,
            requestId = requestId,
            graphResetRequestId = if (shouldResetGraph) requestId else 0L
        )
    }

    private fun checkAndRequestPermissions() {
        val requiredPermissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION
        )
        val optionalPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            emptyList()
        }

        val missingRequired = requiredPermissions.filterNot(::hasPermission)
        val missingOptional = optionalPermissions.filterNot(::hasPermission)

        if (missingRequired.isEmpty()) {
            startLoggingService()
            if (missingOptional.isNotEmpty()) {
                permissionLauncher.launch(missingOptional.toTypedArray())
            }
        } else {
            permissionLauncher.launch((missingRequired + missingOptional).toTypedArray())
        }
    }

    private fun hasRequiredLoggingPermissions(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
            hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun startLoggingService() {
        ContextCompat.startForegroundService(
            this, Intent(this, LoggingService::class.java)
        )
    }
}
