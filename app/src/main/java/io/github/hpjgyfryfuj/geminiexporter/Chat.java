package io.github.hpjgyfryfuj.geminiexporter;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A conversation as returned by extract.js, or built from shared plain text. */
final class Chat {

    static final String ROLE_USER = "user";
    static final String ROLE_MODEL = "model";
    static final String ROLE_PAGE = "page";
    static final String ROLE_TEXT = "text";

    static final class Turn {
        final String role;
        /** Sanitized HTML; images appear as {{GEIMG:n}} tokens. */
        final String html;
        /** Markdown; images appear as {{GEIMG:n}} tokens. */
        final String markdown;

        Turn(String role, String html, String markdown) {
            this.role = role;
            this.html = html;
            this.markdown = markdown;
        }
    }

    final String title;
    final String url;
    final List<Turn> turns;
    /** Image sources by token index: http(s) URLs or data: URIs. */
    final List<String> images;

    private Chat(String title, String url, List<Turn> turns, List<String> images) {
        this.title = title;
        this.url = url;
        this.turns = turns;
        this.images = images;
    }

    static Chat fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        List<Turn> turns = new ArrayList<>();
        JSONArray jsonTurns = root.getJSONArray("turns");
        for (int i = 0; i < jsonTurns.length(); i++) {
            JSONObject t = jsonTurns.getJSONObject(i);
            turns.add(new Turn(t.optString("role", ROLE_MODEL), t.optString("html"), t.optString("md")));
        }
        List<String> images = new ArrayList<>();
        JSONArray jsonImages = root.optJSONArray("images");
        if (jsonImages != null) {
            for (int i = 0; i < jsonImages.length(); i++) {
                images.add(jsonImages.optString(i));
            }
        }
        String url = root.optString("url");
        return new Chat(pickTitle(root.optString("title"), turns), url.isEmpty() ? null : url, turns, images);
    }

    static Chat fromText(String text, String subject) {
        StringBuilder html = new StringBuilder();
        for (String paragraph : text.trim().split("\\n\\s*\\n")) {
            html.append("<p>").append(TextUtils.htmlEncode(paragraph.trim()).replace("\n", "<br>")).append("</p>");
        }
        List<Turn> turns = new ArrayList<>();
        turns.add(new Turn(ROLE_TEXT, html.toString(), text.trim()));
        return new Chat(pickTitle(subject, turns), null, turns, new ArrayList<>());
    }

    int countRole(String role) {
        int n = 0;
        for (Turn t : turns) {
            if (t.role.equals(role)) n++;
        }
        return n;
    }

    private static String pickTitle(String title, List<Turn> turns) {
        if (title != null && !title.trim().isEmpty()) return title.trim();
        for (Turn t : turns) {
            if (t.role.equals(ROLE_MODEL)) continue;
            String plain = t.markdown
                    .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", " ")
                    .replaceAll("[#*_`>\\[\\]|~-]+", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (!plain.isEmpty()) {
                return plain.length() > 60 ? plain.substring(0, 60).trim() + "…" : plain;
            }
        }
        return "Чат Gemini";
    }
}
