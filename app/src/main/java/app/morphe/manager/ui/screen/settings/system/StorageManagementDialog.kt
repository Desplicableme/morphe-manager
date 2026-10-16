/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.settings.system

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.morphe.manager.R
import app.morphe.manager.domain.repository.StorageStats
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.ui.viewmodel.StorageManagementViewModel
import app.morphe.manager.util.formatBytes
import app.morphe.manager.util.formatUsedFree
import app.morphe.manager.util.openAppDetailsSettings
import app.morphe.manager.util.toast
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun StorageManagementDialog(
    onDismissRequest: () -> Unit,
    viewModel: StorageManagementViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsStateWithLifecycle()

    val nothingToClearText = stringResource(R.string.settings_system_storage_nothing_to_clear)
    val clearedTemplate = stringResource(R.string.settings_system_storage_cleared)

    val onCleared: (Long) -> Unit = { freed ->
        val message = if (freed <= 0L) nothingToClearText
        else clearedTemplate.format(context.formatBytes(freed))
        context.toast(message)
    }

    var apkDialog by remember { mutableStateOf<ApkManagementType?>(null) }
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var showClearOriginalApksConfirm by remember { mutableStateOf(false) }
    var showQuickCleanConfirm by remember { mutableStateOf(false) }
    // Incremented on manual refresh to re-key the histogram and replay its entry animation.
    var histogramNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    apkDialog?.let { type ->
        ApkManagementDialog(
            type = type,
            onDismissRequest = {
                apkDialog = null
                viewModel.refresh()
            }
        )
    }

    if (showClearAllConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_storage_clear_all),
            message = stringResource(R.string.settings_system_storage_clear_all_confirm),
            primaryText = stringResource(R.string.clear),
            onConfirm = {
                showClearAllConfirm = false
                viewModel.clearAllCaches(onCleared)
            },
            onDismiss = { showClearAllConfirm = false },
            // Empty caches free nothing, so they are left out
            items = listOf(
                Triple(Icons.Outlined.CloudDownload, R.string.settings_system_storage_http_cache_title, stats.httpCacheBytes),
                Triple(Icons.Outlined.Share, R.string.settings_system_storage_installer_cache_title, stats.installerShareBytes),
                Triple(Icons.Outlined.Build, R.string.settings_system_storage_patcher_workspace_title, stats.patcherWorkspaceBytes),
                Triple(Icons.Outlined.HourglassEmpty, R.string.settings_system_storage_temporary_title, stats.temporaryBytes)
            ).filter { (_, _, bytes) -> bytes > 0 }.map { (icon, title, bytes) ->
                ConfirmItem(icon, stringResource(title), context.formatBytes(bytes))
            }
        )
    }

    if (showClearOriginalApksConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_storage_clear_original_apks),
            message = stringResource(R.string.settings_system_storage_clear_original_apks_desc),
            primaryText = stringResource(R.string.clear),
            onConfirm = {
                showClearOriginalApksConfirm = false
                viewModel.clearOriginalApks(onCleared)
            },
            onDismiss = { showClearOriginalApksConfirm = false },
            items = listOf(
                ConfirmItem(Icons.Outlined.Storage, stringResource(R.string.settings_system_original_apks_title), context.formatBytes(stats.originalApksBytes))
            )
        )
    }

    if (showQuickCleanConfirm) {
        ConfirmDialog(
            title = stringResource(R.string.settings_system_storage_quick_clean_title),
            message = stringResource(R.string.settings_system_storage_quick_clean_desc),
            primaryText = stringResource(R.string.settings_system_storage_quick_clean_button),
            onConfirm = {
                showQuickCleanConfirm = false
                viewModel.clearAllCachesAndOriginals(onCleared)
            },
            onDismiss = { showQuickCleanConfirm = false },
            items = listOf(
                Triple(Icons.Outlined.Storage, R.string.settings_system_original_apks_title, stats.originalApksBytes),
                Triple(Icons.Outlined.CloudDownload, R.string.settings_system_storage_http_cache_title, stats.httpCacheBytes),
                Triple(Icons.Outlined.Share, R.string.settings_system_storage_installer_cache_title, stats.installerShareBytes),
                Triple(Icons.Outlined.Build, R.string.settings_system_storage_patcher_workspace_title, stats.patcherWorkspaceBytes),
                Triple(Icons.Outlined.HourglassEmpty, R.string.settings_system_storage_temporary_title, stats.temporaryBytes)
            ).filter { (_, _, bytes) -> bytes > 0 }.map { (icon, title, bytes) ->
                ConfirmItem(icon, stringResource(title), context.formatBytes(bytes))
            }
        )
    }

    AppDialog(
        onDismissRequest = onDismissRequest,
        padding = DialogPadding.Compact,
        scrollable = false,
        contentArrangement = Arrangement.Top,
        fillContentHeight = true,
        footer = {
            AppDialogOutlinedButton(
                text = stringResource(R.string.close),
                onClick = onDismissRequest,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        // Nothing has been read until the first reading lands, and a real one always has room free
        val loading = stats == StorageStats.Empty

        // Headed like the other lists, with how much Morphe holds against what is left free
        // where the subtitle goes, above the breakdown that scrolls under it
        val accent = MaterialTheme.colorScheme.primary
        ListDialogHeader(
            icon = { modifier ->
                ListDialogHeaderIcon(icon = Icons.Outlined.Storage, color = accent, modifier = modifier)
            },
            title = stringResource(R.string.settings_system_storage_management_title),
            subtitle = context.formatUsedFree(used = stats.appUsedBytes, free = stats.deviceFreeBytes),
            subtitleLoading = loading,
            accentColor = accent
        ) {
            TitleAction(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.refresh),
                onClick = {
                    viewModel.refresh()
                    histogramNonce++
                },
                style = TitleActionStyle.Accent
            )
        }

        DialogScrollColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = Defaults.ItemSpacing),
            verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
        ) {
            key(histogramNonce) {
                StorageHistogram(segments = stats.toSegments(), loading = loading)
            }

            SettingsGroup {
                DataRow(
                    icon = Icons.Outlined.Storage,
                    accentColor = StorageColors.OriginalApks,
                    title = stringResource(R.string.settings_system_original_apks_title),
                    description = stringResource(R.string.settings_system_original_apks_description),
                    onClick = { apkDialog = ApkManagementType.ORIGINAL }
                )
                SettingsDivider()
                DataRow(
                    icon = Icons.Outlined.Apps,
                    accentColor = StorageColors.PatchedApks,
                    title = stringResource(R.string.settings_system_patched_apks_title),
                    description = stringResource(R.string.settings_system_patched_apks_description),
                    onClick = { apkDialog = ApkManagementType.PATCHED }
                )
            }

            SettingsGroup {
                val autoDeleteOriginalApks by viewModel.prefs.autoDeleteOriginalApks.getAsState()
                val autoCleanCache by viewModel.prefs.autoCleanCacheAfterPatching.getAsState()
                val scope = rememberCoroutineScope()

                SettingsSwitchItem(
                    checked = autoDeleteOriginalApks,
                    onToggle = { scope.launch { viewModel.prefs.autoDeleteOriginalApks.update(!autoDeleteOriginalApks) } },
                    title = stringResource(R.string.settings_system_storage_auto_delete_title),
                    subtitle = stringResource(R.string.settings_system_storage_auto_delete_desc),
                    icon = Icons.Outlined.DeleteSweep
                )
                SettingsDivider()
                SettingsSwitchItem(
                    checked = autoCleanCache,
                    onToggle = { scope.launch { viewModel.prefs.autoCleanCacheAfterPatching.update(!autoCleanCache) } },
                    title = stringResource(R.string.settings_system_storage_auto_clean_cache_title),
                    subtitle = stringResource(R.string.settings_system_storage_auto_clean_cache_desc),
                    icon = Icons.Outlined.DeleteOutline
                )
            }

            SettingsGroup {
                CacheActionRow(
                    icon = Icons.Outlined.DeleteForever,
                    accentColor = MaterialTheme.colorScheme.primary,
                    title = stringResource(R.string.settings_system_storage_quick_clean_title),
                    description = stringResource(R.string.settings_system_storage_quick_clean_desc),
                    bytes = stats.totalCacheBytes + stats.originalApksBytes,
                    onClear = { showQuickCleanConfirm = true }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.DeleteSweep,
                    accentColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.settings_system_storage_clear_all),
                    description = stringResource(R.string.settings_system_storage_clear_all_description),
                    bytes = stats.totalCacheBytes,
                    onClear = { showClearAllConfirm = true }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.Storage,
                    accentColor = StorageColors.OriginalApks,
                    title = stringResource(R.string.settings_system_storage_clear_original_apks),
                    description = stringResource(R.string.settings_system_storage_clear_original_apks_desc),
                    bytes = stats.originalApksBytes,
                    onClear = { showClearOriginalApksConfirm = true }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.CloudDownload,
                    accentColor = StorageColors.HttpCache,
                    title = stringResource(R.string.settings_system_storage_http_cache_title),
                    description = stringResource(R.string.settings_system_storage_http_cache_description),
                    bytes = stats.httpCacheBytes,
                    onClear = { viewModel.clearHttpCache(onCleared) }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.Share,
                    accentColor = StorageColors.InstallerShare,
                    title = stringResource(R.string.settings_system_storage_installer_cache_title),
                    description = stringResource(R.string.settings_system_storage_installer_cache_description),
                    bytes = stats.installerShareBytes,
                    onClear = { viewModel.clearInstallerShareCache(onCleared) }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.Build,
                    accentColor = StorageColors.PatcherWorkspace,
                    title = stringResource(R.string.settings_system_storage_patcher_workspace_title),
                    description = stringResource(R.string.settings_system_storage_patcher_workspace_description),
                    bytes = stats.patcherWorkspaceBytes,
                    onClear = { viewModel.clearPatcherWorkspace(onCleared) }
                )
                SettingsDivider()
                CacheActionRow(
                    icon = Icons.Outlined.HourglassEmpty,
                    accentColor = StorageColors.Temporary,
                    title = stringResource(R.string.settings_system_storage_temporary_title),
                    description = stringResource(R.string.settings_system_storage_temporary_description),
                    bytes = stats.temporaryBytes,
                    onClear = { viewModel.clearTemporary(onCleared) }
                )
            }

            SettingsGroup {
                SettingsItem(
                    onClick = { context.openAppDetailsSettings() },
                    title = stringResource(R.string.settings_system_storage_open_app_info_title),
                    subtitle = stringResource(R.string.settings_system_storage_open_app_info_description),
                    leadingContent = { ThemedIcon(icon = Icons.AutoMirrored.Outlined.Launch) }
                )
            }
        }
    }
}

