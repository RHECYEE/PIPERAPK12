package dev.piper.mvp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;


import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exposes only parameters the fork's engine genuinely reads.
 *
 * <p>noise_scale, length_scale and noise_w are the three floats piper.cpp packs into the
 * model's {@code scales} tensor; speaker id is the {@code sid} tensor; sentence silence
 * is the run of zero samples textToAudio() appends between sentences. Volume is applied
 * by AudioTrack because Piper has no gain parameter of its own.
 */
public final class SettingsActivity extends Activity {

    static final String TAG = "PiperSettingsUi";

    static final String EXTRA_MODEL_DEFAULTS = "model_defaults";
    static final String EXTRA_NUM_SPEAKERS = "num_speakers";
    static final String EXTRA_SPEAKER_NAMES = "speaker_names";
    static final String EXTRA_SAMPLE_RATE = "sample_rate";

    private static final int REQUEST_IMPORT_VOICE = 1001;

    private AppSettings settings;
    private VoiceRepository voices;
    private float[] modelDefaults;
    private int numSpeakers = 1;
    private String[] speakerNames = new String[0];

    private Spinner voiceSpinner;
    private Spinner speakerSpinner;
    private List<VoiceRepository.Voice> voiceList = new ArrayList<>();

    private SliderSetting lengthScale;
    private SliderSetting noiseScale;
    private SliderSetting noiseW;
    private SliderSetting sentenceSilence;
    private SliderSetting volume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = new AppSettings(this);
        voices = new VoiceRepository(this, new AssetInstaller(this).voicesDir());

        modelDefaults = getIntent().getFloatArrayExtra(EXTRA_MODEL_DEFAULTS);
        numSpeakers = getIntent().getIntExtra(EXTRA_NUM_SPEAKERS, 1);
        String[] names = getIntent().getStringArrayExtra(EXTRA_SPEAKER_NAMES);
        if (names != null) {
            speakerNames = names;
        }

        voiceSpinner = findViewById(R.id.voiceSpinner);
        speakerSpinner = findViewById(R.id.speakerSpinner);

        lengthScale = new SliderSetting(findViewById(R.id.lengthScaleBar),
                findViewById(R.id.lengthScaleLabel), getString(R.string.length_scale),
                AppSettings.MIN_LENGTH_SCALE, AppSettings.MAX_LENGTH_SCALE,
                settings.lengthScale(), settings::setLengthScale);
        noiseScale = new SliderSetting(findViewById(R.id.noiseScaleBar),
                findViewById(R.id.noiseScaleLabel), getString(R.string.noise_scale),
                AppSettings.MIN_NOISE_SCALE, AppSettings.MAX_NOISE_SCALE,
                settings.noiseScale(), settings::setNoiseScale);
        noiseW = new SliderSetting(findViewById(R.id.noiseWBar),
                findViewById(R.id.noiseWLabel), getString(R.string.noise_w),
                AppSettings.MIN_NOISE_W, AppSettings.MAX_NOISE_W,
                settings.noiseW(), settings::setNoiseW);
        sentenceSilence = new SliderSetting(findViewById(R.id.sentenceSilenceBar),
                findViewById(R.id.sentenceSilenceLabel), getString(R.string.sentence_silence),
                AppSettings.MIN_SENTENCE_SILENCE, AppSettings.MAX_SENTENCE_SILENCE,
                settings.sentenceSilence(), settings::setSentenceSilence);
        volume = new SliderSetting(findViewById(R.id.volumeBar),
                findViewById(R.id.volumeLabel), getString(R.string.volume),
                0f, 1f, settings.volume(), settings::setVolume);

        findViewById(R.id.downloadVoicesButton).setOnClickListener(v ->
                startActivity(new Intent(this, VoiceStoreActivity.class)));
        findViewById(R.id.importVoiceButton).setOnClickListener(v -> pickVoiceFiles());
        ((Button) findViewById(R.id.resetButton)).setOnClickListener(v -> resetToDefaults());

