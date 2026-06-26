package me.weishu.kernelsu.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import me.weishu.kernelsu.ui.util.CUSTOM_NAVIGATION_ICON_MAX_SIDE
import me.weishu.kernelsu.ui.util.CustomNavigationIconState

@Composable
fun CustomNavigationIconImage(
    state: CustomNavigationIconState,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    fallback: @Composable () -> Unit,
) {
    val imageBitmap = rememberCustomImageBitmap(
        uriString = state.uriString,
        maxSide = CUSTOM_NAVIGATION_ICON_MAX_SIDE,
        crop = state.crop,
    )

    if (imageBitmap != null) {
        Image(
            modifier = modifier
                .clip(RoundedCornerShape(7.dp)),
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            alpha = alpha.coerceIn(0f, 1f),
        )
    } else {
        fallback()
    }
}
