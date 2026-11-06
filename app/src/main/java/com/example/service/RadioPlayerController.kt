package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.model.PlaybackState
import com.example.model.PlaybackStatus
import com.example.model.RadioStation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

object RadioPlayerController {

    private var mediaPlayer: MediaPlayer? = null
    private var applicationContext: Context? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var sleepTimerJob: Job? = null
    private var equalizerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var playlist: List<RadioStation> = emptyList()

    fun init(context: Context) {
        if (applicationContext == null) {
            applicationContext = context.applicationContext
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        }
    }

    fun updatePlaylist(stations: List<RadioStation>) {
        playlist = stations
    }

    private fun getHttpHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 RadyoApp/1.0"
        )
    }

    fun play(station: RadioStation) {
        val context = applicationContext ?: return
        startPlaybackService(context)

        // Release old player safely
        releasePlayer()

        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.BUFFERING,
            currentStation = station,
            errorMessage = null
        )

        requestAudioFocus()

        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(context, Uri.parse(station.streamUrl), getHttpHeaders())

            mp.setOnPreparedListener { player ->
                if (mediaPlayer == player) {
                    try {
                        player.start()
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.PLAYING,
                            errorMessage = null
                        )
                        startEqualizerSimulation()
                        updateNotification()
                    } catch (e: Exception) {
                        handlePlaybackFailure(station, "Yayın başlatılamadı: ${e.message}")
                    }
                }
            }

            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.BUFFERING)
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
                }
                true
            }

            mp.setOnErrorListener { player, what, extra ->
                android.util.Log.e("RadioPlayerController", "MediaPlayer error: what=$what extra=$extra")
                if (mediaPlayer == player) {
                    if (station.backupStreamUrl.isNotBlank() && station.backupStreamUrl != station.streamUrl) {
                        playFallback(station)
                    } else {
                        handlePlaybackFailure(station, "Yayın akışı geçici olarak bağlanamadı.")
                    }
                }
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            android.util.Log.e("RadioPlayerController", "play() exception: ${e.message}")
            if (station.backupStreamUrl.isNotBlank() && station.backupStreamUrl != station.streamUrl) {
                playFallback(station)
            } else {
                handlePlaybackFailure(station, "Bağlantı hatası: ${e.localizedMessage ?: "Bilinmeyen hata"}")
            }
        }
    }

    private fun playFallback(station: RadioStation) {
        val context = applicationContext ?: return
        try {
            releasePlayer()
            val mp = MediaPlayer()
            mediaPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            mp.setDataSource(context, Uri.parse(station.backupStreamUrl), getHttpHeaders())

            mp.setOnPreparedListener { player ->
                if (mediaPlayer == player) {
                    try {
                        player.start()
                        _playbackState.value = _playbackState.value.copy(
                            status = PlaybackStatus.PLAYING,
                            errorMessage = null
                        )
                        startEqualizerSimulation()
                        updateNotification()
                    } catch (e: Exception) {
                        handlePlaybackFailure(station, "Yedek yayın başlatılamadı")
                    }
                }
            }

            mp.setOnErrorListener { player, what, extra ->
                android.util.Log.e("RadioPlayerController", "Fallback error: what=$what extra=$extra")
                if (mediaPlayer == player) {
                    handlePlaybackFailure(station, "Yayın akışına ulaşılamadı. Farklı bir istasyon seçin.")
                }
                true
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            handlePlaybackFailure(station, "Yayın açılamadı.")
        }
    }

    private fun handlePlaybackFailure(station: RadioStation, msg: String) {
        _playbackState.value = _playbackState.value.copy(
            status = PlaybackStatus.ERROR,
            errorMessage = msg
        )
        stopEqualizerSimulation()
        updateNotification()
    }

    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
            }
        }
        _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PAUSED)
        stopEqualizerSimulation()
        updateNotification()
    }

    fun resume() {
        val st = _playbackState.value.currentStation
        if (mediaPlayer != null) {
            mediaPlayer?.start()
            _playbackState.value = _playbackState.value.copy(status = PlaybackStatus.PLAYING)
            startEqualizerSimulation()
            updateNotification()
        } else if (st != null) {
            play(st)
        }
    }

    fun togglePlayPause() {
        when (_playbackState.value.status) {
            PlaybackStatus.PLAYING -> pause()
            PlaybackStatus.PAUSED -> resume()
            PlaybackStatus.ERROR, PlaybackStatus.IDLE -> {
                _playbackState.value.currentStation?.let { play(it) }
            }
            PlaybackStatus.BUFFERING -> pause()
        }
    }

    fun playNext() {
        val current = _playbackState.value.currentStation ?: return
        if (playlist.isEmpty()) return
        val currentIndex = playlist.indexOfFirst { it.id == current.id }
        val nextIndex = if (currentIndex != -1 && currentIndex + 1 < playlist.size) {
            currentIndex + 1
        } else {
            0
        }
        play(playlist[nextIndex])
    }

    fun playPrevious() {
        val current = _playbackState.value.currentStation ?: return
        if (playlist.isEmpty()) return
        val currentIndex = playlist.indexOfFirst { it.id == current.id }
        val prevIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            playlist.size - 1
        }
        play(playlist[prevIndex])
    }

    fun setVolume(vol: Float) {
        val clamped = vol.coerceIn(0f, 1f)
        mediaPlayer?.setVolume(clamped, clamped)
        _playbackState.value = _playbackState.value.copy(volume = clamped)
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMinutesLeft = minutes,
            sleepTimerTotalMinutes = minutes
        )

        sleepTimerJob = scope.launch {
            var remaining = minutes
            while (remaining > 0 && isActive) {
                delay(60_000L) // 1 minute
                remaining -= 1
                _playbackState.value = _playbackState.value.copy(
                    sleepTimerMinutesLeft = remaining
                )
            }
            if (isActive && remaining <= 0) {
                // Smooth fade out before pausing
                smoothFadeOutAndPause()
            }
        }
    }

    private suspend fun smoothFadeOutAndPause() {
        val currentVol = _playbackState.value.volume
        for (i in 10 downTo 1) {
            setVolume(currentVol * (i / 10f))
            delay(200L)
        }
        pause()
        setVolume(currentVol) // restore original volume setting for next time
        cancelSleepTimer()
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _playbackState.value = _playbackState.value.copy(
            sleepTimerMinutesLeft = null,
            sleepTimerTotalMinutes = null
        )
    }

    private fun startEqualizerSimulation() {
        equalizerJob?.cancel()
        equalizerJob = scope.launch {
            var phase = 0f
            while (isActive && _playbackState.value.status == PlaybackStatus.PLAYING) {
                phase += 0.25f
                val bands = List(16) { i ->
                    val wave1 = kotlin.math.sin(phase + i * 0.4f) * 0.4f + 0.5f
                    val wave2 = kotlin.math.cos(phase * 1.5f + i * 0.8f) * 0.3f
                    val randomJitter = Random.nextFloat() * 0.2f
                    (wave1 + wave2 + randomJitter).coerceIn(0.12f, 1.0f).toFloat()
                }
                _playbackState.value = _playbackState.value.copy(audioFrequencies = bands)
                delay(65L) // ~15 fps smooth equalizer frame rate
            }
        }
    }

    private fun stopEqualizerSimulation() {
        equalizerJob?.cancel()
        equalizerJob = null
        _playbackState.value = _playbackState.value.copy(
            audioFrequencies = List(16) { 0.08f }
        )
    }

    private fun requestAudioFocus() {
        val am = audioManager ?: return
        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                AudioManager.AUDIOFOCUS_GAIN -> resume()
            }
        }
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun startPlaybackService(context: Context) {
        val intent = Intent(context, RadioPlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun updateNotification() {
        val ctx = applicationContext ?: return
        val serviceIntent = Intent(ctx, RadioPlaybackService::class.java).apply {
            action = RadioPlaybackService.ACTION_UPDATE_NOTIFICATION
        }
        ctx.startService(serviceIntent)
    }

    fun releasePlayer() {
        try {
            val mp = mediaPlayer
            mediaPlayer = null
            if (mp != null) {
                mp.setOnPreparedListener(null)
                mp.setOnErrorListener(null)
                mp.setOnInfoListener(null)
                mp.setOnCompletionListener(null)
                try {
                    if (mp.isPlaying) {
                        mp.stop()
                    }
                } catch (_: Exception) {}
                try {
                    mp.reset()
                } catch (_: Exception) {}
                try {
                    mp.release()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        stopEqualizerSimulation()
    }
}

class RadioPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "radyo_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.example.service.PLAY"
        const val ACTION_PAUSE = "com.example.service.PAUSE"
        const val ACTION_NEXT = "com.example.service.NEXT"
        const val ACTION_PREV = "com.example.service.PREV"
        const val ACTION_UPDATE_NOTIFICATION = "com.example.service.UPDATE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> RadioPlayerController.resume()
            ACTION_PAUSE -> RadioPlayerController.pause()
            ACTION_NEXT -> RadioPlayerController.playNext()
            ACTION_PREV -> RadioPlayerController.playPrevious()
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val state = RadioPlayerController.playbackState.value
        val station = state.currentStation

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val isPlaying = state.status == PlaybackStatus.PLAYING
        val playPauseAction = if (isPlaying) {
            val pauseIntent = PendingIntent.getService(
                this, 1, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Duraklat",
                pauseIntent
            )
            } else {
            val playIntent = PendingIntent.getService(
                this, 2, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_PLAY },
                PendingIntent.FLAG_IMMUTABLE
            )
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Oynat",
                playIntent
            )
        }

        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_NEXT },
            PendingIntent.FLAG_IMMUTABLE
        )
        val prevIntent = PendingIntent.getService(
            this, 4, Intent(this, RadioPlaybackService::class.java).apply { action = ACTION_PREV },
            PendingIntent.FLAG_IMMUTABLE
        )

        val stationTitle = station?.name ?: "Radyo FM"
        val subtitle = when (state.status) {
            PlaybackStatus.BUFFERING -> "Bağlanıyor..."
            PlaybackStatus.PLAYING -> "${station?.frequencyOrCity ?: "Canlı"} • Yayında"
            PlaybackStatus.PAUSED -> "Duraklatıldı"
            PlaybackStatus.ERROR -> "Hata"
            PlaybackStatus.IDLE -> "Hazır"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(stationTitle)
            .setContentText(subtitle)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Önceki", prevIntent)
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Sonraki", nextIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radyo Canlı Çalma Bildirimi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Arka planda çalan radyo istasyonu kontrolleri"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
            
