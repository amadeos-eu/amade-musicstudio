package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Instrument;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Filter;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Osc;
import eu.amadeos.apps.musicstudio.service.synth.Dsp.Wave;

import java.util.Random;

/**
 * Sung voices without words, calculated like the other instruments. A singer is a source and a filter: the
 * vocal folds give a buzz rich in overtones (a soft saw wave with a little breath), and the mouth and throat
 * strengthen a few frequency bands of it, the formants, whose places make the vowel: "ooh", "aah" or a hum
 * with closed lips. Men, women and children differ in the size of the throat, which moves all formants. The
 * pitch wavers a little (vibrato) once a note is held, and slides into the note from just below.
 *
 * <p>A choir is several singers on the same note, each a little off in pitch, vibrato, throat and start.
 */
final class Vocal {
    /** The vowels. */
    enum Vowel { OOH, AAH, MMM }

    /** Who sings. */
    enum Singer {
        MALE(1.0, 0.012), FEMALE(1.17, 0.014), CHILD(1.36, 0.008);

        /** How much higher the formants lie than a man's: the shorter the throat, the higher. */
        final double throat;
        /** The depth of the vibrato as a part of the frequency. */
        final double vibrato;

        Singer(double throat, double vibrato) {
            this.throat = throat;
            this.vibrato = vibrato;
        }
    }

    /** Centre frequencies of the five formants of a man's voice in Hz, per vowel. */
    private static final double[][] FREQUENCY = {
            {340, 680, 2400, 2700, 3000},    // ooh
            {640, 1080, 2400, 2700, 3100},   // aah
            {250, 1100, 2100, 2700, 3200},   // mmm
    };
    /** How strong each formant is, in decibels below the first. */
    private static final double[][] LEVEL = {
            {0, -14, -30, -28, -36},
            {0, -5, -9, -11, -18},
            {0, -26, -34, -40, -46},
    };
    /** The width of each formant in Hz. */
    private static final double[][] WIDTH = {
            {50, 80, 100, 120, 120},
            {70, 80, 110, 120, 130},
            {60, 120, 150, 200, 200},
    };

    /** The level of a held note (root mean square), before its velocity. */
    private static final double LEVEL_HELD = 0.38;

    private Vocal() {
    }

    static boolean sings(Instrument in) {
        return in.group() == Instrument.Group.VOICE;
    }

    static Vowel vowel(Instrument in) {
        return switch (in) {
            case MALE_AAH, FEMALE_AAH, CHILD_AAH, CHOIR_AAH -> Vowel.AAH;
            case CHOIR_MMM -> Vowel.MMM;
            default -> Vowel.OOH;
        };
    }

    static boolean choir(Instrument in) {
        return in == Instrument.CHOIR_OOH || in == Instrument.CHOIR_AAH || in == Instrument.CHOIR_MMM;
    }

    /** One sung note into {@code out}; a choir adds its singers. */
    static void note(float[] out, Instrument in, int pitch, double velocity, double held, double release,
                     Tone tone, double sr, Random random) {
        Vowel vowel = vowel(in);
        if (!choir(in)) {
            Singer singer = switch (in) {
                case FEMALE_OOH, FEMALE_AAH -> Singer.FEMALE;
                case CHILD_OOH, CHILD_AAH -> Singer.CHILD;
                default -> Singer.MALE;
            };
            sing(out, singer, 1, vowel, Dsp.hz(pitch), velocity, held, release, 0.07 * tone.attack(), 0, 1, sr, random);
            return;
        }
        // Six singers: men below middle C, women above, both around it; never two alike.
        double men = Math.max(0, Math.min(1, (64 - pitch) / 12.0));
        int singers = 6;
        for (int s = 0; s < singers; s++) {
            boolean man = (s + 0.5) / singers < men;
            Singer singer = man ? Singer.MALE : Singer.FEMALE;
            double throat = 0.94 + 0.12 * random.nextDouble();
            double cents = (random.nextDouble() - 0.5) * 22;
            double late = random.nextDouble() * 0.045;
            sing(out, singer, throat, vowel, Dsp.hz(pitch + cents / 100), velocity, held, release,
                    (0.12 + 0.08 * random.nextDouble()) * tone.attack(), late, 1 / Math.sqrt(singers), sr, random);
        }
    }

