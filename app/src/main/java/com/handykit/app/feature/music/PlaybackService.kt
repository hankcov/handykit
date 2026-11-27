package com.handykit.app.feature.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放服务。Manifest 声明由调用方统一添加：
 * FOREGROUND_SERVICE + FOREGROUND_SERVICE_MEDIA_PLAYBACK + WAKE_LOCK + POST_NOTIFICATIONS。
 * Media3 在播放开始时通过默认通知提供器把自己提升为前台服务；通知权限需用户运行时授予。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var receiverRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                PlayerController.pause()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PlayerController.init(applicationContext)
        val session = PlayerController.session(applicationContext)
        mediaSession = session
        addSession(session)

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(noisyReceiver, filter)
        }
        receiverRegistered = true
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(noisyReceiver)
            receiverRegistered = false
        }
        // 不 release 单例 player/session：进程内复用，Service 重建时重新 setMediaSession。
        super.onDestroy()
    }
}
