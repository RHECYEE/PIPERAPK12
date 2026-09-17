package dev.piper.mvp;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Enumerates the Piper voices on disk: the bundled one plus any the user imported. */
final class VoiceRepository {

    static final String TAG = "PiperVoices";

    /** A Piper voice is always a {@code <name>.onnx} plus its {@code <name>.onnx.json}. */
    static final class Voice {
        final String id;
        final File model;
        final File config;

        Voice(String id, File model, File config) {
            this.id = id;
            this.model = model;
            this.config = config;
        }

        String displayName() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    private final Context context;
    private final File voicesDir;

    VoiceRepository(Context context, File voicesDir) {
        this.context = context.getApplicationContext();
        this.voicesDir = voicesDir;
    }

    List<Voice> list() {
        List<Voice> voices = new ArrayList<>();
        File[] files = voicesDir.listFiles();
        if (files == null) {
            return voices;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".onnx")) {
                continue;
            }
            File config = new File(file.getParentFile(), file.getName() + ".json");
            if (!config.isFile()) {
                Log.w(TAG, "skipping " + file.getName() + ": missing " + config.getName());
                continue;
            }
            String id = file.getName().substring(0, file.getName().length() - ".onnx".length());
            voices.add(new Voice(id, file, config));
        }
        Collections.sort(voices, (a, b) -> a.id.compareToIgnoreCase(b.id));
        return voices;
    }

    Voice findById(String id) {
        if (id == null) {
            return null;
        }
        for (Voice voice : list()) {
            if (voice.id.equals(id)) {
                return voice;
            }
        }
        return null;
    }

    /** What an import produced, so the caller can select the new voice and explain gaps. */
    static final class ImportResult {
        final List<String> completed = new ArrayList<>();   // voices now usable
        final List<String> missingConfig = new ArrayList<>(); // .onnx with no .onnx.json
        final List<String> ignored = new ArrayList<>();     // not Piper voice files
        int filesCopied;
    }

    /** Copies picked .onnx / .onnx.json documents into the app's voices directory. */
    ImportResult importFrom(List<Uri> uris) {
        ImportResult result = new ImportResult();
        int imported = 0;
        for (Uri uri : uris) {
            String name = displayName(uri);
            if (name == null || (!name.endsWith(".onnx") && !name.endsWith(".onnx.json"))) {
                Log.w(TAG, "ignoring import (not a Piper voice file): " + name);
                result.ignored.add(String.valueOf(name));
                continue;
            }
            File target = new File(voicesDir, name);
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    throw new IOException("cannot open " + uri);
                }
                byte[] buf = new byte[64 * 1024];
                int read;
                long total = 0;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                    total += read;
                }
                imported++;
                Log.i(TAG, "imported " + name + " (" + total + " bytes)");
            } catch (IOException e) {
                Log.e(TAG, "import failed for " + name, e);
            }
        }
        result.filesCopied = imported;

        // Work out which of the touched voices are now complete. A .onnx without its
        // .onnx.json is unusable and used to be dropped silently from the voice list,
        // which looked exactly like "the import did nothing".
        for (Uri uri : uris) {
            String name = displayName(uri);
            if (name == null || !name.endsWith(".onnx")) {
                continue;
            }
            String id = name.substring(0, name.length() - ".onnx".length());
            if (findById(id) != null) {
                if (!result.completed.contains(id)) {
                    result.completed.add(id);
                }
            } else if (!result.missingConfig.contains(id)) {
                result.missingConfig.add(id);
            }
        }
        Log.i(TAG, "import: copied=" + imported + " usable=" + result.completed
                + " missingConfig=" + result.missingConfig + " ignored=" + result.ignored);
        return result;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = context.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read display name for " + uri, e);
        }
        String path = uri.getLastPathSegment();
        return path == null ? null : new File(path).getName();
    }
}
