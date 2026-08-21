package com.rjnsdev.linklift.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val viewModel: LinkLiftViewModel by viewModels()

    /**
     * Android 13+ requires [Manifest.permission.POST_NOTIFICATIONS] at runtime
     * or the system hides **all** notifications from this app — including
     * DownloadManager's in-progress and finished download notifications.
     */
    private val requestPostNotificationsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    /** Android 13+ (API 33): runtime permission or no DownloadManager / app notifications. */
    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestPostNotificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LinkLiftRoot(
                viewModel = viewModel,
                ensureNotificationPermission = ::requestPostNotificationsIfNeeded,
            )
        }
        // Ask on launch so download progress / completion notifications work without
        // waiting until the first Download tap (Android 13+).
        window.decorView.post { requestPostNotificationsIfNeeded() }
        handleIncomingShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingShareIntent(intent)
    }

    private fun handleIncomingShareIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return

        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: intent.clipData?.getItemAt(0)?.text?.toString()
            ?: intent.dataString

        val handled = viewModel.handleSharedText(sharedText)
        if (handled) {
            setIntent(Intent())
        }
    }
}

