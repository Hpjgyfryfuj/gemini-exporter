package io.github.hpjgyfryfuj.geminiexporter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;

/** Files in Download/Gemini Exporter, written through MediaStore so no permission is needed. */
final class Downloads {

    static final String FOLDER = "Gemini Exporter";
    /** How the folder is named to the user. */
    static final String DISPLAY_FOLDER = "Загрузки/" + FOLDER;

    private Downloads() {
    }

    /** Creates a hidden (pending) entry; call {@link #publish} once it is written. */
    static Uri create(ContentResolver resolver, String subFolder, String name, String mime) throws IOException {
        String path = Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER;
        if (subFolder != null) path += "/" + subFolder;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("MediaStore refused to create " + name);
        return uri;
    }

    static void write(ContentResolver resolver, Uri uri, byte[] data) throws IOException {
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("Cannot open " + uri);
            out.write(data);
        }
    }

    static void publish(ContentResolver resolver, Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
    }

    static void delete(ContentResolver resolver, Uri uri) {
        try {
            resolver.delete(uri, null, null);
        } catch (RuntimeException ignored) {
            // Nothing useful to do; the pending entry is cleaned up by the system eventually.
        }
    }

    static Uri save(ContentResolver resolver, String subFolder, String name, String mime, byte[] data)
            throws IOException {
        Uri uri = create(resolver, subFolder, name, mime);
        try {
            write(resolver, uri, data);
        } catch (IOException | RuntimeException e) {
            delete(resolver, uri);
            throw e;
        }
        publish(resolver, uri);
        return uri;
    }
}
