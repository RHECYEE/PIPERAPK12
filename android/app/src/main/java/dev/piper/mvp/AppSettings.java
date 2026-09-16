package dev.piper.mvp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Persisted synthesis settings.
 *
 * <p>Every field here maps to something piper.cpp actually reads:
 * noiseScale/lengthScale/noiseW go into the model's {@code scales} input tensor,
 * speakerId into {@code sid}, and sentenceSilenceSeconds controls the zero samples
 * textToAudio() appends between sentences. Volume is the one playback-layer setting -
 * piper peak-normalizes its output and has no gain parameter.
 */
final class AppSettings {

    static final String TAG = "PiperSettings";

    private static final String PREFS = "piper_mvp_settings";
    private static final String KEY_SEEDED = "seeded";
    private static final String KEY_NOISE_SCALE = "noise_scale";
    private static final String KEY_LENGTH_SCALE = "length_scale";
    private static final String KEY_NOISE_W = "noise_w";
    private static final String KEY_SENTENCE_SILENCE = "sentence_silence";
    private static final String KEY_SPEAKER_ID = "speaker_id";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_VOICE_ID = "voice_id";

    // piper::SynthesisConfig struct defaults, used until a model reports its own.
    static final float DEFAULT_NOISE_SCALE = 0.667f;
    static final float DEFAULT_LENGTH_SCALE = 1.0f;
    static final float DEFAULT_NOISE_W = 0.8f;
    static final float DEFAULT_SENTENCE_SILENCE = 0.2f;
    static final float DEFAULT_VOLUME = 1.0f;

    static final float MIN_NOISE_SCALE = 0f, MAX_NOISE_SCALE = 1.5f;
    static final float MIN_LENGTH_SCALE = 0.25f, MAX_LENGTH_SCALE = 3.0f;
    static final float MIN_NOISE_W = 0f, MAX_NOISE_W = 1.5f;
    static final float MIN_SENTENCE_SILENCE = 0f, MAX_SENTENCE_SILENCE = 2.0f;

    private final SharedPreferences prefs;

    AppSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    float noiseScale() {
        return prefs.getFloat(KEY_NOISE_SCALE, DEFAULT_NOISE_SCALE);
    }

    float lengthScale() {
        return prefs.getFloat(KEY_LENGTH_SCALE, DEFAULT_LENGTH_SCALE);
    }

    float noiseW() {
        return prefs.getFloat(KEY_NOISE_W, DEFAULT_NOISE_W);
    }

    float sentenceSilence() {
        return prefs.getFloat(KEY_SENTENCE_SILENCE, DEFAULT_SENTENCE_SILENCE);
    }

    int speakerId() {
        return prefs.getInt(KEY_SPEAKER_ID, 0);
    }

    float volume() {
        return prefs.getFloat(KEY_VOLUME, DEFAULT_VOLUME);
    }

    String voiceId() {
        return prefs.getString(KEY_VOICE_ID, null);
    }

    void setNoiseScale(float v) {
        prefs.edit().putFloat(KEY_NOISE_SCALE, v).apply();
    }

    void setLengthScale(float v) {
        prefs.edit().putFloat(KEY_LENGTH_SCALE, v).apply();
    }

    void setNoiseW(float v) {
        prefs.edit().putFloat(KEY_NOISE_W, v).apply();
    }

    void setSentenceSilence(float v) {
        prefs.edit().putFloat(KEY_SENTENCE_SILENCE, v).apply();
    }

    void setSpeakerId(int v) {
        prefs.edit().putInt(KEY_SPEAKER_ID, v).apply();
    }

    void setVolume(float v) {
        prefs.edit().putFloat(KEY_VOLUME, v).apply();
    }

    void setVoiceId(String id) {
        prefs.edit().putString(KEY_VOICE_ID, id).apply();
    }

    /**
     * On a fresh install, adopt the loaded model's own inference values so the app
     * starts out behaving exactly like the upstream piper CLI for that voice.
     */
    void seedFromModelDefaultsIfUnset(float[] modelDefaults) {
        if (prefs.getBoolean(KEY_SEEDED, false) || modelDefaults == null
                || modelDefaults.length < 4) {
            return;
        }
        applyModelDefaults(modelDefaults);
        Log.i(TAG, "seeded settings from model defaults");
    }

    /** Reset to Defaults: back to whatever the currently loaded model declares. */
    void resetToDefaults(float[] modelDefaults) {
        float[] values = (modelDefaults != null && modelDefaults.length >= 4)
                ? modelDefaults
                : new float[]{DEFAULT_NOISE_SCALE, DEFAULT_LENGTH_SCALE, DEFAULT_NOISE_W,
                              DEFAULT_SENTENCE_SILENCE};
        applyModelDefaults(values);
        Log.i(TAG, "reset to defaults: noiseScale=" + values[0] + " lengthScale=" + values[1]
                + " noiseW=" + values[2] + " sentenceSilence=" + values[3]);
    }

    private void applyModelDefaults(float[] values) {
        prefs.edit()
                .putFloat(KEY_NOISE_SCALE, values[0])
                .putFloat(KEY_LENGTH_SCALE, values[1])
                .putFloat(KEY_NOISE_W, values[2])
                .putFloat(KEY_SENTENCE_SILENCE, values[3])
                .putInt(KEY_SPEAKER_ID, 0)
                .putFloat(KEY_VOLUME, DEFAULT_VOLUME)
                .putBoolean(KEY_SEEDED, true)
                .apply();
    }

    /**
     * Identifies everything that changes what Piper would produce. Cached audio stays
     * valid while this is unchanged. Volume is excluded: it is applied at playback, so
     * changing it needs no re-synthesis.
     */
    String synthesisFingerprint() {
        return voiceId() + "|" + noiseScale() + "|" + lengthScale() + "|" + noiseW()
                + "|" + sentenceSilence() + "|" + speakerId();
    }

    String describe() {
        return "noiseScale=" + noiseScale() + " lengthScale=" + lengthScale()
                + " noiseW=" + noiseW() + " sentenceSilence=" + sentenceSilence()
                + " speakerId=" + speakerId() + " volume=" + volume();
    }
}
