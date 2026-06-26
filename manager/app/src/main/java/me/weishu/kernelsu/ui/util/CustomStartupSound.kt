package me.weishu.kernelsu.ui.util

import android.content.Context
import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

const val CUSTOM_STARTUP_SOUND_URI_KEY = "custom_startup_sound_uri"
const val CUSTOM_STARTUP_SOUND_DURATION_SECONDS_KEY = "custom_startup_sound_duration_seconds"
const val CUSTOM_STARTUP_SOUND_VOLUME_KEY = "custom_startup_sound_volume"
const val CUSTOM_CLICK_SOUND_URI_KEY = "custom_click_sound_uri"
const val CUSTOM_CLICK_SOUND_VOLUME_KEY = "custom_click_sound_volume"
const val CUSTOM_BACKGROUND_MUSIC_URI_KEY = "custom_background_music_uri"
const val CUSTOM_BACKGROUND_MUSIC_VOLUME_KEY = "custom_background_music_volume"
const val DEFAULT_CUSTOM_STARTUP_SOUND_DURATION_SECONDS = 5
const val MIN_CUSTOM_STARTUP_SOUND_DURATION_SECONDS = 1
const val MAX_CUSTOM_STARTUP_SOUND_DURATION_SECONDS = 30
const val DEFAULT_CUSTOM_AUDIO_VOLUME = 1.0f
const val DEFAULT_CUSTOM_BACKGROUND_MUSIC_VOLUME = 0.35f
const val MIN_CUSTOM_AUDIO_VOLUME = 0.0f
const val MAX_CUSTOM_AUDIO_VOLUME = 1.0f

fun sanitizeCustomStartupSoundDurationSeconds(value: Int): Int {
    return value.coerceIn(
        MIN_CUSTOM_STARTUP_SOUND_DURATION_SECONDS,
        MAX_CUSTOM_STARTUP_SOUND_DURATION_SECONDS,
    )
}

fun sanitizeCustomAudioVolume(value: Float): Float {
    return if (value.isFinite()) {
        value.coerceIn(MIN_CUSTOM_AUDIO_VOLUME, MAX_CUSTOM_AUDIO_VOLUME)
    } else {
        DEFAULT_CUSTOM_AUDIO_VOLUME
    }
}

fun sanitizeCustomBackgroundMusicVolume(value: Float): Float {
    return if (value.isFinite()) {
        value.coerceIn(MIN_CUSTOM_AUDIO_VOLUME, MAX_CUSTOM_AUDIO_VOLUME)
    } else {
        DEFAULT_CUSTOM_BACKGROUND_MUSIC_VOLUME
    }
}

