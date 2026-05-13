package com.aiagent.android.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aiagent.android.ui.chat.ChatScreen
import com.aiagent.android.ui.chat.ChatViewModel
import com.aiagent.android.ui.theme.KiroColors
import com.aiagent.android.ui.theme.KiroTheme
import kotlinx.coroutines.launch

/**
 * Single-Activity host for the Kiro-styled chat. Replaces the legacy three-tab
 * (Agent / Settings / Permissions) screen with one transcript view + bottom sheets.
 * Activity-result launchers stay here because the agent's tools rely on system
 * dialogs (MediaProjection consent, overlay permission, SAF folder picker, …).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.onProjectionResult(result.resultCode, result.data)
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissionStatus()
        }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.refreshPermissionStatus()
        }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            viewModel.refreshPermissionStatus()
        }

    private val openTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) viewModel.onFolderPicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(KiroColors.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(KiroColors.Background.toArgb()),
        )

        // Auto-launch the system MediaProjection dialog whenever the agent's tool layer
        // requests screen capture / recording. The ChatViewModel suspends until the result
        // arrives back through `onProjectionResult`.
        lifecycleScope.launch {
            var seen = 0L
            viewModel.projectionRequests.collect { tick ->
                if (tick > seen) {
                    seen = tick
                    launchProjectionConsent()
                }
            }
        }

        setContent {
            KiroTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = KiroColors.Background,
                ) {
                    ChatScreen(
                        viewModel = viewModel,
                        onMicClick = { /* hook voice instruction to ChatViewModel later */ },
                        onRequestAccessibility = ::launchAccessibilitySettings,
                        onRequestOverlay = ::launchOverlayPermission,
                        onRequestStorage = ::launchManageStoragePermission,
                        onRequestMic = ::requestMicPermission,
                        onPickFolder = ::launchPickFolder,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
    }

    private fun launchAccessibilitySettings() {
        startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun launchOverlayPermission() {
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayLauncher.launch(intent)
    }

    private fun launchManageStoragePermission() {
        if (Build.VERSION.SDK_INT < 30) return
        val intent = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        manageStorageLauncher.launch(intent)
    }

    private fun requestMicPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            startActivity(
                Intent(
                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchPickFolder() {
        openTreeLauncher.launch(null)
    }

    /**
     * When a tool call (`start_screen_recording` or any screenshot-via-projection action)
     * suspends waiting for the user's consent, the [ChatViewModel] flips an internal flag.
     * That signal is surfaced through [ChatViewModel.isProjectionPending] — we keep a tiny
     * hot polling watcher running on the IO dispatcher so any pending consent transparently
     * pops the system dialog without the user having to tap an extra button.
     *
     * NOTE: kept as a private helper rather than auto-wired here to avoid lifecycle leaks;
     * the legacy MainActivity surfaced an explicit button instead. The chat layout has no
     * such button slot — see Settings → "Доступ к захвату экрана" for manual consent.
     */
    private fun launchProjectionConsent() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
