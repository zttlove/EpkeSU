package me.weishu.kernelsu.ui.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import me.weishu.kernelsu.ui.util.AppIconCache

@Composable
fun AboutAppIcon(
    size: Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val applicationInfo = context.applicationInfo
    val targetSizePx = with(LocalDensity.current) { size.roundToPx() }
    val iconKey = remember(applicationInfo.packageName, applicationInfo.uid, applicationInfo.sourceDir, targetSizePx) {
        "${applicationInfo.packageName}:${applicationInfo.uid}:${applicationInfo.sourceDir}:$targetSizePx"
    }
    var bitmap by remember(iconKey) {
        mutableStateOf(AppIconCache.getCached(applicationInfo, targetSizePx))
    }

    LaunchedEffect(iconKey) {
        if (bitmap == null) {
            bitmap = AppIconCache.loadIcon(context, applicationInfo, targetSizePx)
        }
    }

    Box(modifier = modifier) {
        val icon = bitmap ?: return@Box
        val imageBitmap = remember(icon) { icon.asImageBitmap() }
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