        bindVoices();
        bindSpeakers();
        showModelInfo();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A download or delete in the voice store changes what is available here.
        bindVoices();
    }

    private void bindVoices() {
        voiceList = voices.list();
        List<String> labels = new ArrayList<>();
        int selected = 0;
        String current = settings.voiceId();
        for (int i = 0; i < voiceList.size(); i++) {
            labels.add(voiceList.get(i).displayName());
            if (voiceList.get(i).id.equals(current)) {
                selected = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        voiceSpinner.setAdapter(adapter);
        if (!voiceList.isEmpty()) {
            voiceSpinner.setSelection(selected);
        }
        voiceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= voiceList.size()) {
                    return;
                }
                String voiceId = voiceList.get(position).id;
                // Spinner fires this once for the initial selection too, on a later layout
                // pass than setSelection(), so a flag set around binding never catches it.
                // Writing only real changes makes that echo harmless.
                if (voiceId.equals(settings.voiceId())) {
                    return;
                }
                settings.setVoiceId(voiceId);
                Log.i(TAG, "voice selected: " + voiceId);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void bindSpeakers() {
        TextView hint = findViewById(R.id.speakerHint);
        if (numSpeakers <= 1) {
            speakerSpinner.setEnabled(false);
            speakerSpinner.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_item, new String[]{"0 (default)"}));
            hint.setText(R.string.single_speaker);
            return;
        }
        hint.setText(numSpeakers + " speakers in this model");
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < numSpeakers; i++) {
            labels.add(i < speakerNames.length ? i + " — " + speakerNames[i] : String.valueOf(i));
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        speakerSpinner.setAdapter(adapter);
        speakerSpinner.setSelection(Math.min(settings.speakerId(), numSpeakers - 1));
        speakerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                settings.setSpeakerId(position);
                Log.i(TAG, "speaker selected: " + position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void showModelInfo() {
        TextView info = findViewById(R.id.modelInfo);
        int sampleRate = getIntent().getIntExtra(EXTRA_SAMPLE_RATE, 0);
        StringBuilder text = new StringBuilder();
        text.append("Sample rate: ").append(sampleRate).append(" Hz\n");
        if (modelDefaults != null && modelDefaults.length >= 4) {
            text.append(String.format(Locale.US,
                    "Model defaults: noise_scale=%.3f length_scale=%.3f noise_w=%.3f "
                            + "sentence_silence=%.3f",
                    modelDefaults[0], modelDefaults[1], modelDefaults[2], modelDefaults[3]));
        }
        info.setText(text.toString());
    }

    private void resetToDefaults() {
        settings.resetToDefaults(modelDefaults);
        lengthScale.setValue(settings.lengthScale());
        noiseScale.setValue(settings.noiseScale());
        noiseW.setValue(settings.noiseW());
        sentenceSilence.setValue(settings.sentenceSilence());
        volume.setValue(settings.volume());
        if (numSpeakers > 1) {
            speakerSpinner.setSelection(0);
        }
        Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show();
    }

    private void pickVoiceFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, getString(R.string.import_voice)),
                REQUEST_IMPORT_VOICE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_VOICE || resultCode != Activity.RESULT_OK
                || data == null) {
            return;
        }
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        VoiceRepository.ImportResult result = voices.importFrom(uris);

        // Selecting the new voice is the whole point of importing one; leaving the old
        // one active made a successful import look like it had done nothing.
        if (!result.completed.isEmpty()) {
            String voiceId = result.completed.get(0);
            settings.setVoiceId(voiceId);
            Log.i(TAG, "auto-selecting imported voice: " + voiceId);
        }
        bindVoices();

        String message;
        if (!result.completed.isEmpty()) {
            message = "Imported " + result.completed.get(0)
                    + (result.completed.size() > 1
                            ? " and " + (result.completed.size() - 1) + " more" : "");
        } else if (!result.missingConfig.isEmpty()) {
            message = "Also pick " + result.missingConfig.get(0)
                    + ".onnx.json — a voice needs both files";
        } else {
            message = "No Piper voice files in that selection";
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    /** Maps a 0..1000 SeekBar onto a float range and writes through on every change. */
    private static final class SliderSetting {
        private final SeekBar bar;
        private final TextView label;
        private final String name;
        private final float min;
        private final float max;
        private final java.util.function.Consumer<Float> writer;

        SliderSetting(SeekBar bar, TextView label, String name, float min, float max,
                      float initial, java.util.function.Consumer<Float> writer) {
            this.bar = bar;
            this.label = label;
            this.name = name;
            this.min = min;
            this.max = max;
            this.writer = writer;
            bar.setMax(1000);
            setValue(initial);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    float value = valueOf(progress);
                    render(value);
                    if (fromUser) {
                        writer.accept(value);
                        Log.i(TAG, name + " = " + value);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        void setValue(float value) {
            float clamped = Math.max(min, Math.min(max, value));
            bar.setProgress(Math.round((clamped - min) / (max - min) * 1000f));
            render(clamped);
            writer.accept(clamped);
        }

        private float valueOf(int progress) {
            return min + (max - min) * (progress / 1000f);
        }

        private void render(float value) {
            label.setText(String.format(Locale.US, "%s: %.3f", name, value));
        }
    }
}
