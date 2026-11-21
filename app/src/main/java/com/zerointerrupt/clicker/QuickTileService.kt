package com.zerointerrupt.clicker

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.zerointerrupt.clicker.overlay.OverlayService
import com.zerointerrupt.clicker.overlay.OverlayStateHub

/**
 * Android Quick Settings Tile for one-tap floating overlay toggle.
 */
class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        if (!Settings.canDrawOverlays(this)) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchActivityAndCollapseCompat(permIntent)
            return
        }

        val currentlyVisible = OverlayStateHub.overlayVisible.value
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = if (currentlyVisible) {
                OverlayService.ACTION_STOP_OVERLAY
            } else {
                OverlayService.ACTION_START_OVERLAY
            }
        }
        if (currentlyVisible) {
            startService(serviceIntent)
        } else {
            ContextCompat.startForegroundService(this, serviceIntent)
        }
        refreshTileState(overrideVisible = !currentlyVisible)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivityAndCollapseCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pi = PendingIntent.getActivity(
                this,
                3001,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun refreshTileState(overrideVisible: Boolean? = null) {
        val tile = qsTile ?: return
        val visible = overrideVisible ?: OverlayStateHub.overlayVisible.value
        val running = OverlayStateHub.clickingRunning.value
        tile.label = getString(R.string.tile_label)
        tile.state = if (visible) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                running && visible -> "Otomatik dokunma çalışıyor"
                visible -> "Yüzen pencere etkin"
                else -> "Etkinleştir"
            }
        }
        tile.updateTile()
    }
}
