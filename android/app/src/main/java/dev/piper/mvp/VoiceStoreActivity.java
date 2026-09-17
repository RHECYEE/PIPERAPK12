package dev.piper.mvp;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * The English female voices from piper-voices v1.0.0, downloadable on demand.
 * Tap to download, tap an installed voice to use it, long press to delete.
 */
public final class VoiceStoreActivity extends Activity implements VoiceDownloader.Listener {

    static final String TAG = "PiperVoiceStore";

    private final List<VoiceCatalog.Voice> catalog = VoiceCatalog.englishFemale();

    private AppSettings settings;
    private VoiceRepository voices;
    private VoiceDownloader downloader;
    private File voicesDir;
    private ArrayAdapter<VoiceCatalog.Voice> adapter;

    private String downloadingId;
    private long downloadedBytes;
    private long downloadTotalBytes;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_voice_store);

        settings = new AppSettings(this);
        voicesDir = new AssetInstaller(this).voicesDir();
        voices = new VoiceRepository(this, voicesDir);
        downloader = new VoiceDownloader(voicesDir);

        ((TextView) findViewById(R.id.voiceStoreHeader)).setText(String.format(Locale.US,
                "%d English female voices from piper-voices v1.0.0. "
                        + "Downloaded once, then used entirely offline.", catalog.size()));

        ListView list = findViewById(R.id.voiceList);
        adapter = new ArrayAdapter<VoiceCatalog.Voice>(this,
                android.R.layout.simple_list_item_2, android.R.id.text1, catalog) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                VoiceCatalog.Voice voice = catalog.get(position);
                ((TextView) row.findViewById(android.R.id.text1)).setText(voice.displayName);
                ((TextView) row.findViewById(android.R.id.text2)).setText(statusOf(voice));
                return row;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> onVoiceTapped(catalog.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            confirmDelete(catalog.get(position));
            return true;
        });
    }

    private String statusOf(VoiceCatalog.Voice voice) {
        if (voice.id.equals(downloadingId)) {
            int percent = downloadTotalBytes > 0
                    ? (int) (100L * downloadedBytes / downloadTotalBytes) : 0;
            return "Downloading… " + percent + "%";
        }
        boolean installed = isInstalled(voice);
        String detail = voice.sizeLabel() + " · " + voice.sampleRate + " Hz";
        if (!installed) {
            return detail + " · tap to download";
        }
        return voice.id.equals(settings.voiceId())
                ? detail + " · installed, in use"
                : detail + " · installed, tap to use";
    }

    private boolean isInstalled(VoiceCatalog.Voice voice) {
        return voices.findById(voice.id) != null;
    }

    private void onVoiceTapped(VoiceCatalog.Voice voice) {
        if (downloadingId != null) {
            Toast.makeText(this, "Already downloading " + downloadingId, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isInstalled(voice)) {
            settings.setVoiceId(voice.id);
            Log.i(TAG, "selected installed voice " + voice.id);
            Toast.makeText(this, voice.displayName + " selected", Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
            return;
        }
        downloadingId = voice.id;
        downloadedBytes = 0;
        downloadTotalBytes = voice.totalBytes();
        adapter.notifyDataSetChanged();
        Log.i(TAG, "starting download of " + voice.id);
        downloader.download(voice, this);
    }

    private void confirmDelete(VoiceCatalog.Voice voice) {
        if (!isInstalled(voice)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + voice.displayName + "?")
                .setMessage("Frees " + voice.sizeLabel() + ". You can download it again later.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialog, which) -> {
                    deleteVoice(voice);
                    adapter.notifyDataSetChanged();
                })
                .show();
    }

    private void deleteVoice(VoiceCatalog.Voice voice) {
        boolean removed = new File(voicesDir, voice.id + ".onnx").delete();
        removed |= new File(voicesDir, voice.id + ".onnx.json").delete();
        Log.i(TAG, "deleted " + voice.id + " -> " + removed);
        if (voice.id.equals(settings.voiceId())) {
            // Fall back to whatever is still on disk so the app keeps a working voice.
            List<VoiceRepository.Voice> remaining = voices.list();
            if (!remaining.isEmpty()) {
                settings.setVoiceId(remaining.get(0).id);
                Log.i(TAG, "active voice deleted, falling back to " + remaining.get(0).id);
            }
        }
    }

    @Override
    public void onProgress(String voiceId, long bytesDone, long bytesTotal) {
        downloadedBytes = bytesDone;
        downloadTotalBytes = bytesTotal;
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onFinished(String voiceId, boolean ok, String message) {
        downloadingId = null;
        adapter.notifyDataSetChanged();
        if (ok) {
            // Downloading a voice means wanting to use it.
            settings.setVoiceId(voiceId);
            Toast.makeText(this, voiceId + " ready", Toast.LENGTH_SHORT).show();
            adapter.notifyDataSetChanged();
        } else {
            Toast.makeText(this, "Download failed: " + message, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        downloader.shutdown();
    }
}
