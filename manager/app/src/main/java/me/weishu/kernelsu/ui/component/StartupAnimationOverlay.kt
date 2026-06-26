package me.weishu.kernelsu.ui.component

import android.graphics.Matrix
import android.graphics.ImageDecoder
import android.graphics.SurfaceTexture
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.weishu.kernelsu.ui.util.isCustomStartupAnimationGif
import me.weishu.kernelsu.ui.util.isCustomStartupAnimationVideo

private const val MAX_STARTUP_ANIMATION_DURATION_MS = 5_000L
private const val STATIC_STARTUP_IMAGE_DURATION_MS = 1_500L

@Composable
fun StartupAnimationOverlay(
    uriString: String?,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
    onError: () -> Unit = {},
) {
    if (uriString.isNullOrBlank()) {
        LaunchedEffect(Unit) {
            onFinished()
        }
        return
    }

    val context = LocalContext.current
    val uri = remember(uriString) { uriString.toUri() }
    val isGif = remember(uriString) { isCustomStartupAnimationGif(context, uri) }
    val isVideo = remember(uriString, isGif) { !isGif && isCustomStartupAnimationVideo(context, uri) }
    var isVideoRendering by remember(uriString) { mutableStateOf(false) }
    val currentOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(uriString) {
        delay(MAX_STARTUP_ANIMATION_DURATION_MS)
        currentOnFinished()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            StartupAnimationVideo(
                uri = uri,
                visible = isVideoRendering,
                onFirstFrame = { isVideoRendering = true },
                onFinished = onFinished,
                onError = onError,
            )
        } else {
            StartupAnimationImage(
                uri = uri,
                onFinished = onFinished,
                onError = onError,
            )
        }
    }
}

@Composable
private fun StartupAnimationVideo(
    uri: Uri,
    visible: Boolean,
    onFirstFrame: () -> Unit,
    onFinished: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnFirstFrame by rememberUpdatedState(onFirstFrame)
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnError by rememberUpdatedState(onError)
    var mediaPlayer by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var surface by remember(uri) { mutableStateOf<Surface?>(null) }

    key(uri) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (visible) 1f else 0f },
            factory = { viewContext ->
                TextureView(viewContext).apply textureView@{
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            val nextSurface = Surface(surfaceTexture)
                            surface = nextSurface
                            runCatching {
                                MediaPlayer().apply {
                                    setSurface(nextSurface)
                                    setDataSource(context.applicationContext, uri)
                                    setOnPreparedListener { player ->
                                        player.isLooping = false
                                        applyFitCenterTransform(this@textureView, player.videoWidth, player.videoHeight)
                                        player.start()
                                        this@textureView.postDelayed({ currentOnFirstFrame() }, 250L)
                                    }
                                    setOnInfoListener { _, what, _ ->
                                        if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                                            currentOnFirstFrame()
                                        }
                                        false
                                    }
                                    setOnVideoSizeChangedListener { _, videoWidth, videoHeight ->
                                        applyFitCenterTransform(this@textureView, videoWidth, videoHeight)
                                    }
                                    setOnCompletionListener {
                                        currentOnFinished()
                                    }
                                    setOnErrorListener { _, _, _ ->
                                        currentOnError()
                                        currentOnFinished()
                                        true
                                    }
                                    prepareAsync()
                                }
                            }.onSuccess {
                                mediaPlayer = it
                            }.onFailure {
                                currentOnError()
                                currentOnFinished()
                            }
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            mediaPlayer?.let { player ->
                                applyFitCenterTransform(this@textureView, player.videoWidth, player.videoHeight)
                            }
                        }

                        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                            runCatching { mediaPlayer?.release() }
                            mediaPlayer = null
                            runCatching { surface?.release() }
                            surface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                    }
                }
            },
        )
    }

    DisposableEffect(uri) {
        onDispose {
            runCatching { mediaPlayer?.release() }
            mediaPlayer = null
            runCatching { surface?.release() }
            surface = null
        }
    }
}

private fun applyFitCenterTransform(textureView: TextureView, videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || textureView.width <= 0 || textureView.height <= 0) return

    val viewWidth = textureView.width.toFloat()
    val viewHeight = textureView.height.toFloat()
    val viewAspect = viewWidth / viewHeight
    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val (scaleX, scaleY) = if (videoAspect > viewAspect) {
        1f to viewAspect / videoAspect
    } else {
        videoAspect / viewAspect to 1f
    }

    textureView.setTransform(
        Matrix().apply {
            setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        }
    )
}

@Composable
private fun StartupAnimationImage(
    uri: Uri,
    onFinished: () -> Unit,
    onError: () -> Unit,
) {
    val context = LocalContext.current
    val currentOnFinished by rememberUpdatedState(onFinished)
    val currentOnError by rememberUpdatedState(onError)
    val drawableResult by produceState<Result<Drawable>?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeDrawable(source)
            }
        }
    }

    val result = drawableResult ?: return
    val drawable = result.getOrNull()
    if (drawable == null) {
        LaunchedEffect(uri) {
            currentOnError()
            currentOnFinished()
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            ImageView(context).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageDrawable(drawable)
            }
        },
        update = { imageView ->
            if (imageView.drawable !== drawable) {
                imageView.setImageDrawable(drawable)
            }
        },
    )

    if (drawable is AnimatedImageDrawable) {
        DisposableEffect(drawable) {
            val callback = object : Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: Drawable?) {
                    currentOnFinished()
                }
            }
            drawable.repeatCount = 0
            drawable.registerAnimationCallback(callback)
            drawable.start()
            onDispose {
                runCatching { drawable.unregisterAnimationCallback(callback) }
                runCatching { drawable.stop() }
            }
        }
    } else {
        LaunchedEffect(drawable) {
            delay(STATIC_STARTUP_IMAGE_DURATION_MS)
            currentOnFinished()
        }
    }
}
