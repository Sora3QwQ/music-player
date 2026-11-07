package com.sora.music;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder().followRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS).build();
    private SharedPreferences prefs;
    private EditText address, code;
    private TextView status;
    private Button start, pairing;
    private List<Library.Song> songs = Collections.emptyList();
    private ArrayAdapter<String> adapter;
    private int scanGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding,padding,padding,padding);
        setContentView(layout);
        status = new TextView(this);
        layout.addView(status);
        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://music.example.com");
        address.setText(prefs.getString("origin",""));
        layout.addView(address);
        code = new EditText(this);
        code.setSingleLine(true);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setHint("网页一次性配对码");
        layout.addView(code);
        pairing = button(layout,"配对", this::pair);
        button(layout,"选择音乐目录", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 1));
        button(layout,"重新扫描", this::scan);
        start = button(layout,"开启远程待命（仅 Wi-Fi）", this::startRemote);
        button(layout,"关闭远程待命", () -> action("stopRemote", null));
        button(layout,"暂停 / 继续", () -> action("toggle", null));
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> action("track", songs.get(index).id));
        layout.addView(list, new LinearLayout.LayoutParams(-1,0,1));
        scan();
    }

    private Button button(LinearLayout layout, String label, Runnable run) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> run.run());
        layout.addView(button);
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
