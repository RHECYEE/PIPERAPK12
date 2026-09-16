package dev.piper.mvp;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Unpacks bundled assets to real files. espeak-ng needs a filesystem directory
 * (espeak_Initialize takes a path) and onnxruntime opens the model by path, so neither
 * can read straight out of the APK.
 */
final class AssetInstaller {

    static final String TAG = "PiperAssets";

    private static final String ESPEAK_DATA_DIR = "espeak-ng-data";
    private static final String VOICES_DIR = "voices";
    private static final String STAMP = ".installed-v1";

    private final Context context;

    AssetInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    File espeakDataDir() {
        return new File(context.getFilesDir(), ESPEAK_DATA_DIR);
    }

    File voicesDir() {
        return new File(context.getFilesDir(), VOICES_DIR);
    }

    /** Idempotent: does the copy once, then short-circuits on the stamp file. */
    void installIfNeeded() throws IOException {
        File stamp = new File(context.getFilesDir(), STAMP);
        if (stamp.exists()) {
            Log.i(TAG, "assets already installed at " + context.getFilesDir());
            return;
        }
        long started = System.currentTimeMillis();
        int files = copyAssetDir(ESPEAK_DATA_DIR, espeakDataDir());
        files += copyAssetDir(VOICES_DIR, voicesDir());
        if (!stamp.createNewFile()) {
            Log.w(TAG, "could not create install stamp " + stamp);
        }
        Log.i(TAG, "installed " + files + " asset file(s) in "
                + (System.currentTimeMillis() - started) + " ms");
    }

    private int copyAssetDir(String assetPath, File target) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(assetPath);
        if (entries == null || entries.length == 0) {
            copyAssetFile(assetPath, target);
            return 1;
        }
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("cannot create " + target);
        }
        int count = 0;
        for (String entry : entries) {
            count += copyAssetDir(assetPath + "/" + entry, new File(target, entry));
        }
        return count;
    }

    private void copyAssetFile(String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = context.getAssets().open(assetPath);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
        }
    }
}
