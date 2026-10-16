/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 */

package app.morphe.manager.ui.screen.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.morphe.manager.R
import app.morphe.manager.ui.screen.shared.*
import app.morphe.manager.util.formatBytesForReport
import app.morphe.manager.util.withVersionPrefix

/**
 * Dialog displaying active APK/XAPK download progress from APKMirror.
 */
@Composable
internal fun ApkDownloadProgressDialog(
    appName: String,
    status: String,
    progress: Float?,
    speedBytesPerSec: Long,
    bytesDownloaded: Long,
    totalBytes: Long?,
    onCancel: () -> Unit,
    version: String? = null,
    packageName: String? = null
) {
    val appColor = rememberAppColor(packageName)
    val accentColor = appColor ?: MaterialTheme.colorScheme.primary
    val secondaryColor = dialogSecondaryTextColor()

    AppDialog(
        onDismissRequest = onCancel,
        accentColor = accentColor,
        title = stringResource(R.string.home_download_inbuilt_title, appName),
        description = listOfNotNull(
            packageName,
            version?.withVersionPrefix()
        ).joinToString(" · "),
        footer = {
            AppDialogButton(
                text = stringResource(android.R.string.cancel),
                onClick = onCancel,
                icon = Icons.Outlined.Close,
                modifier = Modifier.fillMaxWidth()
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Defaults.ContentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(48.dp)
            )

            // Progress Bar
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = accentColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = accentColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Progress metrics (downloaded/total and speed)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val sizeText = if (totalBytes != null && totalBytes > 0) {
                        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
                    } else if (bytesDownloaded > 0) {
                        formatBytes(bytesDownloaded)
                    } else ""

                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelMedium,
                        color = secondaryColor
                    )

                    if (speedBytesPerSec > 0) {
                        Text(
                            text = "${formatBytes(speedBytesPerSec)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = accentColor
                        )
                    }
                }
            }

            // Current status message
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = dialogTextColor(),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val mb = bytes / (1024f * 1024f)
    return if (mb >= 1.0f) {
        "%.1f MB".format(mb)
    } else {
        val kb = bytes / 1024f
        "%.0f KB".format(kb)
    }
}
