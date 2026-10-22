package io.github.hpjgyfryfuj.geminiexporter;

import android.content.ContentResolver;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.webkit.CookieManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns a {@link Chat} into Markdown or printable HTML, with its images. */
final class ChatExporter {

    static final class Image {
        final byte[] data;
        final String mime;
        final String extension;

        Image(byte[] data, String mime, String extension) {
            this.data = data;
            this.mime = mime;
            this.extension = extension;
        }
    }

    static final class Saved {
        final Uri uri;
        final String mime;
        /** Where the user finds the file, e.g. "Загрузки/Gemini Exporter/Чат.pdf". */
        final String location;

        Saved(Uri uri, String mime, String location) {
            this.uri = uri;
            this.mime = mime;
            this.location = location;
        }
    }

    static final String MARKDOWN_MIME = "text/markdown";

    private static final Pattern HTML_IMAGE = Pattern.compile("<img src=\"\\{\\{GEIMG:(\\d+)}}\" alt=\"\">");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[]\\(\\{\\{GEIMG:(\\d+)}}\\)");
    private static final int MAX_IMAGE_BYTES = 25 * 1024 * 1024;
    private static final Locale RUSSIAN = Locale.forLanguageTag("ru");

    private static final String CSS = ""
            + "body{font-family:sans-serif;font-size:11pt;line-height:1.45;color:#1f1f1f;margin:0;"
            + "overflow-wrap:anywhere}"
            + "h1.title{font-size:18pt;margin:0 0 4pt}"
            + ".meta{color:#5f6368;font-size:9pt;margin-bottom:18pt}"
            + ".turn{margin:0 0 16pt}"
            + ".role{font-weight:bold;font-size:10pt;margin-bottom:4pt;color:#5f6368}"
            + ".turn.model .role,.turn.text .role{color:#1a73e8}"
            + ".turn.user .content{background:#eef3fd;border-radius:10pt;padding:6pt 12pt}"
            + ".content>:first-child,.content>div>:first-child{margin-top:0}"
            + "img{display:block;max-width:100%;max-height:220mm;height:auto;margin:6pt 0;border-radius:6pt;"
            + "page-break-inside:avoid}"
            + "pre{white-space:pre-wrap;background:#f1f3f4;padding:8pt;border-radius:6pt;font-size:9pt;margin:0}"
            + ".code{margin:8pt 0}.lang{font-size:8pt;color:#5f6368;margin-bottom:2pt}"
            + "code{font-family:monospace;background:#f1f3f4;padding:0 2pt;border-radius:3pt}"
            + "pre code{background:none;padding:0}"
            + "table{border-collapse:collapse;margin:8pt 0;font-size:9.5pt}"
            + "th,td{border:1px solid #dadce0;padding:4pt 6pt;vertical-align:top;text-align:left}"
            + "th{background:#f1f3f4}"
            + "blockquote{border-left:3pt solid #dadce0;margin:8pt 0;padding-left:10pt;color:#3c4043}"
            + "a{color:#1a73e8}";

    private ChatExporter() {
    }

    // ---- images ----

    /** Downloads or decodes every image; an entry is null where that failed. Call off the main thread. */
    static List<Image> loadImages(List<String> sources, String userAgent) {
        List<Image> images = new ArrayList<>();
        for (String source : sources) {
            Image image = null;
            try {
                image = loadImage(source, userAgent);
            } catch (IOException | RuntimeException ignored) {
                // Reported to the user as a missing image.
            }
            images.add(image);
        }
        return images;
    }

    static int countMissing(List<Image> images) {
        int missing = 0;
        for (Image image : images) {
            if (image == null) missing++;
        }
        return missing;
    }

