package com.sora.music

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private companion object {
        val BACKGROUND = 0xff101b17.toInt()
        val SURFACE = 0xff182722.toInt()
        val LINE = 0xff354940.toInt()
        val TEXT = 0xfff4f8f5.toInt()
        val MUTED = 0xffafc2b7.toInt()
        val MINT = 0xffb2f1cc.toInt()
        val SECRET_FORMAT = Regex("[0-9a-f]{64}")
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val http = OkHttpClient.Builder().followRedirects(false).callTimeout(10, TimeUnit.SECONDS).build()
    private lateinit var prefs: SharedPreferences
    private lateinit var address: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var pairing: Button
    private lateinit var adapter: ArrayAdapter<String>
    private var songs = emptyList<Library.Song>()
    private var scanGeneration = 0
    private var state = JSONObject()
    private var showLibrary = false
    private var shownRows = emptyList<String>()
    private var shownTitles = emptyList<String>()
    private lateinit var nowPlaying: TextView
    private lateinit var playlistButton: Button
    private lateinit var repeatButton: Button
    private lateinit var playButton: Button
    private lateinit var progress: SeekBar
    private lateinit var libraryButton: Button
    private lateinit var listButton: Button
    private var connectionStatus: TextView? = null
    private var binder: PlayerService.LocalBinder? = null
    private var bound = false
    private val observer: (JSONObject) -> Unit = { snapshot ->
        state = snapshot
        renderPlayer()
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service as PlayerService.LocalBinder
            binder?.observe(observer)
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val list = ListView(this).apply {
            setBackgroundColor(BACKGROUND)
            divider = null
            setHeaderDividersEnabled(false)
            itemsCanFocus = true
            setOnApplyWindowInsetsListener { view, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime())
                    view.setPadding(safe.left + dp(16), safe.top + dp(12), safe.right + dp(16), safe.bottom + dp(12))
                } else {
                    view.setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                insets
            }
        }
        setContentView(list)

        val header = column()
        header.addView(buttonRow(label("♫  远程音乐", 26, TEXT, true), button("设置", run = ::showSettings)))
        val playback = panel(header, "NOW PLAYING", "正在播放")
        nowPlaying = label("选择一首歌", 23, TEXT, true)
        add(playback, nowPlaying, 10)
        progress = SeekBar(this).apply {
            contentDescription = "播放进度"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(bar: SeekBar) { bar.isPressed = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    bar.isPressed = false
                    startService(Intent(this@MainActivity, PlayerService::class.java).setAction("seek")
                        .putExtra("position", bar.progress.toLong()))
                }
            })
        }
        add(playback, progress, 8)
        playButton = button("播放", true) { action("toggle") }
        add(playback, buttonRow(button("上一首") { action("previous") }, playButton,
            button("下一首") { action("next") }), 8)
        repeatButton = button("列表循环") { action("repeat") }
        add(playback, repeatButton, 8)
        val playlists = panel(header, "PLAYLISTS", "你的音乐")
        playlistButton = button("我的歌单", run = ::selectPlaylist)
        add(playlists, buttonRow(playlistButton, button("新建歌单", run = ::createPlaylist)), 8)
        listButton = button("当前歌单") { showLibrary = false; renderPlayer() }
        libraryButton = button("本地曲库") { showLibrary = true; renderPlayer() }
        add(playlists, buttonRow(listButton, libraryButton), 8)
        status = label("", 14, MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(header, status, 12)
        list.addHeaderView(header, null, false)
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                return (convertView as? TextView ?: label("", 16, TEXT)).apply {
                    text = getItem(position)
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(52)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, index, _ ->
            shownRows.getOrNull(index - list.headerViewsCount)?.let { id ->
                if (showLibrary) action("enqueue", id) else action("track", id)
            }
        }
        list.setOnItemLongClickListener { _, _, index, _ ->
            val id = shownRows.getOrNull(index - list.headerViewsCount)
            if (showLibrary || id == null) false
            else {
                AlertDialog.Builder(this).setMessage("将歌曲移出当前歌单？").setNegativeButton("取消", null)
                    .setPositiveButton("移出") { _, _ -> action("removeTrack", id) }.show()
                true
            }
        }
        renderPlayer()
        scan()
    }

    override fun onStart() {
        super.onStart()
        bound = bindService(Intent(this, PlayerService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        binder?.remove(observer)
        binder = null
        if (bound) unbindService(connection)
        bound = false
        super.onStop()
    }

    private fun buttonRow(vararg views: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
    }

    private fun playlistChoices(): List<Pair<String, String>> {
        val items = state.optJSONArray("playlists") ?: return emptyList()
        return (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            item.getString("id") to item.getString("name")
        }
    }

    private fun renderPlayer() {
        val current = state.optString("id")
        nowPlaying.text = songs.firstOrNull { it.id == current }?.title ?: "选择一首歌"
        playButton.text = if (state.optBoolean("playing")) "暂停" else "播放"
        repeatButton.text = RepeatMode.entries.firstOrNull { it.name == state.optString("repeat") }?.label ?: "列表循环"
        val active = state.optString("playlist")
        playlistButton.text = playlistChoices().firstOrNull { it.first == active }?.second ?: "我的歌单"
        val tracks = state.optJSONArray("queue")
        val ids = if (showLibrary) songs.map { it.id }
            else if (tracks == null) emptyList() else List(tracks.length()) { tracks.getString(it) }
        val byId = songs.associateBy { it.id }
        val titles = ids.map { id -> (if (id == current) "♫  " else "") + (byId[id]?.title ?: "曲目暂不可用") }
        if (shownRows != ids || shownTitles != titles) {
            shownRows = ids
            shownTitles = titles
            adapter.clear()
            adapter.addAll(titles)
        }
        listButton.isEnabled = showLibrary
        libraryButton.isEnabled = !showLibrary
        if (!progress.isPressed) {
            progress.max = state.optInt("duration")
            progress.progress = state.optInt("position")
        }
        progress.isEnabled = progress.max > 0
        val error = state.optString("error")
        status.text = when {
            error.isNotEmpty() -> error
            showLibrary && ids.isEmpty() -> "在设置里选择本地音乐目录"
            showLibrary -> "点按曲目，加入当前歌单"
            ids.isEmpty() -> "切到本地曲库，为这个歌单添加歌曲"
            else -> "点按播放 · 长按移出歌单"
        }
    }

    private fun selectPlaylist() {
        val lists = playlistChoices()
        if (lists.isEmpty()) return
        AlertDialog.Builder(this).setTitle("选择歌单").setSingleChoiceItems(
            lists.map { it.second }.toTypedArray(), lists.indexOfFirst { it.first == state.optString("playlist") }
        ) { dialog, index ->
            showLibrary = false
            action("selectPlaylist", lists[index].first)
            dialog.dismiss()
        }.setNegativeButton("取消", null).show()
    }

    private fun createPlaylist() {
        val input = field("歌单名称", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        AlertDialog.Builder(this).setTitle("新建歌单").setView(input).setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                showLibrary = true
                action("createPlaylist", input.text.toString())
            }.show()
    }

    private fun showSettings() {
        val content = column().apply { setPadding(dp(16), 0, dp(16), dp(16)) }
        val device = panel(content, "DEVICE", "连接到网页")
        val message = label("输入控制台地址和一次性配对码", 14, MUTED)
        connectionStatus = message
        add(device, message, 8)
        address = field("https://music.example.com", InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(prefs.getString("origin", ""))
        }
        code = field("网页一次性配对码", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pairing = button("配对设备", true, ::pair)
        add(device, address, 8)
        add(device, code, 8)
        add(device, pairing, 8)
        add(device, buttonRow(button("开启 Wi-Fi 待命", true, ::startRemote),
            button("关闭待命") { action("stopRemote"); message.text = "已请求关闭待命" }), 8)
        val library = panel(content, "LIBRARY", "音乐目录")
        add(library, buttonRow(button("选择目录") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        }, button("重新扫描", run = ::scan)), 8)
        AlertDialog.Builder(this).setTitle("设置").setView(ScrollView(this).apply { addView(content) })
            .setPositiveButton("完成", null).setOnDismissListener { connectionStatus = null }.show()
    }

    private fun report(message: String) {
        (connectionStatus ?: status).text = message
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun add(layout: LinearLayout, child: View, gap: Int) {
        layout.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(gap) })
    }

    private fun shape(fill: Int, border: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), border)
    }

    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = size.toFloat()
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun panel(parent: LinearLayout, category: String, title: String): LinearLayout {
        val panel = column().apply {
            background = shape(SURFACE, LINE, 20)
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        panel.addView(label(category, 11, MINT, true).apply { letterSpacing = 0.13f })
        add(panel, label(title, 20, TEXT, true), 2)
        add(parent, panel, 12)
        return panel
    }

    private fun field(prompt: String, variation: Int) = EditText(this).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or variation
        hint = prompt
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        background = shape(BACKGROUND, LINE, 12)
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun styledButton(title: String, primary: Boolean) = Button(this).apply {
        text = title
        setAllCaps(false)
        minHeight = dp(46)
        setTextColor(if (primary) BACKGROUND else TEXT)
        val fill = if (primary) MINT else 0xff20332a.toInt()
        background = RippleDrawable(ColorStateList.valueOf(0x3090b29e), shape(fill, if (primary) MINT else LINE, 12), null)
        setPadding(dp(10), dp(5), dp(10), dp(5))
    }

    private fun button(title: String, primary: Boolean = false, run: () -> Unit) =
        styledButton(title, primary).apply { setOnClickListener { run() } }

    private fun action(action: String, id: String? = null) {
        val intent = Intent(this, PlayerService::class.java).setAction(action).putExtra("id", id)
        if (action == "remote") startForegroundService(intent) else startService(intent)
    }

    private fun startRemote() {
        if (!prefs.contains("token") || !prefs.contains("tree")) {
            report("请先配对并选择音乐目录")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        action("remote")
        report("已请求待命，连接结果可在网页或通知中查看")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRemote()
    }

    private fun pair() {
        val url = address.text.toString().trim().toHttpUrlOrNull()
        val secret = code.text.toString().trim()
        if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.encodedPath != "/" || url.query != null || url.fragment != null || !SECRET_FORMAT.matches(secret)) {
            report("请输入 HTTPS 站点地址和有效的配对码")
            return
        }
        pairing.isEnabled = false
        worker.execute {
            try {
                val json = JSONObject().put("code", secret).toString()
                val request = Request.Builder().url(checkNotNull(url.resolve("/device/pair")))
                    .post(json.toRequestBody("application/json".toMediaType())).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful)
                    val key = JSONObject(checkNotNull(response.body).string()).getString("token")
                    check(SECRET_FORMAT.matches(key))
                    prefs.edit().putString("origin", url.toString()).putString("token", key).apply()
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    code.text.clear()
                    report("配对成功，可以开启待命")
                    pairing.isEnabled = true
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    report("配对失败，请检查地址、证书和配对码")
                    pairing.isEnabled = true
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1 || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val old = prefs.getString("tree", "").orEmpty()
            prefs.edit().putString("tree", tree.toString()).apply()
            if (old.isNotEmpty() && old != tree.toString()) {
                try {
                    contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    // The previous grant may already have been revoked.
                }
            }
            scan()
        } catch (_: SecurityException) {
            report("无法保留目录读取权限，请重新选择")
        }
    }

    private fun scan() {
        val tree = prefs.getString("tree", "").orEmpty()
        if (tree.isEmpty()) {
            report("请选择音乐目录")
            return
        }
        val generation = ++scanGeneration
        report("正在扫描曲库…")
        worker.execute {
            try {
                val found = Library.scan(this, Uri.parse(tree))
                runOnUiThread {
                    if (isDestroyed || generation != scanGeneration) return@runOnUiThread
                    songs = found
                    renderPlayer()
                    connectionStatus?.text = "本地曲库 · " + songs.size + " 首"
                    action("scan")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isDestroyed && generation == scanGeneration) report("扫描失败，请检查目录授权及曲库大小")
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
