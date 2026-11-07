package com.sora.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PlayerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val scanner = Executors.newSingleThreadExecutor()
    private val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private var songs = emptyList<Library.Song>()
    private val queue = ArrayDeque<String>()
    private var selected: Library.Song? = null
    private var player: MediaPlayer? = null
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focus: AudioFocusRequest
    private lateinit var prefs: SharedPreferences
    private lateinit var connectivity: ConnectivityManager
    private var wifi: Network? = null
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private lateinit var wake: PowerManager.WakeLock
    private var ready = false
    private var online = false
    private var remote = false
    private var preparing = false
    private var playing = false
    private var destroyed = false
    private var foreground = false
    private var scanning = false
    private var scanGeneration = 0
    private var retrySeconds = 1
    private var volume = 100
    private var ack = ""
    private var preparingAck: String? = ""
    private var error = ""

    private val reconnect = Runnable { connect() }
    private val tick = object : Runnable {
        override fun run() {
            if (online && playing) publish()
            if (playing) main.postDelayed(this, 5000)
        }
    }
    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { useWifi(network) }
        }

        override fun onLost(network: Network) {
            main.post { if (network == wifi) useWifi(findWifi()) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        volume = prefs.getInt("volume", 100)
        connectivity = checkNotNull(getSystemService(ConnectivityManager::class.java))
        audioManager = checkNotNull(getSystemService(AudioManager::class.java))
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(audio)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0 && (playing || preparing)) pause("")
            }, main).build()
        wake = checkNotNull(getSystemService(PowerManager::class.java))
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteMusic:standby")
        wake.setReferenceCounted(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel("player", "播放与远程待命", NotificationManager.IMPORTANCE_LOW)
        )
        session = MediaSession(this, "RemoteMusic")
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play("")
            override fun onPause() = pause("")
            override fun onSkipToNext() = skip(1, "")
            override fun onSkipToPrevious() = skip(-1, "")
            override fun onSeekTo(position: Long) = seek(position, "")
            override fun onStop() = pause("")
        }, main)
        scan()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "remote" -> {
                if (remote) {
                    closeSocket()
                    connect()
                } else {
                    remote = true
                    updateNotification()
                    wake.acquire()
                    connectivity.registerNetworkCallback(NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), networks)
                    useWifi(findWifi())
                }
            }
            "stopRemote" -> {
                stopRemote()
                updateNotification()
                if (!playing && !preparing) stopSelf()
            }
            "track" -> intent.getStringExtra("id")?.let { id ->
                if (scanning) scan(id) else choose(id, "")
            }
            "toggle" -> if (playing || preparing) pause("") else play("")
            "scan" -> scan()
        }
        return START_NOT_STICKY
    }

    private fun findWifi(): Network? = connectivity.allNetworks.firstOrNull { network ->
        val caps = connectivity.getNetworkCapabilities(network)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun useWifi(network: Network?) {
        if (!remote || wifi == network) return
        closeSocket()
        wifi = network
        retrySeconds = 1
        connect()
        updateNotification()
    }

    private fun connect() {
        val network = wifi ?: return
        if (!remote || socket != null) return
        val connection = OkHttpClient.Builder().socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String) = network.getAllByName(hostname).toList()
            }).followRedirects(false).pingInterval(30, TimeUnit.SECONDS).build()
        client = connection
        val request = Request.Builder().url(prefs.getString("origin", "").orEmpty() + "device/socket")
            .header("Authorization", "Bearer " + prefs.getString("token", "").orEmpty()).build()
        socket = connection.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (webSocket !== socket) return@post
                    online = true
                    retrySeconds = 1
                    if (ready) publishCatalog()
                    publish()
                    updateNotification()
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (webSocket === socket && online) command(text) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { failed(webSocket, false) }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val unauthorized = response?.code == 401
                main.post { failed(webSocket, unauthorized) }
            }
        })
    }

    private fun failed(ws: WebSocket, unauthorized: Boolean) {
        if (ws !== socket) return
        closeSocket()
        if (unauthorized) {
            stopRemote()
            error = "设备已解绑，请重新配对"
        } else if (remote && wifi != null) {
            main.postDelayed(reconnect, retrySeconds * 1000L)
            retrySeconds = (retrySeconds * 2).coerceAtMost(60)
        }
        updateNotification()
        if (unauthorized && !playing && !preparing) stopSelf()
    }

    private fun closeSocket() {
        main.removeCallbacks(reconnect)
        online = false
        socket?.cancel()
        socket = null
        client?.let {
            it.connectionPool.evictAll()
            it.dispatcher.executorService.shutdown()
        }
        client = null
    }

    private fun stopRemote() {
        if (!remote) return
        remote = false
        connectivity.unregisterNetworkCallback(networks)
        closeSocket()
        wifi = null
        if (wake.isHeld) wake.release()
    }

    private fun scan(autoPlay: String? = null) {
        val tree = prefs.getString("tree", "").orEmpty()
        val generation = ++scanGeneration
        scanning = true
        scanner.execute {
            try {
                val found = if (tree.isEmpty()) emptyList() else Library.scan(this, Uri.parse(tree))
                main.post {
                    if (destroyed || generation != scanGeneration) return@post
                    scanning = false
                    ready = true
                    songs = found
                    queue.removeIf { findSong(it) == null }
                    publishCatalog()
                    publish()
                    if (autoPlay != null) choose(autoPlay, "")
                    else if (!remote && !playing && !preparing && player == null) stopSelf()
                }
            } catch (_: Exception) {
                main.post {
                    if (destroyed || generation != scanGeneration) return@post
                    scanning = false
                    ready = true
                    songs = emptyList()
                    queue.clear()
                    publishCatalog()
                    finish("", "曲库读取失败，请重新授权目录")
                }
            }
        }
    }

    private fun publishCatalog() {
        if (online) send(Library.catalog(songs))
    }

    private fun send(message: JSONObject) {
        val ws = socket ?: return
        if (online && !ws.send(message.toString())) failed(ws, false)
    }

    private fun command(raw: String) {
        try {
            val cmd = JSONObject(raw)
            val request = cmd.getString("request")
            val remaining = cmd.getLong("expires") - System.currentTimeMillis()
            if (remaining !in 1L..30000L) {
                finish(request, "指令已过期，请检查设备时间")
                return
            }
            when (cmd.getString("action")) {
                "track" -> choose(cmd.getString("id"), request)
                "enqueue" -> {
                    val id = cmd.getString("id")
                    if (queue.size >= 100 || findSong(id) == null) {
                        finish(request, "曲库中无此曲目或队列已满")
                    } else {
                        queue.addLast(id)
                        finish(request, "")
                    }
                }
                "clearQueue" -> {
                    queue.clear()
                    finish(request, "")
                }
                "play" -> play(request)
                "pause" -> pause(request)
                "next" -> skip(1, request)
                "previous" -> skip(-1, request)
                "seek" -> seek(cmd.getLong("value"), request)
                "volume" -> {
                    volume = cmd.getInt("value").coerceIn(0, 100)
                    prefs.edit().putInt("volume", volume).apply()
                    player?.setVolume(volume / 100f, volume / 100f)
                    finish(request, "")
                }
                else -> finish(request, "不支持该指令")
            }
        } catch (_: JSONException) {
            // Ignore malformed messages.
        }
    }

    private fun findSong(id: String) = songs.firstOrNull { it.id == id }

    private fun choose(id: String, request: String) {
        val song = findSong(id)
        if (song == null) finish(request, "曲目不可用，请重新扫描") else load(song, request)
    }

    private fun skip(delta: Int, request: String) {
        if (delta > 0 && queue.isNotEmpty()) {
            choose(queue.removeFirst(), request)
            return
        }
        if (songs.isEmpty()) {
            finish(request, "曲库为空")
            return
        }
        val index = songs.indexOfFirst { it.id == selected?.id }
        load(songs[if (index < 0) 0 else Math.floorMod(index + delta, songs.size)], request)
    }

    private fun load(song: Library.Song, request: String) {
        releasePlayer()
        selected = song
        preparing = true
        preparingAck = request
        try {
            updateNotification()
            val loading = MediaPlayer()
            player = loading
            loading.setAudioAttributes(audio)
            loading.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK)
            loading.setDataSource(this, song.uri)
            loading.setVolume(volume / 100f, volume / 100f)
            loading.setOnPreparedListener { current ->
                if (player !== current) return@setOnPreparedListener
                preparing = false
                val waiting = preparingAck
                if (waiting == null) {
                    updateNotification()
                    publish()
                } else {
                    preparingAck = ""
                    play(waiting)
                }
            }
            loading.setOnCompletionListener { current ->
                if (player === current) {
                    playing = false
                    if (queue.isNotEmpty() || songs.size > 1) skip(1, "") else pause("")
                }
            }
            loading.setOnErrorListener { current, _, _ ->
                if (player === current) {
                    val waiting = preparingAck
                    releasePlayer()
                    finish(waiting, "无法播放该文件")
                }
                true
            }
            loading.prepareAsync()
            main.postDelayed({
                if (player === loading && preparing) {
                    val waiting = preparingAck
                    releasePlayer()
                    finish(waiting, "读取音频超时")
                }
            }, 8000)
        } catch (_: Exception) {
            releasePlayer()
            finish(request, "无法读取或启动该曲目")
        }
    }

    private fun play(request: String) {
        if (preparing) {
            preparingAck = request
            return
        }
        val current = player
        if (current == null) {
            val song = selected
            if (song != null) load(song, request) else skip(1, request)
            return
        }
        try {
            playing = true
            updateNotification()
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                playing = false
                finish(request, "暂时无法取得音频焦点")
                return
            }
            current.start()
            main.removeCallbacks(tick)
            main.postDelayed(tick, 5000)
            finish(request, "")
        } catch (_: RuntimeException) {
            releasePlayer()
            finish(request, "播放启动失败")
        }
    }

    private fun pause(request: String) {
        if (preparing) preparingAck = null
        if (playing) player?.pause()
        playing = false
        audioManager.abandonAudioFocusRequest(focus)
        main.removeCallbacks(tick)
        finish(request, "")
    }

    private fun seek(position: Long, request: String) {
        val current = player
        if (current == null || preparing) {
            finish(request, "当前无法调整进度")
            return
        }
        current.setOnSeekCompleteListener { if (it === player) finish(request, "") }
        current.seekTo(position.coerceIn(0L, current.duration.coerceAtLeast(0).toLong()), MediaPlayer.SEEK_CLOSEST)
    }

    private fun releasePlayer() {
        playing = false
        preparing = false
        preparingAck = ""
        audioManager.abandonAudioFocusRequest(focus)
        main.removeCallbacks(tick)
        player?.release()
        player = null
    }

    private fun finish(request: String?, message: String) {
        if (!request.isNullOrEmpty()) ack = request
        error = message
        updateNotification()
        publish()
    }

    private fun publish() {
        val current = player?.takeUnless { preparing }
        val position = (current?.currentPosition ?: 0).coerceAtLeast(0)
        val duration = (current?.duration ?: 0).coerceAtLeast(0)
        session.setPlaybackState(PlaybackState.Builder().setActions(
            PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
        ).setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
            position.toLong(), if (playing) 1f else 0f).build())
        selected?.let {
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, it.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong()).build())
        }
        if (!online) return
        val state = JSONObject().put("id", selected?.id.orEmpty()).put("playing", playing)
            .put("position", position).put("duration", duration).put("volume", volume)
            .put("ack", ack).put("error", error).put("queue", JSONArray(queue))
        send(JSONObject().put("type", "state").put("state", state))
    }

    private fun updateNotification() {
        val active = playing || (preparing && preparingAck != null)
        session.isActive = active || remote
        if (!active && !remote) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            return
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PlayerService::class.java).setAction("stopRemote"),
            PendingIntent.FLAG_IMMUTABLE)
        val status = when {
            playing -> selected?.title ?: "正在播放"
            online -> "远程待命 · 在线"
            remote -> "远程待命 · 等待 Wi-Fi 或服务器"
            else -> "正在准备播放"
        }
        val builder = Notification.Builder(this, "player").setContentTitle("远程音乐")
            .setContentText(status).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
        if (active) builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        if (remote) builder.addAction(Notification.Action.Builder(null, "关闭待命", stop).build())
        var type = if (active) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        if (Build.VERSION.SDK_INT >= 34 && remote) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, builder.build(), type)
        else startForeground(1, builder.build())
        foreground = true
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        destroyed = true
        stopRemote()
        releasePlayer()
        main.removeCallbacksAndMessages(null)
        scanner.shutdownNow()
        session.release()
        super.onDestroy()
    }
}
