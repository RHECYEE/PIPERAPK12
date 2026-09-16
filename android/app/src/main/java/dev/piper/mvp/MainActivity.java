package dev.piper.mvp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

import java.util.Locale;

/** The single playback screen: paste text, play, pause/resume, jump +/-10 s. */
public final class MainActivity extends Activity implements PiperEngine.Listener {

    static final String TAG = "PiperMain";

    private static final double SEEK_SECONDS = 10.0;
    private static final long TICK_MS = 200L;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private EditText textInput;
    private TextView statusText;
    private TextView positionText;
    private SeekBar progressBar;
    private Button playButton;
    private Button pauseButton;

    private AppSettings settings;
    private AssetInstaller assets;
    private VoiceRepository voices;
    private PiperEngine engine;
    private PlaybackEngine playback;

    private String synthesizedText;
    private String synthesizedFingerprint;
    private String loadedVoiceId;
    private int numSpeakers = 1;
    private String[] speakerNames = new String[0];
    private boolean synthesisRunning;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textInput = findViewById(R.id.textInput);
        statusText = findViewById(R.id.statusText);
        positionText = findViewById(R.id.positionText);
        progressBar = findViewById(R.id.progressBar);
        playButton = findViewById(R.id.playButton);
        pauseButton = findViewById(R.id.pauseButton);

        settings = new AppSettings(this);
        assets = new AssetInstaller(this);
        voices = new VoiceRepository(this, assets.voicesDir());
        engine = new PiperEngine();
        engine.setListener(this);

        playButton.setOnClickListener(v -> onPlay());
        pauseButton.setOnClickListener(v -> onPauseResume());
        findViewById(R.id.backButton).setOnClickListener(v -> onSeek(-SEEK_SECONDS));
        findViewById(R.id.forwardButton).setOnClickListener(v -> onSeek(SEEK_SECONDS));
        findViewById(R.id.settingsButton).setOnClickListener(v -> openSettings());

        textInput.setText("Piper is a fast, local neural text to speech system. "
                + "This build runs it entirely on the device. "
                + "Use the ten second buttons to jump around the generated audio.");