    private static Image loadImage(String source, String userAgent) throws IOException {
        if (source.startsWith("data:")) {
            int comma = source.indexOf(',');
            if (comma < 0) return null;
            String meta = source.substring(5, comma);
            String payload = source.substring(comma + 1);
            byte[] data = meta.endsWith(";base64")
                    ? Base64.decode(payload, Base64.DEFAULT)
                    : Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
            return toImage(data, meta.split(";")[0]);
        }
        if (!source.startsWith("https://") && !source.startsWith("http://")) return null;

        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(source);
            if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            if (connection.getResponseCode() / 100 != 2) return null;
            try (InputStream in = connection.getInputStream()) {
                return toImage(readAll(in), connection.getContentType());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_IMAGE_BYTES) throw new IOException("Image too large");
        }
        return out.toByteArray();
    }

    /** Trusts the file signature over the declared type; returns null for anything that is not an image. */
    private static Image toImage(byte[] d, String declaredMime) {
        if (d.length >= 4 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return new Image(d, "image/png", "png");
        }
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return new Image(d, "image/jpeg", "jpg");
        }
        if (d.length >= 3 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F') {
            return new Image(d, "image/gif", "gif");
        }
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return new Image(d, "image/webp", "webp");
        }
        if (declaredMime != null && declaredMime.toLowerCase(Locale.ROOT).startsWith("image/svg")) {
            return new Image(d, "image/svg+xml", "svg");
        }
        return null;
    }

    /** What a token points at: the saved file, else the original web address, else nothing. */
    private static List<String> imageRefs(Chat chat, List<Image> images, List<String> fileNames) {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < chat.images.size(); i++) {
            String source = chat.images.get(i);
            if (images.get(i) != null) {
                refs.add(fileNames.get(i));
            } else if (source.startsWith("https://") || source.startsWith("http://")) {
                refs.add(source);
            } else {
                refs.add(null);
            }
        }
        return refs;
    }

    private static List<String> imageFileNames(List<Image> images) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            Image image = images.get(i);
            names.add(image == null ? null : String.format(Locale.ROOT, "image-%02d.%s", i + 1, image.extension));
        }
        return names;
    }

    private static String replaceImages(String text, Pattern pattern, List<String> refs, boolean html) {
        Matcher m = pattern.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int index = Integer.parseInt(m.group(1));
            String ref = index < refs.size() ? refs.get(index) : null;
            String replacement;
            if (ref == null) {
                replacement = "";
            } else if (html) {
                replacement = "<img src=\"" + TextUtils.htmlEncode(ref) + "\" alt=\"\">";
            } else {
                replacement = "![](" + ref.replace(" ", "%20").replace(")", "%29") + ")";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ---- documents ----

    /** A file name without extension: the chat title plus the export time. */
    static String fileBase(Chat chat, Date when) {
        String title = chat.title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (title.codePointCount(0, title.length()) > 60) {
            title = title.substring(0, title.offsetByCodePoints(0, 60)).trim();
        }
        if (title.isEmpty()) title = "Чат Gemini";
        return title + " " + new SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.ROOT).format(when);
    }

    private static String exportedOn(Date when) {
        return new SimpleDateFormat("d MMMM yyyy, HH:mm", RUSSIAN).format(when);
    }

    private static String roleLabel(String role) {
        switch (role) {
            case Chat.ROLE_USER:
                return "Вы";
            case Chat.ROLE_PAGE:
                return null;
            default:
                return "Gemini";
        }
    }

    static String buildHtml(Chat chat, List<String> imageRefs, Date when) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><title>")
                .append(TextUtils.htmlEncode(chat.title))
                .append("</title><style>").append(CSS).append("</style></head><body>")
                .append("<h1 class=\"title\">").append(TextUtils.htmlEncode(chat.title)).append("</h1>")
                .append("<div class=\"meta\">Экспорт из Gemini · ").append(TextUtils.htmlEncode(exportedOn(when)));
        if (chat.url != null) {
            String url = TextUtils.htmlEncode(chat.url);
            sb.append(" · <a href=\"").append(url).append("\">").append(url).append("</a>");
        }
        sb.append("</div>");
        for (Chat.Turn turn : chat.turns) {
            sb.append("<div class=\"turn ").append(turn.role).append("\">");
            String label = roleLabel(turn.role);
            if (label != null) sb.append("<div class=\"role\">").append(label).append("</div>");
            sb.append("<div class=\"content\">")
                    .append(replaceImages(turn.html, HTML_IMAGE, imageRefs, true))
                    .append("</div></div>");
        }
        return sb.append("</body></html>").toString();
    }

    static String buildMarkdown(Chat chat, List<String> imageRefs, Date when) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(chat.title.replace('\n', ' ')).append("\n\n");
        sb.append("*Экспорт из Gemini · ").append(exportedOn(when));
        if (chat.url != null) sb.append(" · [ссылка на чат](").append(chat.url).append(")");
        sb.append("*\n\n");
        for (Chat.Turn turn : chat.turns) {
            if (Chat.ROLE_USER.equals(turn.role)) {
                sb.append("## 👤 Вы\n\n");
            } else if (!Chat.ROLE_PAGE.equals(turn.role)) {
                sb.append("## ✨ Gemini\n\n");
            }
            sb.append(replaceImages(turn.markdown, MARKDOWN_IMAGE, imageRefs, false).trim()).append("\n\n");
        }
        return sb.toString().trim() + "\n";
    }

    /** Writes the printable page and its images into {@code dir}; returns the page. */
    static File writePrintPage(File dir, Chat chat, List<Image> images, Date when) throws IOException {
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        List<String> names = imageFileNames(images);
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i) != null) writeFile(new File(dir, names.get(i)), images.get(i).data);
        }
        File page = new File(dir, "chat.html");
        String html = buildHtml(chat, imageRefs(chat, images, names), when);
        writeFile(page, html.getBytes(StandardCharsets.UTF_8));
        return page;
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    /**
     * Saves the chat as Markdown. With images it becomes a folder holding the .md file
     * and an images/ subfolder, so the relative links keep working.
     */
    static Saved saveMarkdown(ContentResolver resolver, Chat chat, List<Image> images, Date when)
            throws IOException {
        String base = fileBase(chat, when);
        boolean hasImages = countMissing(images) < images.size();
        String folder = hasImages ? base : null;

        List<String> names = imageFileNames(images);
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            Image image = images.get(i);
            if (image != null) {
                Downloads.save(resolver, folder + "/images", names.get(i), image.mime, image.data);
                paths.add("images/" + names.get(i));
            } else {
                paths.add(null);
            }
        }

        String name = base + ".md";
        byte[] markdown = buildMarkdown(chat, imageRefs(chat, images, paths), when).getBytes(StandardCharsets.UTF_8);
        Uri uri = Downloads.save(resolver, folder, name, MARKDOWN_MIME, markdown);
        String location = Downloads.DISPLAY_FOLDER + "/" + (folder != null ? folder + "/" : "") + name;
        return new Saved(uri, MARKDOWN_MIME, location);
    }
}