fun takePersistableAudioReadPermission(context: Context, uri: Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

fun releasePersistableAudioReadPermission(context: Context, uriString: String?) {
    if (uriString.isNullOrBlank()) return
    runCatching {
        context.contentResolver.releasePersistableUriPermission(
            Uri.parse(uriString),
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }
}

object StartupSoundPlayer {

    private var player: MediaPlayer? = null
    private var source: AssetFileDescriptor? = null
    private var suppressNextAutoPlay = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    fun playConfigured(context: Context) {
        if (suppressNextAutoPlay) {
            suppressNextAutoPlay = false
            return
        }
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriString = prefs.getString(CUSTOM_STARTUP_SOUND_URI_KEY, null)
        val durationSeconds = sanitizeCustomStartupSoundDurationSeconds(
            prefs.getInt(
                CUSTOM_STARTUP_SOUND_DURATION_SECONDS_KEY,
                DEFAULT_CUSTOM_STARTUP_SOUND_DURATION_SECONDS,
            )
        )
        val volume = sanitizeCustomAudioVolume(
            prefs.getFloat(CUSTOM_STARTUP_SOUND_VOLUME_KEY, DEFAULT_CUSTOM_AUDIO_VOLUME)
        )
        play(appContext, uriString, durationSeconds, volume)
    }

    fun suppressNextAutoPlay() {
        suppressNextAutoPlay = true
    }

    fun clearAutoPlaySuppression() {
        suppressNextAutoPlay = false
    }

    fun play(
        context: Context,
        uriString: String?,
        onError: ((Throwable?) -> Unit)? = null,
    ) {
        play(
            context = context,
            uriString = uriString,
            durationSeconds = readConfiguredDurationSeconds(context),
            volume = readConfiguredVolume(context),
            onError = onError,
        )
    }

    fun play(
        context: Context,
        uriString: String?,
        durationSeconds: Int,
        volume: Float = readConfiguredVolume(context),
        onError: ((Throwable?) -> Unit)? = null,
    ) {
        if (uriString.isNullOrBlank()) return
        stop()
        val safeDurationSeconds = sanitizeCustomStartupSoundDurationSeconds(durationSeconds)
        val safeVolume = sanitizeCustomAudioVolume(volume)

        runCatching {
            val appContext = context.applicationContext
            val uri = Uri.parse(uriString)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                setVolume(safeVolume, safeVolume)
                source = runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                }.getOrNull()
                val currentSource = source
                if (currentSource != null) {
                    if (currentSource.length == AssetFileDescriptor.UNKNOWN_LENGTH) {
                        setDataSource(currentSource.fileDescriptor)
                    } else {
                        setDataSource(currentSource.fileDescriptor, currentSource.startOffset, currentSource.length)
                    }
                } else {
                    setDataSource(appContext, uri)
                }
                setOnPreparedListener {
                    runCatching {
                        it.start()
                        scheduleStop(it, safeDurationSeconds)
                    }.onFailure { throwable ->
                        Log.e("StartupSound", "failed to start startup sound", throwable)
                        cleanup(it)
                        notifyError(onError, throwable)
                    }
                }
                setOnCompletionListener { mediaPlayer ->
                    cleanup(mediaPlayer)
                }
                setOnErrorListener { mediaPlayer, what, extra ->
                    Log.e("StartupSound", "failed to play startup sound: what=$what extra=$extra")
                    cleanup(mediaPlayer)
                    notifyError(onError, null)
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            Log.e("StartupSound", "failed to play startup sound", it)
            stop()
            notifyError(onError, it)
        }
    }

    fun stop() {
        clearScheduledStop()
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
            }
            mediaPlayer.release()
        }
        player = null
        runCatching { source?.close() }
        source = null
    }

    private fun cleanup(mediaPlayer: MediaPlayer) {
        if (player === mediaPlayer) {
            player = null
            clearScheduledStop()
            runCatching { source?.close() }
            source = null
        }
        mediaPlayer.release()
    }

    private fun scheduleStop(mediaPlayer: MediaPlayer, durationSeconds: Int) {
        clearScheduledStop()
        val runnable = Runnable {
            if (player === mediaPlayer) {
                stop()
            }
        }
        stopRunnable = runnable
        mainHandler.postDelayed(runnable, durationSeconds * 1000L)
    }

    private fun clearScheduledStop() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopRunnable = null
    }

    private fun readConfiguredDurationSeconds(context: Context): Int {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sanitizeCustomStartupSoundDurationSeconds(
            prefs.getInt(
                CUSTOM_STARTUP_SOUND_DURATION_SECONDS_KEY,
                DEFAULT_CUSTOM_STARTUP_SOUND_DURATION_SECONDS,
            )
        )
    }

    private fun readConfiguredVolume(context: Context): Float {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sanitizeCustomAudioVolume(
            prefs.getFloat(CUSTOM_STARTUP_SOUND_VOLUME_KEY, DEFAULT_CUSTOM_AUDIO_VOLUME)
        )
    }

    private fun notifyError(onError: ((Throwable?) -> Unit)?, throwable: Throwable?) {
        onError ?: return
        mainHandler.post { onError(throwable) }
    }
}

object ClickSoundPlayer {

    private const val MIN_PLAY_INTERVAL_MS = 80L

    private var soundPool: SoundPool? = null
    private var soundId = 0
    private var loadedUriString: String? = null
    private var isLoaded = false
    private var pendingPlay = false
    private var source: AssetFileDescriptor? = null
    private var lastPlayUptime = 0L
    private var volume = DEFAULT_CUSTOM_AUDIO_VOLUME
    private val mainHandler = Handler(Looper.getMainLooper())

