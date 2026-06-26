package me.weishu.kernelsu.ui.webui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import me.weishu.kernelsu.ui.InterfaceStyle
import me.weishu.kernelsu.ui.LocalInterfaceStyle
import me.weishu.kernelsu.ui.LocalUiMode
import me.weishu.kernelsu.ui.UiMode
import me.weishu.kernelsu.ui.component.CustomWallpaperRoot
import me.weishu.kernelsu.ui.theme.ColorMode
import me.weishu.kernelsu.ui.theme.KernelSUTheme
import me.weishu.kernelsu.ui.theme.LocalColorMode
import me.weishu.kernelsu.ui.theme.THEME_SYNC_STRATEGY_KEY
import me.weishu.kernelsu.ui.theme.ThemeController
import me.weishu.kernelsu.ui.theme.ThemePreferenceKeys
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_CROP_BOTTOM_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_CROP_LEFT_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_CROP_RIGHT_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_CROP_TOP_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_OPACITY_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_PASSTHROUGH_ENABLED_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_PASSTHROUGH_OPACITY_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_WALLPAPER_URI_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_VIDEO_BACKGROUND_DURATION_SECONDS_KEY
import me.weishu.kernelsu.ui.util.CUSTOM_VIDEO_BACKGROUND_URI_KEY
import me.weishu.kernelsu.ui.util.CustomWallpaperCrop
import me.weishu.kernelsu.ui.util.DEFAULT_CUSTOM_VIDEO_BACKGROUND_DURATION_SECONDS
import me.weishu.kernelsu.ui.util.DEFAULT_CUSTOM_WALLPAPER_CROP
import me.weishu.kernelsu.ui.util.DEFAULT_CUSTOM_WALLPAPER_OPACITY
import me.weishu.kernelsu.ui.util.DEFAULT_CUSTOM_WALLPAPER_PASSTHROUGH_OPACITY
import me.weishu.kernelsu.ui.util.sanitizeCustomVideoBackgroundDurationSeconds
import me.weishu.kernelsu.ui.util.sanitizeCustomWallpaperCrop
import me.weishu.kernelsu.ui.util.sanitizeCustomWallpaperOpacity
import me.weishu.kernelsu.ui.util.sanitizeCustomWallpaperPassthroughOpacity
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator

