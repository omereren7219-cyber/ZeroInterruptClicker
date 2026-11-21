package com.zerointerrupt.clicker.ui.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerointerrupt.clicker.R
import com.zerointerrupt.clicker.automation.AutomationReceiver
import com.zerointerrupt.clicker.automation.LocalRpcServer
import com.zerointerrupt.clicker.model.ClickGlobalSettings
import com.zerointerrupt.clicker.shizuku.TouchMuxUserService
import com.zerointerrupt.clicker.ui.MainScreenCallbacks
import com.zerointerrupt.clicker.ui.MainUiState
import com.zerointerrupt.clicker.ui.components.CheckSwitch
import com.zerointerrupt.clicker.ui.components.ExpressiveCard
import com.zerointerrupt.clicker.ui.components.ExpressiveConnectedGroup
import com.zerointerrupt.clicker.ui.components.ExpressiveOption
import com.zerointerrupt.clicker.ui.components.PageTopHeader
import com.zerointerrupt.clicker.ui.components.SlotMatrixStrip

@Composable
fun LabTab(
    state: MainUiState,
    callbacks: MainScreenCallbacks,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var versionTapCount by remember { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PageTopHeader(title = "Laboratuvar")

        ExpressiveCard {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Görünüm ve denetim",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Uygulama görünümü",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ExpressiveConnectedGroup(
                        options = listOf(
                            ExpressiveOption(ClickGlobalSettings.THEME_SYSTEM, "Sistemi izle"),
                            ExpressiveOption(ClickGlobalSettings.THEME_DARK, "Koyu"),
                            ExpressiveOption(ClickGlobalSettings.THEME_LIGHT, "Açık")
                        ),
                        selectedValue = state.themeMode,
                        onSelect = callbacks.onThemeModeChange,
                        height = 42.dp
                    )
                }

                LabSwitchRow(
                    title = "Dinamik renk çıkarımı",
                    checked = state.dynamicColor,
                    onCheckedChange = callbacks.onDynamicColorChange
                )

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Uygulama simgesi",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    ExpressiveConnectedGroup(
                        options = listOf(
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_SYSTEM, "Sistemi izle"),
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_DARK, "Koyu"),
                            ExpressiveOption(ClickGlobalSettings.LAUNCHER_LIGHT, "Açık")
                        ),
                        selectedValue = state.launcherIconMode,
                        onSelect = callbacks.onLauncherIconModeChange,
                        height = 42.dp
                    )
                    LabSwitchRow(
                        title = "Uygulama simgesinde dinamik renk çıkarımı",
                        checked = state.launcherDynamicIcon,
                        onCheckedChange = callbacks.onLauncherDynamicIconChange
                    )
                    Text(
                        text = "Sistem başlatıcısına Monochrome tek renk katmanı sunar (Android 13+ gerekir; başlatıcı tema simgesi renklerini desteklemeli/etkinleştirmiş olmalıdır)",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant
                    )
                }

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                LabSwitchRow(
                    title = "Ses kısma tuşu kontrolü",
                    checked = state.volumeKeyToggleEnabled,
                    onCheckedChange = callbacks.onVolumeKeyToggleChange
                )

                HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Durum çubuğu kutucuğu",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    )
                    FilledTonalButton(
                        onClick = callbacks.onRequestAddQuickTile,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_tile),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Ekle", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ExpressiveCard(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .clickable(enabled = !state.proModeEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    val next = versionTapCount + 1
                    if (next >= 7) {
                        versionTapCount = 0
                        callbacks.onProModeChange(true)
                    } else {
                        versionTapCount = next
                    }
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lab),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = if (state.proModeEnabled) {
                                "Sürüm v${state.appVersionName} · Profesyonel mod"
                            } else {
                                "Sürüm v${state.appVersionName}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!state.proModeEnabled && versionTapCount in 1..6) {
                            Text(
                                text = "${7 - versionTapCount} kez kaldı",
                                style = MaterialTheme.typography.bodySmall,
                                color = cs.primary
                            )
                        }
                    }
                }

                if (state.proModeEnabled) {
                    CheckSwitch(
                        checked = true,
                        onCheckedChange = { enabled ->
                            if (!enabled) {
                                versionTapCount = 0
                                callbacks.onProModeChange(false)
                            }
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = state.proModeEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ProHardwareConsoleCard(state = state, callbacks = callbacks)
                ProRpcBridgeCard(state = state, callbacks = callbacks)
            }
        }

        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ProHardwareConsoleCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val snap = state.engineSnapshot

    ExpressiveCard(
        border = BorderStroke(1.dp, cs.primary.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_terminal),
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Donanım izleme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = callbacks.onRefreshCapabilities,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Donanım algılamasını yenile",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DiagLine("Shizuku durumu", "${state.connectionState} · ${modeName(snap.engineMode)}")
                    DiagLine("UID / PID", "${snap.uid} / ${snap.pid}")
                    DiagLine("Fiziksel dokunmatik düğümleri", snap.touchDeviceInfo.ifBlank { "Okunabilir düğüm bulunamadı" })
                    DiagLine("Abs aralığı", snap.absRangeText.ifBlank { "Başlatılmadı" })
                    DiagLine(
                        "/dev/uinput",
                        "${if (snap.uinputWritable) "RW kullanılabilir" else "Kısıtlı"} (errno=${snap.uinputErrno})"
                    )
                    DiagLine(
                        "/dev/uhid",
                        "${if (snap.uhidWritable) "RW kullanılabilir" else "Kısıtlı"} (errno=${snap.uhidErrno})"
                    )
                    DiagLine(
                        "/dev/input/*",
                        "${if (snap.touchDeviceReadable) "Okunabilir" else "Okunamaz"} (errno=${snap.touchErrno})"
                    )
                    DiagLine("Motor ayrıntıları", snap.statusDetail)
                    if (snap.nativeProbeError.isNotBlank()) {
                        Text(
                            text = "Yerel motor hatası: ${snap.nativeProbeError}",
                            style = MaterialTheme.typography.labelSmall,
                            color = cs.error,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callbacks.onCleanupOrphans()
                    },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("Yetim süreçleri zorla temizle", maxLines = 1, fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callbacks.onReleaseNative()
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    Text("Tekel durumunu bırak", maxLines = 1, fontSize = 12.sp)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enjeksiyon arka ucu",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                ExpressiveConnectedGroup(
                    options = listOf(
                        ExpressiveOption(ClickGlobalSettings.BACKEND_AUTO, "Otomatik"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_UINPUT, "UInput"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_UHID, "UHID"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_HYBRID, "Hibrit"),
                        ExpressiveOption(ClickGlobalSettings.BACKEND_FALLBACK, "Yedek")
                    ),
                    selectedValue = state.preferredBackend,
                    onSelect = callbacks.onPreferredBackendChange,
                    height = 40.dp
                )
            }

            HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.35f))

            SlotMatrixStrip(slots = state.slotStates)
        }
    }
}