    fun playConfigured(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriString = prefs.getString(CUSTOM_CLICK_SOUND_URI_KEY, null)
        val configuredVolume = sanitizeCustomAudioVolume(
            prefs.getFloat(CUSTOM_CLICK_SOUND_VOLUME_KEY, DEFAULT_CUSTOM_AUDIO_VOLUME)
        )
        play(appContext, uriString, configuredVolume)
    }

    fun play(
        context: Context,
        uriString: String?,
        volume: Float = readConfiguredVolume(context),
        onError: ((Throwable?) -> Unit)? = null,
    ) {
        if (uriString.isNullOrBlank()) {
            release()
            return
        }

        this.volume = sanitizeCustomAudioVolume(volume)
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayUptime < MIN_PLAY_INTERVAL_MS) return

        if (loadedUriString != uriString || soundPool == null || soundId == 0) {
            load(context, uriString, playAfterLoad = true, onError = onError)
            return
        }

        if (!isLoaded) {
            pendingPlay = true
            return
        }

        playLoaded(onError)
    }

    fun release() {
        soundPool?.release()
        soundPool = null
        soundId = 0
        loadedUriString = null
        isLoaded = false
        pendingPlay = false
        closeSource()
    }

    private fun load(
        context: Context,
        uriString: String,
        playAfterLoad: Boolean,
        onError: ((Throwable?) -> Unit)?,
    ) {
        release()
        loadedUriString = uriString
        pendingPlay = playAfterLoad

        runCatching {
            val appContext = context.applicationContext
            val uri = Uri.parse(uriString)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val newSoundPool = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(audioAttributes)
                .build()
            source = appContext.contentResolver.openAssetFileDescriptor(uri, "r")
            val currentSource = source ?: error("Unable to open click sound")

            newSoundPool.setOnLoadCompleteListener { pool, loadedSoundId, status ->
                if (pool !== soundPool || loadedSoundId != soundId) return@setOnLoadCompleteListener
                closeSource()
                if (status == 0) {
                    isLoaded = true
                    if (pendingPlay) {
                        pendingPlay = false
                        playLoaded(onError)
                    }
                } else {
                    Log.e("ClickSound", "failed to load click sound: status=$status")
                    release()
                    notifyError(onError, null)
                }
            }

            soundPool = newSoundPool
            soundId = newSoundPool.load(currentSource, 1)
            if (soundId == 0) {
                Log.e("ClickSound", "failed to queue click sound")
                release()
                notifyError(onError, null)
            }
        }.onFailure {
            Log.e("ClickSound", "failed to load click sound", it)
            release()
            notifyError(onError, it)
        }
    }

    private fun playLoaded(onError: ((Throwable?) -> Unit)?) {
        val currentPool = soundPool ?: return
        val currentSoundId = soundId.takeIf { it != 0 } ?: return
        val safeVolume = sanitizeCustomAudioVolume(volume)
        runCatching {
            val streamId = currentPool.play(currentSoundId, safeVolume, safeVolume, 1, 0, 1.0f)
            if (streamId == 0) {
                notifyError(onError, null)
            } else {
                lastPlayUptime = SystemClock.uptimeMillis()
            }
        }.onFailure {
            Log.e("ClickSound", "failed to play click sound", it)
            notifyError(onError, it)
        }
    }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    private fun notifyError(onError: ((Throwable?) -> Unit)?, throwable: Throwable?) {
        onError ?: return
        mainHandler.post { onError(throwable) }
    }

    private fun readConfiguredVolume(context: Context): Float {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sanitizeCustomAudioVolume(
            prefs.getFloat(CUSTOM_CLICK_SOUND_VOLUME_KEY, DEFAULT_CUSTOM_AUDIO_VOLUME)
        )
    }
}

object BackgroundMusicPlayer {

    private var player: MediaPlayer? = null
    private var source: AssetFileDescriptor? = null
    private var loadedUriString: String? = null
    private var volume = DEFAULT_CUSTOM_BACKGROUND_MUSIC_VOLUME
    private val mainHandler = Handler(Looper.getMainLooper())

