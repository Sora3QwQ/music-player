package com.sora.music;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONObject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff101b17;
    private static final int SURFACE = 0xff182722;
    private static final int LINE = 0xff354940;
    private static final int TEXT = 0xfff4f8f5;
    private static final int MUTED = 0xffafc2b7;
    private static final int MINT = 0xffb2f1cc;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().followRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS).build();
    private SharedPreferences prefs;
    private EditText address, code;
    private TextView status;
    private Button pairing;
    private List<Library.Song> songs = Collections.emptyList();
    private ArrayAdapter<String> adapter;
    private int scanGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        ListView list = new ListView(this);
        list.setBackgroundColor(BACKGROUND);
        list.setDivider(null);
        list.setHeaderDividersEnabled(false);
        list.setItemsCanFocus(true);
        list.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(safe.left + dp(16), safe.top + dp(12), safe.right + dp(16), safe.bottom + dp(12));
            } else {
                view.setPadding(dp(16), dp(12), dp(16), dp(12));
            }
            return insets;
        });
        setContentView(list);

        LinearLayout header = column();
        TextView title = label("♫   远程音乐", 29, TEXT, true);
        header.addView(title);
        TextView subtitle = label("本地曲库 · 网页遥控", 14, MUTED, false);
        add(header, subtitle, 3);

        LinearLayout standby = panel(header, "REMOTE CONTROL", "远程待命");
        status = label("请选择音乐目录", 14, MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(standby, status, 6);
        add(standby, button("开启待命 · 仅 Wi-Fi", true, this::startRemote), 12);
        add(standby, button("关闭待命", false, () -> action("stopRemote", null)), 6);

        LinearLayout device = panel(header, "DEVICE", "连接到网页");
        LinearLayout fields = column();
        boolean paired = prefs.contains("token");
        fields.setVisibility(paired ? View.GONE : View.VISIBLE);
        Button settings = button(paired ? "展开配对设置" : "收起配对设置", false, () -> {});
        settings.setOnClickListener(view -> {
            boolean open = fields.getVisibility() == View.VISIBLE;
            fields.setVisibility(open ? View.GONE : View.VISIBLE);
            settings.setText(open ? "展开配对设置" : "收起配对设置");
        });
        add(device, settings, 8);
        add(device, fields, 0);
        address = field("https://music.example.com", InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(prefs.getString("origin",""));
        add(fields, address, 8);
        code = field("网页一次性配对码", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(fields, code, 8);
        pairing = button("配对设备", true, this::pair);
        add(fields, pairing, 8);

        LinearLayout library = panel(header, "LIBRARY", "本地曲库");
        LinearLayout tools = new LinearLayout(this);
        tools.addView(button("选择目录", false,
                () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1)),
                new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(0, -2, 1);
        scanParams.leftMargin = dp(8);
        tools.addView(button("重新扫描", false, this::scan), scanParams);
        add(library, tools, 8);
        add(library, button("暂停 / 继续", false, () -> action("toggle", null)), 8);
        add(header, label("点按曲目即可播放", 13, MUTED, false), 12);
        list.addHeaderView(header, null, false);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1) {
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = recycled instanceof TextView ? (TextView) recycled : label("", 16, TEXT, false);
                row.setText(getItem(position));
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinHeight(dp(52));
                row.setPadding(dp(12), dp(8), dp(12), dp(8));
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> {
            int song = index - list.getHeaderViewsCount();
            if (song >= 0 && song < songs.size()) action("track", songs.get(song).id);
        });
        scan();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout layout, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(gap);
        layout.addView(child, params);
    }

    private GradientDrawable shape(int fill, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), border);
        return background;
    }

    private TextView label(String text, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout panel(LinearLayout parent, String category, String title) {
        LinearLayout panel = column();
        panel.setBackground(shape(SURFACE, LINE, 20));
        panel.setPadding(dp(15), dp(13), dp(15), dp(13));
        TextView eyebrow = label(category, 11, MINT, true);
        eyebrow.setLetterSpacing(0.13f);
        panel.addView(eyebrow);
        add(panel, label(title, 20, TEXT, true), 2);
        add(parent, panel, 12);
        return panel;
    }

    private EditText field(String hint, int variation) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        input.setHint(hint);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setBackground(shape(BACKGROUND, LINE, 12));
        input.setMinHeight(dp(48));
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        return input;
    }

    private Button button(String label, boolean primary, Runnable run) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(46));
        button.setTextColor(primary ? BACKGROUND : TEXT);
        int fill = primary ? MINT : 0xff20332a;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3090b29e),
                shape(fill, primary ? MINT : LINE, 12), null));
        button.setPadding(dp(10), dp(5), dp(10), dp(5));
        button.setOnClickListener(view -> run.run());
        return button;
    }

    private void action(String action, String id) {
        Intent intent = new Intent(this, PlayerService.class).setAction(action).putExtra("id",id);
        if (action.equals("remote")) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void startRemote() {
        if (!prefs.contains("token") || !prefs.contains("tree")) {
            status.setText("请先配对并选择音乐目录");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        action("remote",null);
        status.setText("已请求待命，连接结果可在网页或通知中查看");
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 2 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startRemote();
    }

    private void pair() {
        HttpUrl url = HttpUrl.parse(address.getText().toString().trim());
        String secret = code.getText().toString().trim();
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || !url.encodedPath().equals("/") || url.query() != null || url.fragment() != null
                || !secret.matches("[0-9a-f]{64}")) {
            status.setText("请输入 HTTPS 站点地址和有效的配对码");
            return;
        }
        pairing.setEnabled(false);
        worker.execute(() -> {
            try {
                String json = new JSONObject().put("code",secret).toString();
                Request request = new Request.Builder().url(url.resolve("/device/pair"))
                        .post(RequestBody.create(json, MediaType.get("application/json"))).build();
                try (Response response = http.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException();
                    String key = new JSONObject(response.body().string()).getString("token");
                    if (!key.matches("[0-9a-f]{64}")) throw new IllegalStateException();
                    prefs.edit().putString("origin",url.toString()).putString("token",key).apply();
                }
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    code.setText("");
                    status.setText("配对成功，可以开启待命");
                    pairing.setEnabled(true);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    status.setText("配对失败，请检查地址、证书和配对码");
                    pairing.setEnabled(true);
                });
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String old = prefs.getString("tree","");
            prefs.edit().putString("tree",tree.toString()).apply();
            if (!old.isEmpty() && !old.equals(tree.toString())) {
                try { getContentResolver().releasePersistableUriPermission(Uri.parse(old),Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (SecurityException ignored) { }
            }
            scan();
        } catch (SecurityException failure) {
            status.setText("无法保留目录读取权限，请重新选择");
        }
    }

    private void scan() {
        String tree = prefs.getString("tree","");
        if (tree.isEmpty()) { status.setText("请选择音乐目录"); return; }
        int generation = ++scanGeneration;
        status.setText("正在扫描曲库…");
        worker.execute(() -> {
            try {
                List<Library.Song> found = Library.scan(this,Uri.parse(tree));
                runOnUiThread(() -> {
                    if (isDestroyed() || generation != scanGeneration) return;
                    songs = found;
                    adapter.clear();
                    for (Library.Song song : songs) adapter.add(song.title);
                    status.setText("本地曲库 · " + songs.size() + " 首");
                    action("scan",null);
                });
            } catch (Exception failure) {
                runOnUiThread(() -> {
                    if (!isDestroyed() && generation == scanGeneration) status.setText("扫描失败，请检查目录授权及曲库大小");
                });
            }
        });
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