@Composable
private fun ProRpcBridgeCard(
    state: MainUiState,
    callbacks: MainScreenCallbacks
) {
    val cs = MaterialTheme.colorScheme
    val tokenDisplay = state.rpcToken.ifBlank { "<Oluşturulmadı>" }

    val adbCommand = remember(tokenDisplay) {
        "adb shell am broadcast -p com.zerointerrupt.clicker -a ${AutomationReceiver.ACTION_RPC} " +
            "--es token \"$tokenDisplay\" --es cmd \"TAP\" --ef x 540 --ef y 1200 --el duration_ms 30"
    }

    val pythonExample = remember(tokenDisplay) {
        """
        # 1. Önce çalıştırın: adb forward tcp:${LocalRpcServer.RPC_PORT} tcp:${LocalRpcServer.RPC_PORT}
        import json, socket

        TOKEN = "$tokenDisplay"
        with socket.create_connection(("127.0.0.1", ${LocalRpcServer.RPC_PORT}), timeout=3) as s:
            def rpc(payload):
                payload["token"] = TOKEN
                s.sendall((json.dumps(payload) + "\n").encode())
                return json.loads(s.makefile().readline())

            print(rpc({"cmd": "ping"}))
            print(rpc({"cmd": "status"}))
            print(rpc({"cmd": "tap", "x": 540, "y": 1200, "durationMs": 30}))
            print(rpc({"cmd": "swipe", "x1": 300, "y1": 1400, "x2": 300, "y2": 600, "durationMs": 280}))
        """.trimIndent()
    }

    ExpressiveCard(
        border = BorderStroke(1.dp, cs.tertiary.copy(alpha = 0.30f))
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "RPC arayüz köprüsü",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.rpcServerRunning) {
                            "127.0.0.1:${LocalRpcServer.RPC_PORT}"
                        } else {
                            "Etkin değil"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.rpcServerRunning) cs.primary else cs.onSurfaceVariant
                    )
                }
                CheckSwitch(
                    checked = state.rpcBridgeEnabled,
                    onCheckedChange = callbacks.onRpcBridgeEnabledChange
                )
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = cs.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Erişim Tokenı",
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant
                    )
                    Text(
                        text = tokenDisplay,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { callbacks.onCopyText("Erişim Tokenı", tokenDisplay) },
                            enabled = state.rpcToken.isNotBlank(),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Token'ı kopyala", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = callbacks.onResetRpcToken,
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Token'ı sıfırla", fontSize = 12.sp)
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Arayüz A · ADB Broadcast (START/STOP/SWITCH_PRESET/TAP/SWIPE)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = cs.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                OutlinedButton(
                    onClick = { callbacks.onCopyText("ADB yayın komutları", adbCommand) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("ADB yayın hata ayıklama komutunu kopyala")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Arayüz B · 127.0.0.1:${LocalRpcServer.RPC_PORT} satır satır JSON-RPC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Protokol komutları: ping / status / tap / swipe / hold_down / hold_up / start / stop / switch_preset",
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = { callbacks.onCopyText("Python RPC örneği", pythonExample) },
                    shape = CircleShape,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Python çağrı örneğini tek dokunuşla kopyala")
                }
            }
        }
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun LabSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        CheckSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

private fun modeName(mode: Int): String = when (mode) {
    TouchMuxUserService.MODE_NATIVE_UINPUT -> "Tier1 UInput"
    TouchMuxUserService.MODE_NATIVE_UHID -> "Tier2 UHID"
    TouchMuxUserService.MODE_HYBRID_EVDEV_INJECT -> "Tier 3 Hibrit"
    TouchMuxUserService.MODE_SHIZUKU_FALLBACK -> "Tier 4 Geri dönüş"
    else -> "Boşta"
}