@Composable
private fun DataRow(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    SettingsItem(
        onClick = onClick,
        title = title,
        subtitle = description,
        leadingContent = { ThemedIcon(icon = icon, tint = accentColor) }
    )
}

@Composable
private fun CacheActionRow(
    icon: ImageVector,
    accentColor: Color,
    title: String,
    description: String,
    bytes: Long,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Defaults.ContentPadding),
        verticalArrangement = Arrangement.spacedBy(Defaults.ContentPadding)
    ) {
        IconTextRow(
            leadingContent = { ThemedIcon(icon = icon, tint = accentColor) },
            title = title,
            description = description
        )
        CardActionRow(
            actions = listOf(
                CardAction(
                    icon = Icons.Outlined.DeleteSweep,
                    label = "${stringResource(R.string.clear)} (${LocalContext.current.formatBytes(bytes)})",
                    onClick = onClear,
                    enabled = bytes > 0L,
                    destructive = true
                )
            )
        )
    }
}

@Composable
private fun StorageStats.toSegments(): List<StorageSegment> {
    val originalLabel = stringResource(R.string.settings_system_original_apks_title)
    val patchedLabel = stringResource(R.string.settings_system_patched_apks_title)
    val bundlesLabel = stringResource(R.string.settings_system_storage_patch_bundles_title)
    val keystoreLabel = stringResource(R.string.settings_system_storage_keystore_title)
    val appDataLabel = stringResource(R.string.settings_system_storage_app_data_title)
    val httpLabel = stringResource(R.string.settings_system_storage_http_cache_title)
    val shareLabel = stringResource(R.string.settings_system_storage_installer_cache_title)
    val patcherLabel = stringResource(R.string.settings_system_storage_patcher_workspace_title)
    val tempLabel = stringResource(R.string.settings_system_storage_temporary_title)
    return listOf(
        StorageSegment("original", originalLabel, originalApksBytes, StorageColors.OriginalApks),
        StorageSegment("patched", patchedLabel, patchedApksBytes, StorageColors.PatchedApks),
        StorageSegment("bundles", bundlesLabel, patchBundlesBytes, StorageColors.PatchBundles),
        StorageSegment("keystore", keystoreLabel, keystoreBytes, StorageColors.Keystore),
        StorageSegment("appdata", appDataLabel, appDataBytes, StorageColors.AppData),
        StorageSegment("http", httpLabel, httpCacheBytes, StorageColors.HttpCache),
        StorageSegment("share", shareLabel, installerShareBytes, StorageColors.InstallerShare),
        StorageSegment("patcher", patcherLabel, patcherWorkspaceBytes, StorageColors.PatcherWorkspace),
        StorageSegment("temp", tempLabel, temporaryBytes, StorageColors.Temporary)
    )
}