    private static void sing(float[] out, Singer singer, double throat, Vowel vowel, double f, double velocity,
                             double held, double release, double attack, double late, double gain, double sr,
                             Random random) {
        int v = vowel.ordinal();
        Filter[] formants = new Filter[5];
        double[] level = new double[5];
        for (int k = 0; k < 5; k++) {
            double centre = FREQUENCY[v][k] * singer.throat * throat;
            // Singers open the mouth for high notes, so that the first formant never lies below the note.
            if (k == 0 && vowel != Vowel.MMM) centre = Math.max(centre, f * 1.02);
            if (k == 1) centre = Math.max(centre, f * 1.6);
            // High notes have few overtones; wider bands keep a single one from ringing out.
            double width = Math.max(WIDTH[v][k] * (1 + 0.6 * (singer.throat * throat - 1)), 0.2 * f);
            double q = centre / width;
            formants[k] = new Filter().set(centre, q, sr);
            // The band output of the filter is q times too loud at its centre.
            level[k] = Math.pow(10, LEVEL[v][k] / 20) / q;
        }
        Osc folds = new Osc(Dsp.table(Wave.SAW, f * 1.02, sr, 0), f, sr, random.nextDouble());
        // The folds close softly: the overtones fall off faster than a saw's, less so when singing loudly.
        Filter tilt = new Filter().set(Math.max(f * 2, 1400 + 3200 * velocity) * (vowel == Vowel.MMM ? 0.5 : 1), 0.6, sr);
        Filter breath = new Filter().set(2500 * singer.throat, 0.8, sr);
        double vibratoRate = 5.2 + 0.9 * random.nextDouble();
        double vibratoPhase = random.nextDouble() * 2 * Math.PI;
        double drift = 0;
        int start = Math.min(out.length, (int) (late * sr));
        float[] sung = new float[out.length - start];
        double airy = vowel == Vowel.MMM ? 0.01 : singer == Singer.CHILD ? 0.05 : 0.035;
        for (int i = 0; i < sung.length; i++) {
            double t = i / sr;
            // Vibrato sets in after a third of a second; a slow random drift keeps long notes alive.
            double depth = singer.vibrato * Math.min(1, Math.max(0, t - 0.28) / 0.5);
            drift += (random.nextDouble() - 0.5) * 0.00004;
            drift *= 0.9995;
            double scoop = -0.018 * Math.exp(-t / 0.045);
            double factor = 1 + depth * Math.sin(2 * Math.PI * vibratoRate * t + vibratoPhase) + drift + scoop;
            double source = tilt.process(folds.next(factor));
            breath.process(random.nextDouble() * 2 - 1);
            source += airy * breath.band * (0.6 + 0.4 * source);
            double sum = 0;
            for (int k = 0; k < 5; k++) {
                formants[k].process(source);
                sum += level[k] * formants[k].band;
            }
            double env = Dsp.envelope(t, attack, 0.5, 0.9, release, held - late);
            sung[i] = (float) (sum * env);
        }
        // How loud a note comes out depends on where its overtones meet the formants, so every note is
        // brought to the same level, measured while it is held.
        int from = Math.min(sung.length, (int) (Math.min(attack, held * 0.5) * sr));
        int to = Math.max(from + 1, Math.min(sung.length, (int) ((held - late) * sr)));
        double square = 0;
        for (int i = from; i < to && i < sung.length; i++) square += sung[i] * sung[i];
        double rms = Math.sqrt(square / (to - from));
        float scale = (float) (rms < 1e-9 ? 0 : LEVEL_HELD * gain / rms);
        for (int i = 0; i < sung.length; i++) out[start + i] += sung[i] * scale;
    }
}