        bootstrap();
    }

    private void bootstrap() {
        setStatus(getString(R.string.starting));
        new Thread(() -> {
            try {
                assets.installIfNeeded();
                ui.post(this::loadSelectedVoice);
            } catch (Exception e) {
                Log.e(TAG, "asset install failed", e);
                ui.post(() -> onError("Could not unpack bundled data: " + e.getMessage(), e));
            }
        }, "piper-assets").start();
    }

    private void loadSelectedVoice() {
        // A voice change invalidates anything still being synthesized: loading a new
        // voice can replace the PlaybackEngine, and the run in flight holds a reference
        // to the old one. Without this, its completion would mark the new engine's
        // stream finished.
        engine.cancelSynthesis();
        synthesisRunning = false;
        VoiceRepository.Voice voice = voices.findById(settings.voiceId());
        if (voice == null) {
            java.util.List<VoiceRepository.Voice> all = voices.list();
            if (all.isEmpty()) {
                onError("No Piper voice found in " + assets.voicesDir(), null);
                return;
            }
            voice = all.get(0);
            settings.setVoiceId(voice.id);
        }
        Log.i(TAG, "loading voice " + voice.id);
        if (engine.isReady()) {
            engine.loadVoice(voice);
        } else {
            engine.initAndLoad(assets.espeakDataDir(), voice);
        }
    }

    @Override
    public void onVoiceLoaded(VoiceRepository.Voice voice, int sampleRate, int speakers,
                              float[] modelDefaults, String[] names) {
        settings.seedFromModelDefaultsIfUnset(modelDefaults);
        loadedVoiceId = voice.id;
        numSpeakers = speakers;
        speakerNames = names;

        if (playback == null || playback.sampleRate() != sampleRate) {
            if (playback != null) {
                playback.release();
            }
            playback = new PlaybackEngine(sampleRate);
            playback.setListener(state -> ui.post(this::syncButtons));
            synthesizedText = null;
            synthesizedFingerprint = null;
        }
        playback.setVolume(settings.volume());
        setStatus(voice.displayName() + " · " + sampleRate + " Hz"
                + (speakers > 1 ? " · " + speakers + " speakers" : ""));
        syncButtons();
        Log.i(TAG, "voice ready: " + voice.id + " settings{" + settings.describe() + "}");
    }

    private void onPlay() {
        if (playback == null) {
            Toast.makeText(this, "Piper is still starting", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = textInput.getText().toString().trim();
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter some text first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (text.equals(synthesizedText)
                && settings.synthesisFingerprint().equals(synthesizedFingerprint)
                && playback.totalFrames() > 0) {
            // Same text and same synthesis settings: the cached audio is still correct,
            // so play from wherever we are instead of regenerating.
            Log.i(TAG, "play: reusing cached audio, frames=" + playback.totalFrames());
            playback.play();
            startTicker();
            return;
        }
        startSynthesis(text);
    }

    private void startSynthesis(String text) {
        engine.cancelSynthesis();
        playback.reset();
        synthesizedText = text;
        synthesizedFingerprint = settings.synthesisFingerprint();
        synthesisRunning = true;
        setStatus("Synthesizing…");
        engine.synthesize(text, settings, playback);
        playback.play();
        startTicker();
    }

    private void onPauseResume() {
        if (playback == null) {
            return;
        }
        playback.togglePlayPause();
        syncButtons();
    }

    private void onSeek(double seconds) {
        if (playback == null) {
            return;
        }
        double before = playback.positionSeconds();
        playback.seekBy(seconds);
        double after = playback.positionSeconds();
        Log.i(TAG, String.format(Locale.US, "seek %+.0fs: %.3fs -> %.3fs (duration %.3fs)",
                seconds, before, after, playback.durationSeconds()));
        updateProgress();
    }

    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra(SettingsActivity.EXTRA_MODEL_DEFAULTS, engine.modelDefaults());
        intent.putExtra(SettingsActivity.EXTRA_NUM_SPEAKERS, numSpeakers);
        intent.putExtra(SettingsActivity.EXTRA_SPEAKER_NAMES, speakerNames);
        intent.putExtra(SettingsActivity.EXTRA_SAMPLE_RATE,
                playback != null ? playback.sampleRate() : 0);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (playback != null) {
            playback.setVolume(settings.volume());
        }
        String selected = settings.voiceId();
        if (selected != null && !selected.equals(loadedVoiceId) && engine.isReady()) {
            Log.i(TAG, "voice changed in settings: " + loadedVoiceId + " -> " + selected);
            loadSelectedVoice();
        }
        startTicker();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacks(ticker);
        if (playback != null) {
            playback.release();
        }
        engine.shutdown();
    }

    @Override
    public void onSynthesisChunk(int chunkSamples, long totalFrames) {
        updateProgress();
    }

    @Override
    public void onSynthesisFinished(int totalSamples, boolean cancelled) {
        synthesisRunning = false;
        if (playback != null) {
            playback.setProducerFinished(true);
        }
        if (totalSamples < 0) {
            setStatus("Synthesis failed — see logcat (tag PiperNative)");
            return;
        }
        VoiceRepository.Voice voice = engine.currentVoice();
        setStatus((voice != null ? voice.displayName() : "voice") + " · "
                + String.format(Locale.US, "%.1f s generated", playback.durationSeconds())
                + (cancelled ? " (cancelled)" : ""));
        updateProgress();
    }

    @Override
    public void onError(String message, Throwable cause) {
        Log.e(TAG, "error: " + message, cause);
        setStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void startTicker() {
        ui.removeCallbacks(ticker);
        ui.post(ticker);
    }

    private void updateProgress() {
        if (playback == null) {
            return;
        }
        double position = playback.positionSeconds();
        double duration = playback.durationSeconds();
        positionText.setText(formatTime(position) + " / " + formatTime(duration)
                + (synthesisRunning ? " (generating…)" : ""));
        int progress = duration > 0 ? (int) Math.round(1000.0 * position / duration) : 0;
        progressBar.setProgress(Math.max(0, Math.min(1000, progress)));
        syncButtons();
    }

    private void syncButtons() {
        if (playback == null) {
            return;
        }
        PlaybackEngine.State state = playback.state();
        pauseButton.setText(state == PlaybackEngine.State.PAUSED
                ? R.string.resume : R.string.pause);
        // Nothing to pause or resume until there is audio.
        pauseButton.setEnabled(state == PlaybackEngine.State.PLAYING
                || state == PlaybackEngine.State.PAUSED);
    }

    private void setStatus(String text) {
        statusText.setText(text);
    }

    private static String formatTime(double seconds) {
        if (seconds < 0 || Double.isNaN(seconds)) {
            seconds = 0;
        }
        int total = (int) seconds;
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }
}
