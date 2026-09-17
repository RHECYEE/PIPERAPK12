package dev.piper.mvp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fetches catalogue voices into the app's voices directory.
 *
 * <p>This is the only part of the app that touches the network, and it only ever runs
 * when the user asks for a specific voice. Synthesis itself stays entirely offline.
 */
final class VoiceDownloader {

    static final String TAG = "PiperDownload";

    interface Listener {
        void onProgress(String voiceId, long bytesDone, long bytesTotal);

        void onFinished(String voiceId, boolean ok, String message);
    }

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "piper-download"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final File voicesDir;

    VoiceDownloader(File voicesDir) {
        this.voicesDir = voicesDir;
    }

    void cancel() {
        cancelled.set(true);
    }

    void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    void download(VoiceCatalog.Voice voice, Listener listener) {
        cancelled.set(false);
        executor.execute(() -> {
            File model = new File(voicesDir, voice.id + ".onnx");
            File config = new File(voicesDir, voice.id + ".onnx.json");
            long total = voice.totalBytes();
            Log.i(TAG, "downloading " + voice.id + " (" + total + " bytes)");
            try {
                if (!voicesDir.exists() && !voicesDir.mkdirs()) {
                    throw new IOException("cannot create " + voicesDir);
                }
                // Config first: it is tiny, and a model without it is unusable.
                fetch(voice.configUrl(), config, 0L, total, voice.id, listener);
                fetch(voice.modelUrl(), model, voice.configBytes, total, voice.id, listener);
                Log.i(TAG, "downloaded " + voice.id);
                finish(listener, voice.id, true, null);
            } catch (Throwable t) {
                // Never leave half a voice behind: the app would list it as usable.
                deleteQuietly(new File(voicesDir, voice.id + ".onnx.part"));
                deleteQuietly(new File(voicesDir, voice.id + ".onnx.json.part"));
                deleteQuietly(model);
                deleteQuietly(config);
                boolean wasCancelled = cancelled.get();
                Log.e(TAG, "download failed for " + voice.id, t);
                finish(listener, voice.id, false,
                        wasCancelled ? "Cancelled" : String.valueOf(t.getMessage()));
            }
        });
    }

    private void fetch(String url, File target, long alreadyDone, long grandTotal,
                       String voiceId, Listener listener) throws IOException {
        File part = new File(target.getPath() + ".part");
        HttpURLConnection connection = open(url, 0);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[128 * 1024];
            long done = 0;
            long lastReport = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                if (cancelled.get()) {
                    throw new IOException("cancelled");
                }
                out.write(buffer, 0, read);
                done += read;
                if (done - lastReport > 1_000_000L) {
                    lastReport = done;
                    long soFar = alreadyDone + done;
                    main.post(() -> listener.onProgress(voiceId, soFar, grandTotal));
                }
            }
        } finally {
            connection.disconnect();
        }
        if (!part.renameTo(target)) {
            throw new IOException("cannot move " + part + " into place");
        }
    }

    /** HuggingFace redirects downloads to a CDN, so follow redirects explicitly. */
    private HttpURLConnection open(String url, int depth) throws IOException {
        if (depth > 5) {
            throw new IOException("too many redirects for " + url);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(false);
        int code = connection.getResponseCode();
        if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null) {
                throw new IOException("redirect with no Location for " + url);
            }
            return open(new URL(new URL(url), location).toString(), depth + 1);
        }
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("HTTP " + code + " for " + url);
        }
        return connection;
    }

    private void finish(Listener listener, String voiceId, boolean ok, String message) {
        main.post(() -> listener.onFinished(voiceId, ok, message));
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "could not delete " + file);
        }
    }
}