@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity() {
    private val intentVersion = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        super.onCreate(savedInstanceState)
        val hostActivity = this

        setContent {
            val context = LocalContext.current
            val prefs = context.getSharedPreferences("settings", MODE_PRIVATE)
            var appSettings by remember { mutableStateOf(ThemeController.getAppSettings(context)) }
            var uiModeValue by remember { mutableStateOf(prefs.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE) }
            var wallpaperState by remember { mutableStateOf(readWebUiWallpaperState(prefs)) }
            val uiMode = remember(uiModeValue) {
                UiMode.fromValue(uiModeValue)
            }
            val isLiquidGlassInterface = uiModeValue == InterfaceStyle.LiquidGlass.value
            val localColorMode = if (isLiquidGlassInterface) ColorMode.LIGHT.value else appSettings.colorMode.value

            DisposableEffect(prefs) {
                val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key in themePreferenceKeys) {
                        appSettings = ThemeController.getAppSettings(context)
                    }
                    if (key == "ui_mode") {
                        uiModeValue = prefs.getString("ui_mode", UiMode.DEFAULT_VALUE) ?: UiMode.DEFAULT_VALUE
                    }
                    if (key in wallpaperPreferenceKeys) {
                        wallpaperState = readWebUiWallpaperState(prefs)
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            CompositionLocalProvider(
                LocalUiMode provides uiMode,
                LocalInterfaceStyle provides uiModeValue,
                LocalColorMode provides localColorMode,
            ) {
                KernelSUTheme(appSettings = appSettings, uiMode = uiMode) {
                    CustomWallpaperRoot(
                        uriString = wallpaperState.uriString,
                        videoUriString = wallpaperState.videoUriString,
                        videoDurationSeconds = wallpaperState.videoDurationSeconds,
                        opacity = wallpaperState.opacity,
                        crop = wallpaperState.crop,
                        passthroughEnabled = wallpaperState.passthroughEnabled,
                        passthroughOpacity = wallpaperState.passthroughOpacity,
                    ) {
                        MainContent(
                            activity = hostActivity,
                            intentVersion = intentVersion,
                            onFinish = hostActivity::finish,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentVersion.update { it + 1 }
    }
}

private data class WebUiWallpaperState(
    val uriString: String?,
    val videoUriString: String?,
    val videoDurationSeconds: Int,
    val opacity: Float,
    val crop: CustomWallpaperCrop,
    val passthroughEnabled: Boolean,
    val passthroughOpacity: Float,
)

private fun readWebUiWallpaperState(prefs: SharedPreferences): WebUiWallpaperState {
    return WebUiWallpaperState(
        uriString = prefs.getString(CUSTOM_WALLPAPER_URI_KEY, null),
        videoUriString = prefs.getString(CUSTOM_VIDEO_BACKGROUND_URI_KEY, null),
        videoDurationSeconds = sanitizeCustomVideoBackgroundDurationSeconds(
            prefs.getInt(
                CUSTOM_VIDEO_BACKGROUND_DURATION_SECONDS_KEY,
                DEFAULT_CUSTOM_VIDEO_BACKGROUND_DURATION_SECONDS,
            )
        ),
        opacity = sanitizeCustomWallpaperOpacity(
            prefs.getFloat(CUSTOM_WALLPAPER_OPACITY_KEY, DEFAULT_CUSTOM_WALLPAPER_OPACITY)
        ),
        crop = sanitizeCustomWallpaperCrop(
            CustomWallpaperCrop(
                left = prefs.getFloat(CUSTOM_WALLPAPER_CROP_LEFT_KEY, DEFAULT_CUSTOM_WALLPAPER_CROP.left),
                top = prefs.getFloat(CUSTOM_WALLPAPER_CROP_TOP_KEY, DEFAULT_CUSTOM_WALLPAPER_CROP.top),
                right = prefs.getFloat(CUSTOM_WALLPAPER_CROP_RIGHT_KEY, DEFAULT_CUSTOM_WALLPAPER_CROP.right),
                bottom = prefs.getFloat(CUSTOM_WALLPAPER_CROP_BOTTOM_KEY, DEFAULT_CUSTOM_WALLPAPER_CROP.bottom),
            )
        ),
        passthroughEnabled = prefs.getBoolean(CUSTOM_WALLPAPER_PASSTHROUGH_ENABLED_KEY, false),
        passthroughOpacity = sanitizeCustomWallpaperPassthroughOpacity(
            prefs.getFloat(
                CUSTOM_WALLPAPER_PASSTHROUGH_OPACITY_KEY,
                DEFAULT_CUSTOM_WALLPAPER_PASSTHROUGH_OPACITY
            )
        ),
    )
}

private val themePreferenceKeys = buildSet {
    add("ui_mode")
    add(THEME_SYNC_STRATEGY_KEY)
    addAll(ThemePreferenceKeys)
    InterfaceStyle.entries.forEach { style ->
        ThemePreferenceKeys.forEach { key ->
            add("${key}_${style.value}")
        }
    }
}

private val wallpaperPreferenceKeys = setOf(
    CUSTOM_WALLPAPER_URI_KEY,
    CUSTOM_WALLPAPER_OPACITY_KEY,
    CUSTOM_WALLPAPER_CROP_LEFT_KEY,
    CUSTOM_WALLPAPER_CROP_TOP_KEY,
    CUSTOM_WALLPAPER_CROP_RIGHT_KEY,
    CUSTOM_WALLPAPER_CROP_BOTTOM_KEY,
    CUSTOM_WALLPAPER_PASSTHROUGH_ENABLED_KEY,
    CUSTOM_WALLPAPER_PASSTHROUGH_OPACITY_KEY,
    CUSTOM_VIDEO_BACKGROUND_URI_KEY,
    CUSTOM_VIDEO_BACKGROUND_DURATION_SECONDS_KEY,
)

@Composable
private fun MainContent(
    activity: ComponentActivity,
    intentVersion: StateFlow<Int>,
    onFinish: () -> Unit,
) {
    val intentTick by intentVersion.collectAsStateWithLifecycle()
    val moduleId = remember(intentTick) { activity.intent.moduleId() }
    val webUIState = remember(moduleId) { WebUIState() }

    LaunchedEffect(moduleId, webUIState) {
        if (moduleId == null) {
            onFinish()
            return@LaunchedEffect
        }
        prepareWebView(activity, moduleId, webUIState)
    }

    DisposableEffect(webUIState) {
        onDispose { webUIState.dispose(activity) }
    }

    when (val event = webUIState.uiEvent) {
        is WebUIEvent.Error -> {
            LaunchedEffect(event) {
                Toast.makeText(activity, event.message, Toast.LENGTH_SHORT).show()
                onFinish()
            }
        }

        is WebUIEvent.Close -> {
            LaunchedEffect(event) { onFinish() }
        }

        else -> {}
    }
    val isLoading = webUIState.uiEvent is WebUIEvent.Loading

    Crossfade(targetState = isLoading, animationSpec = tween(300)) { loading ->
        if (loading) {
            LoadingContent()
        } else {
            WebUIScreen(webUIState = webUIState)
        }
    }
}

private fun Intent.moduleId(): String? {
    getStringExtra("id")?.takeIf { it.isNotBlank() }?.let { return it }

    val intentData = this.data ?: return null
    if (!intentData.scheme.equals("kernelsu", ignoreCase = true) || !intentData.host.equals("webui", ignoreCase = true)) {
        return null
    }

    return intentData.lastPathSegment?.takeIf { it.isNotBlank() }
}

@Composable
private fun LoadingContent() {
    when (LocalUiMode.current) {
        UiMode.Miuix -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                InfiniteProgressIndicator()
            }
        }

        UiMode.Material -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.LoadingIndicator()
            }
        }
    }
}
