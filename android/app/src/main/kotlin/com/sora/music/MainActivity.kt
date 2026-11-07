package com.sora.music

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
        header.addView(label("♫   远程音乐", 29, TEXT, true))
        add(header, label("本地曲库 · 网页遥控", 14, MUTED), 3)
        val standby = panel(header, "REMOTE CONTROL", "远程待命")
        status = label("请选择音乐目录", 14, MUTED).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        add(standby, status, 6)
        add(standby, button("开启待命 · 仅 Wi-Fi", true, ::startRemote), 12)
        add(standby, button("关闭待命") { action("stopRemote") }, 6)

        val device = panel(header, "DEVICE", "连接到网页")
        val fields = column()
        val paired = prefs.contains("token")
        fields.visibility = if (paired) View.GONE else View.VISIBLE
        val settings = styledButton(if (paired) "展开配对设置" else "收起配对设置", false)
        settings.setOnClickListener {
            val open = fields.visibility == View.VISIBLE
            fields.visibility = if (open) View.GONE else View.VISIBLE
            settings.text = if (open) "展开配对设置" else "收起配对设置"
        }
        add(device, settings, 8)
        add(device, fields, 0)
        address = field("https://music.example.com", InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(prefs.getString("origin", ""))
        }
        add(fields, address, 8)
        code = field("网页一次性配对码", InputType.TYPE_TEXT_VARIATION_PASSWORD)
        add(fields, code, 8)
        pairing = button("配对设备", true, ::pair)
        add(fields, pairing, 8)

        val library = panel(header, "LIBRARY", "本地曲库")
        val tools = LinearLayout(this)
        tools.addView(button("选择目录") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        tools.addView(button("重新扫描", run = ::scan), LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(8)
        })
        add(library, tools, 8)
        add(library, button("暂停 / 继续") { action("toggle") }, 8)
        add(header, label("点按曲目即可播放", 13, MUTED), 12)
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
            songs.getOrNull(index - list.headerViewsCount)?.let { action("track", it.id) }
        }
        scan()
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
            status.text = "请先配对并选择音乐目录"
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            return
        }
        action("remote")
        status.text = "已请求待命，连接结果可在网页或通知中查看"
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
            status.text = "请输入 HTTPS 站点地址和有效的配对码"
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
                    status.text = "配对成功，可以开启待命"
                    pairing.isEnabled = true
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    status.text = "配对失败，请检查地址、证书和配对码"
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
            status.text = "无法保留目录读取权限，请重新选择"
        }
    }

    private fun scan() {
        val tree = prefs.getString("tree", "").orEmpty()
        if (tree.isEmpty()) {
            status.text = "请选择音乐目录"
            return
        }
        val generation = ++scanGeneration
        status.text = "正在扫描曲库…"
        worker.execute {
            try {
                val found = Library.scan(this, Uri.parse(tree))
                runOnUiThread {
                    if (isDestroyed || generation != scanGeneration) return@runOnUiThread
                    songs = found
                    adapter.clear()
                    adapter.addAll(found.map { it.title })
                    status.text = "本地曲库 · " + songs.size + " 首"
                    action("scan")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isDestroyed && generation == scanGeneration) status.text = "扫描失败，请检查目录授权及曲库大小"
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
