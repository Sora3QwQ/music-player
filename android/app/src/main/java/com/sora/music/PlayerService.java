package com.sora.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.json.JSONException;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PlayerService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final AudioAttributes audio = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
    private List<Library.Song> songs = Collections.emptyList();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private Library.Song selected;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private SharedPreferences prefs;
    private ConnectivityManager connectivity;
    private Network wifi;
    private OkHttpClient client;
    private WebSocket socket;
    private PowerManager.WakeLock wake;
    private boolean ready, online, remote, preparing, playing, destroyed, foreground;
    private boolean scanning;
    private int scanGeneration, retrySeconds = 1, volume = 100;
    private String ack = "", preparingAck = "", error = "";

    private final Runnable reconnect = this::connect;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (online && playing) publish();
            if (playing) main.postDelayed(this,5000);
        }
    };

    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { main.post(() -> useWifi(network)); }
        @Override public void onLost(Network network) {
            main.post(() -> {
                if (network.equals(wifi)) useWifi(findWifi());
            });
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        volume = prefs.getInt("volume",100);
        connectivity = getSystemService(ConnectivityManager.class);
        audioManager = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(audio)
                .setOnAudioFocusChangeListener(change -> { if (change < 0 && (playing || preparing)) pause(""); },main).build();
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"RemoteMusic:standby");
        wake.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("player","播放与远程待命",NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this,"RemoteMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(""); }
            @Override public void onPause() { pause(""); }
            @Override public void onSkipToNext() { skip(1,""); }
            @Override public void onSkipToPrevious() { skip(-1,""); }
            @Override public void onSeekTo(long position) { seek(position,""); }
            @Override public void onStop() { pause(""); }
        },main);
        scan(null);
    }

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if ("remote".equals(action)) {
            if (remote) {
                closeSocket();
                connect();
            } else {
                remote = true;
                updateNotification();
                wake.acquire();
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),networks);
                useWifi(findWifi());
            }
        } else if ("stopRemote".equals(action)) {
            stopRemote();
            updateNotification();
            if (!playing && !preparing) stopSelf();
        } else if ("track".equals(action)) {
            String song = intent.getStringExtra("id");
            if (scanning) scan(song);
            else choose(song,"");
        } else if ("toggle".equals(action)) {
            if (playing || preparing) pause("");
            else play("");
        } else if ("scan".equals(action)) {
            scan(null);
        }
        return START_NOT_STICKY;
    }

    private Network findWifi() {
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return network;
        }
        return null;
    }

    private void useWifi(Network network) {
        if (!remote || (wifi == null ? network == null : wifi.equals(network))) return;
        closeSocket();
        wifi = network;
        retrySeconds = 1;
        connect();
        updateNotification();
    }

    private void connect() {
        if (!remote || wifi == null || socket != null) return;
        Network network = wifi;
        client = new OkHttpClient.Builder().socketFactory(network.getSocketFactory())
                .dns(host -> Arrays.asList(network.getAllByName(host)))
                .followRedirects(false).pingInterval(30, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url(prefs.getString("origin","") + "device/socket")
                .header("Authorization","Bearer " + prefs.getString("token","")).build();
        socket = client.newWebSocket(request,new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                main.post(() -> {
                    if (ws != socket) return;
                    online = true;
                    retrySeconds = 1;
                    if (ready) publishCatalog();
                    publish();
                    updateNotification();
                });
            }
            @Override public void onMessage(WebSocket ws,String message) {
                main.post(() -> { if (ws == socket && online) command(message); });
            }
            @Override public void onClosed(WebSocket ws,int code,String reason) {
                main.post(() -> failed(ws,false));
            }
            @Override public void onClosing(WebSocket ws,int code,String reason) { ws.close(1000,null); }
            @Override public void onFailure(WebSocket ws,Throwable error,Response response) {
                boolean unauthorized = response != null && response.code() == 401;
                main.post(() -> failed(ws,unauthorized));
            }
        });
    }

    private void failed(WebSocket ws, boolean unauthorized) {
        if (ws != socket) return;
        closeSocket();
        if (unauthorized) {
            stopRemote();
            error = "设备已解绑，请重新配对";
        } else if (remote && wifi != null) {
            main.postDelayed(reconnect,retrySeconds * 1000L);
            retrySeconds = Math.min(retrySeconds * 2,60);
        }
        updateNotification();
        if (unauthorized && !playing && !preparing) stopSelf();
    }

    private void closeSocket() {
        main.removeCallbacks(reconnect);
        online = false;
        if (socket != null) { socket.cancel(); socket = null; }
        if (client != null) {
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
            client = null;
        }
    }

    private void stopRemote() {
        if (!remote) return;
        remote = false;
        connectivity.unregisterNetworkCallback(networks);
        closeSocket();
        wifi = null;
        if (wake.isHeld()) wake.release();
    }

    private void scan(String autoPlay) {
        String tree = prefs.getString("tree","");
        int generation = ++scanGeneration;
        scanning = true;
        scanner.execute(() -> {
            try {
                List<Library.Song> found = tree.isEmpty() ? Collections.emptyList() : Library.scan(this,Uri.parse(tree));
                main.post(() -> {
                    if (destroyed || generation != scanGeneration) return;
                    scanning = false;
                    ready = true;
                    songs = found;
                    queue.removeIf(id -> findSong(id) == null);
                    publishCatalog();
                    publish();
                    if (autoPlay != null) choose(autoPlay,"");
                    else if (!remote && !playing && !preparing && player == null) stopSelf();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (destroyed || generation != scanGeneration) return;
                    scanning = false;
                    songs = Collections.emptyList();
                    queue.clear();
                    ready = true;
                    publishCatalog();
                    finish("", "曲库读取失败，请重新授权目录");
                });
            }
        });
    }

    private void publishCatalog() {
        if (!online) return;
        try { send(Library.catalog(songs)); }
        catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    private void send(JSONObject message) {
        WebSocket ws = socket;
        if (online && ws != null && !ws.send(message.toString())) failed(ws,false);
    }

    private void command(String raw) {
        try {
            JSONObject cmd = new JSONObject(raw);
            String request = cmd.getString("request");
            long remaining = cmd.getLong("expires") - System.currentTimeMillis();
            if (remaining <= 0 || remaining > 30000) { finish(request,"指令已过期，请检查设备时间"); return; }
            switch (cmd.getString("action")) {
                case "track": choose(cmd.getString("id"),request); break;
                case "enqueue":
                    String id = cmd.getString("id");
                    if (queue.size() >= 100 || findSong(id) == null) {
                        finish(request,"曲库中无此曲目或队列已满");
                    } else {
                        queue.addLast(id);
                        finish(request,"");
                    }
                    break;
                case "clearQueue":
                    queue.clear();
                    finish(request,"");
                    break;
                case "play": play(request); break;
                case "pause": pause(request); break;
                case "next": skip(1,request); break;
                case "previous": skip(-1,request); break;
                case "seek": seek(cmd.getLong("value"),request); break;
                case "volume":
                    volume = Math.max(0,Math.min(100,cmd.getInt("value")));
                    prefs.edit().putInt("volume",volume).apply();
                    if (player != null) player.setVolume(volume/100f,volume/100f);
                    finish(request,"");
                    break;
                default: finish(request,"不支持该指令");
            }
        } catch (JSONException ignored) { }
    }

    private Library.Song findSong(String id) {
        for (Library.Song song : songs) {
            if (song.id.equals(id)) return song;
        }
        return null;
    }

    private void choose(String id,String request) {
        Library.Song song = findSong(id);
        if (song == null) finish(request,"曲目不可用，请重新扫描");
        else load(song,request);
    }

    private void skip(int delta,String request) {
        if (delta > 0 && !queue.isEmpty()) { choose(queue.removeFirst(),request); return; }
        if (songs.isEmpty()) { finish(request,"曲库为空"); return; }
        int index = -1;
        for (int i = 0; i < songs.size(); i++) {
            if (selected != null && songs.get(i).id.equals(selected.id)) { index = i; break; }
        }
        load(songs.get(index < 0 ? 0 : Math.floorMod(index+delta,songs.size())),request);
    }

    private void load(Library.Song song,String request) {
        releasePlayer();
        selected = song;
        preparing = true;
        preparingAck = request;
        try {
            updateNotification();
            player = new MediaPlayer();
            player.setAudioAttributes(audio);
            player.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
            player.setDataSource(this,song.uri);
            player.setVolume(volume/100f,volume/100f);
            player.setOnPreparedListener(current -> {
                if (player != current) return;
                preparing = false;
                if (preparingAck == null) { updateNotification(); publish(); return; }
                String waiting = preparingAck;
                preparingAck = "";
                play(waiting);
            });
            player.setOnCompletionListener(current -> {
                if (player == current) {
                    playing = false;
                    if (!queue.isEmpty() || songs.size() > 1) skip(1,"");
                    else pause("");
                }
            });
            player.setOnErrorListener((current,what,extra) -> {
                if (player == current) {
                    String waiting = preparingAck;
                    releasePlayer();
                    finish(waiting,"无法播放该文件");
                }
                return true;
            });
            player.prepareAsync();
            MediaPlayer loading = player;
            main.postDelayed(() -> {
                if (loading == player && preparing) {
                    String waiting = preparingAck;
                    releasePlayer();
                    finish(waiting,"读取音频超时");
                }
            },8000);
        } catch (Exception failure) {
            releasePlayer();
            finish(request,"无法读取或启动该曲目");
        }
    }

    private void play(String request) {
        if (preparing) { preparingAck = request; return; }
        if (player == null) {
            if (selected != null) load(selected,request);
            else skip(1,request);
            return;
        }
        try {
            playing = true;
            updateNotification();
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                playing = false;
                finish(request,"暂时无法取得音频焦点");
                return;
            }
            player.start();
            main.removeCallbacks(tick);
            main.postDelayed(tick,5000);
            finish(request,"");
        } catch (RuntimeException failure) {
            releasePlayer();
            finish(request,"播放启动失败");
        }
    }

    private void pause(String request) {
        if (preparing) preparingAck = null;
        if (playing && player != null) player.pause();
        playing = false;
        audioManager.abandonAudioFocusRequest(focus);
        main.removeCallbacks(tick);
        finish(request,"");
    }

    private void seek(long position,String request) {
        if (player == null || preparing) { finish(request,"当前无法调整进度"); return; }
        MediaPlayer current = player;
        current.setOnSeekCompleteListener(done -> { if (done == player) finish(request,""); });
        current.seekTo(Math.max(0,Math.min(position,current.getDuration())),MediaPlayer.SEEK_CLOSEST);
    }

    private void releasePlayer() {
        playing = false;
        preparing = false;
        preparingAck = "";
        audioManager.abandonAudioFocusRequest(focus);
        main.removeCallbacks(tick);
        if (player != null) { player.release(); player = null; }
    }

    private void finish(String request,String message) {
        if (request != null && !request.isEmpty()) ack = request;
        error = message;
        updateNotification();
        publish();
    }

    private void publish() {
        int position = 0, duration = 0;
        if (player != null && !preparing) {
            position = Math.max(0,player.getCurrentPosition());
            duration = Math.max(0,player.getDuration());
        }
        session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,position,playing ? 1f : 0f).build());
        if (selected != null) session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,selected.title)
                .putLong(MediaMetadata.METADATA_KEY_DURATION,duration).build());
        if (!online) return;
        try {
            JSONObject state = new JSONObject().put("id",selected == null ? "" : selected.id)
                    .put("playing",playing).put("position",position).put("duration",duration)
                    .put("volume",volume).put("ack",ack).put("error",error).put("queue",new JSONArray(queue));
            send(new JSONObject().put("type","state").put("state",state));
        } catch (JSONException impossible) { throw new AssertionError(impossible); }
    }

    private void updateNotification() {
        boolean active = playing || (preparing && preparingAck != null);
        session.setActive(active || remote);
        if (!active && !remote) {
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
            return;
        }
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,PlayerService.class).setAction("stopRemote"),PendingIntent.FLAG_IMMUTABLE);
        String status = playing && selected != null ? selected.title : online ? "远程待命 · 在线" : remote ? "远程待命 · 等待 Wi-Fi 或服务器" : "正在准备播放";
        Notification.Builder builder = new Notification.Builder(this,"player").setContentTitle("远程音乐")
                .setContentText(status).setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(open)
                .setOngoing(true).setOnlyAlertOnce(true);
        if (active) builder.setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
        if (remote) builder.addAction(new Notification.Action.Builder(null,"关闭待命",stop).build());
        int type = active ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK : 0;
        if (Build.VERSION.SDK_INT >= 34 && remote) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        if (Build.VERSION.SDK_INT >= 29) startForeground(1,builder.build(),type);
        else startForeground(1,builder.build());
        foreground = true;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        stopRemote();
        releasePlayer();
        main.removeCallbacksAndMessages(null);
        scanner.shutdownNow();
        session.release();
        super.onDestroy();
    }
}