    fun playConfigured(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriString = prefs.getString(CUSTOM_BACKGROUND_MUSIC_URI_KEY, null)
        val configuredVolume = sanitizeCustomBackgroundMusicVolume(
            prefs.getFloat(CUSTOM_BACKGROUND_MUSIC_VOLUME_KEY, DEFAULT_CUSTOM_BACKGROUND_MUSIC_VOLUME)
        )
        play(appContext, uriString, configuredVolume)
    }

    fun play(
        context: Context,
        uriString: String?,
        volume: Float = readConfiguredVolume(context),
        loop: Boolean = true,
        onError: ((Throwable?) -> Unit)? = null,
    ) {
        if (uriString.isNullOrBlank()) {
            stop()
            return
        }

        val safeVolume = sanitizeCustomBackgroundMusicVolume(volume)
        if (loadedUriString == uriString && player != null) {
            this.volume = safeVolume
            player?.setVolume(safeVolume, safeVolume)
            runCatching {
                if (player?.isPlaying == false) {
                    player?.start()
                }
            }.onFailure {
                Log.e("BackgroundMusic", "failed to resume background music", it)
                stop()
                notifyError(onError, it)
            }
            return
        }

        stop()
        this.volume = safeVolume
        loadedUriString = uriString

        runCatching {
            val appContext = context.applicationContext
            val uri = Uri.parse(uriString)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            player = MediaPlayer().apply {
                setAudioAttributes(audioAttributes)
                isLooping = loop
                setVolume(safeVolume, safeVolume)
                source = runCatching {
                    appContext.contentResolver.openAssetFileDescriptor(uri, "r")
                }.getOrNull()
                val currentSource = source
                if (currentSource != null) {
                    if (currentSource.length == AssetFileDescriptor.UNKNOWN_LENGTH) {
                        setDataSource(currentSource.fileDescriptor)
                    } else {
                        setDataSource(currentSource.fileDescriptor, currentSource.startOffset, currentSource.length)
                    }
                } else {
                    setDataSource(appContext, uri)
                }
                setOnPreparedListener {
                    runCatching { it.start() }
                        .onFailure { throwable ->
                            Log.e("BackgroundMusic", "failed to start background music", throwable)
                            cleanup(it)
                            notifyError(onError, throwable)
                        }
                }
                setOnCompletionListener { mediaPlayer ->
                    cleanup(mediaPlayer)
                }
                setOnErrorListener { mediaPlayer, what, extra ->
                    Log.e("BackgroundMusic", "failed to play background music: what=$what extra=$extra")
                    cleanup(mediaPlayer)
                    notifyError(onError, null)
                    true
                }
                prepareAsync()
            }
        }.onFailure {
            Log.e("BackgroundMusic", "failed to play background music", it)
            stop()
            notifyError(onError, it)
        }
    }

    fun updateVolume(volume: Float) {
        val safeVolume = sanitizeCustomBackgroundMusicVolume(volume)
        this.volume = safeVolume
        player?.setVolume(safeVolume, safeVolume)
    }

    fun stop() {
        player?.let { mediaPlayer ->
            runCatching {
                if (mediaPlayer.isPlaying) {
                    mediaPlayer.stop()
                }
            }
            mediaPlayer.release()
        }
        player = null
        loadedUriString = null
        runCatching { source?.close() }
        source = null
    }

    private fun cleanup(mediaPlayer: MediaPlayer) {
        if (player === mediaPlayer) {
            player = null
            loadedUriString = null
            runCatching { source?.close() }
            source = null
        }
        mediaPlayer.release()
    }

    private fun readConfiguredVolume(context: Context): Float {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return sanitizeCustomBackgroundMusicVolume(
            prefs.getFloat(CUSTOM_BACKGROUND_MUSIC_VOLUME_KEY, DEFAULT_CUSTOM_BACKGROUND_MUSIC_VOLUME)
        )
    }

    private fun notifyError(onError: ((Throwable?) -> Unit)?, throwable: Throwable?) {
        onError ?: return
        mainHandler.post { onError(throwable) }
    }
}
