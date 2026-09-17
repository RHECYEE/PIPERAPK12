package dev.piper.mvp;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The English female voices published in rhasspy/piper-voices at tag v1.0.0.
 *
 * <p>Together these are about 881 MB, so they are downloaded on demand rather than
 * bundled. Sizes, sample rates and paths were read from the repository tree and from
 * each voice's .onnx.json, not typed by hand.
 *
 * <p>piper-voices carries no gender field, so this selection comes from the voice and
 * dataset names rather than repository metadata. Multi-speaker models (vctk, libritts,
 * arctic, l2arctic, aru, semaine) also contain female speakers and are deliberately not
 * listed: select one of those as a voice and pick a speaker instead.
 */
final class VoiceCatalog {

    private static final String BASE =
            "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/";

    static final class Voice {
        final String id;
        final String displayName;
        final long modelBytes;
        final long configBytes;
        final int sampleRate;
        private final String path;

        Voice(String id, String displayName, long modelBytes, long configBytes,
              int sampleRate, String path) {
            this.id = id;
            this.displayName = displayName;
            this.modelBytes = modelBytes;
            this.configBytes = configBytes;
            this.sampleRate = sampleRate;
            this.path = path;
        }

        String modelUrl() {
            return BASE + path;
        }

        String configUrl() {
            return BASE + path + ".json";
        }

        long totalBytes() {
            return modelBytes + configBytes;
        }

        String sizeLabel() {
            return String.format(Locale.US, "%.0f MB", totalBytes() / 1048576.0);
        }
    }

    static List<Voice> englishFemale() {
        return Arrays.asList(
                new Voice("en_GB-alba-medium", "Alba (en-GB, medium)", 63201294L, 4888L, 22050, "en/en_GB/alba/medium/en_GB-alba-medium.onnx"),
                new Voice("en_GB-cori-medium", "Cori (en-GB, medium)", 63531379L, 4966L, 22050, "en/en_GB/cori/medium/en_GB-cori-medium.onnx"),
                new Voice("en_GB-cori-high", "Cori (en-GB, high)", 114219352L, 4963L, 22050, "en/en_GB/cori/high/en_GB-cori-high.onnx"),
                new Voice("en_GB-jenny_dioco-medium", "Jenny Dioco (en-GB, medium)", 63201294L, 4895L, 22050, "en/en_GB/jenny_dioco/medium/en_GB-jenny_dioco-medium.onnx"),
                new Voice("en_GB-southern_english_female-low", "Southern English Female (en-GB, low)", 63104526L, 4189L, 16000, "en/en_GB/southern_english_female/low/en_GB-southern_english_female-low.onnx"),
                new Voice("en_US-amy-low", "Amy (en-US, low)", 63104526L, 4164L, 16000, "en/en_US/amy/low/en_US-amy-low.onnx"),
                new Voice("en_US-amy-medium", "Amy (en-US, medium)", 63201294L, 4882L, 22050, "en/en_US/amy/medium/en_US-amy-medium.onnx"),
                new Voice("en_US-hfc_female-medium", "Hfc Female (en-US, medium)", 63201294L, 5033L, 22050, "en/en_US/hfc_female/medium/en_US-hfc_female-medium.onnx"),
                new Voice("en_US-kathleen-low", "Kathleen (en-US, low)", 63104526L, 4169L, 16000, "en/en_US/kathleen/low/en_US-kathleen-low.onnx"),
                new Voice("en_US-kristin-medium", "Kristin (en-US, medium)", 63531379L, 4968L, 22050, "en/en_US/kristin/medium/en_US-kristin-medium.onnx"),
                new Voice("en_US-lessac-low", "Lessac (en-US, low)", 63201294L, 4882L, 16000, "en/en_US/lessac/low/en_US-lessac-low.onnx"),
                new Voice("en_US-lessac-medium", "Lessac (en-US, medium)", 63201294L, 4885L, 22050, "en/en_US/lessac/medium/en_US-lessac-medium.onnx"),
                new Voice("en_US-lessac-high", "Lessac (en-US, high)", 113895201L, 4883L, 22050, "en/en_US/lessac/high/en_US-lessac-high.onnx")
        );
    }

    private VoiceCatalog() {
    }
}
