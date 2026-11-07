package com.sora.music;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class Library {
    static final class Song {
        final Uri uri;
        final String id, title;

        Song(Uri uri, String title) throws Exception {
            this.uri = uri;
            this.title = title;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            id = hex.toString();
        }
    }

    static List<Song> scan(Context context, Uri tree) throws Exception {
        List<Song> songs = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> folders = new ArrayDeque<>();
        folders.add(DocumentsContract.getTreeDocumentId(tree));
        while (!folders.isEmpty()) {
            String folder = folders.remove();
            if (!visited.add(folder)) continue;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder);
            String[] columns = {"document_id", "_display_name", "mime_type"};
            try (Cursor cursor = context.getContentResolver().query(children, columns, null, null, null)) {
                if (cursor == null) throw new IllegalStateException("无法读取曲库");
                while (cursor.moveToNext()) {
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        folders.add(id);
                    } else if (name != null && ((mime != null && mime.startsWith("audio/"))
                            || name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|flac|m4a|aac|wav|ogg|opus)$"))) {
                        songs.add(new Song(DocumentsContract.buildDocumentUriUsingTree(tree, id), name));
                        if (songs.size() > 10000) throw new IllegalStateException("曲库最多支持 10000 首");
                    }
                }
            }
        }
        songs.sort(Comparator.comparing(song -> song.title.toLowerCase(Locale.ROOT)));
        return songs;
    }

    static JSONObject catalog(List<Song> songs) throws JSONException {
        JSONArray tracks = new JSONArray();
        for (Song song : songs) tracks.put(new JSONObject().put("id", song.id).put("title", song.title));
        return new JSONObject().put("type", "catalog").put("tracks", tracks);
    }
}
