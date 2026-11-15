package eu.amadeos.apps.musicstudio.domain;

import java.util.List;
import java.util.Objects;

/**
 * How one tonal part sounds: the instrument, an octave up or down, a softer or harder attack, a shorter or
 * longer release, the volume in the mix, and two slow movements in time with the piece: a filter that closes
 * and opens again (sweep) and a volume that sinks and rises again (tremolo).
 *
 * @param octave      -2 to 2
 * @param attack      0 (hard) to 100 (soft); 50 is the instrument as it is
 * @param release     0 (short) to 100 (long); 50 is the instrument as it is
 * @param volume      0 to 100
 * @param sweep       0 (none) to 100 (deep): how far the filter closes
 * @param sweepRate   how long one sweep takes, an index into {@link #RATES}
 * @param tremolo     0 (none) to 100 (deep): how far the volume sinks
 * @param tremoloRate how long one wave of the tremolo takes, an index into {@link #RATES}
 */
public record Voice(Instrument instrument, int octave, int attack, int release, int volume, int sweep, int sweepRate,
                    int tremolo, int tremoloRate) {
    public static final int MIN_OCTAVE = -2;
    public static final int MAX_OCTAVE = 2;
    public static final int DEFAULT_VOLUME = 75;
    /** The lengths a movement can have, in sixteenths: eight, four and two bars, one bar, a half, a quarter, an eighth. */
    public static final List<Integer> RATES = List.of(128, 64, 32, 16, 8, 4, 2);
    public static final int DEFAULT_SWEEP_RATE = 1;
    public static final int DEFAULT_TREMOLO_RATE = 5;

    public Voice {
        Objects.requireNonNull(instrument, "instrument");
        octave = Math.max(MIN_OCTAVE, Math.min(MAX_OCTAVE, octave));
        attack = Recipe.clamp(attack);
        release = Recipe.clamp(release);
        volume = Recipe.clamp(volume);
        sweep = Recipe.clamp(sweep);
        tremolo = Recipe.clamp(tremolo);
        sweepRate = Math.max(0, Math.min(RATES.size() - 1, sweepRate));
        tremoloRate = Math.max(0, Math.min(RATES.size() - 1, tremoloRate));
    }

    public static Voice of(Instrument instrument) {
        return new Voice(instrument, 0, 50, 50, DEFAULT_VOLUME, 0, DEFAULT_SWEEP_RATE, 0, DEFAULT_TREMOLO_RATE);
    }

    /** The message key of a length in {@link #RATES}. */
    public static String rateKey(int rate) {
        return "movement.rate." + RATES.get(rate);
    }

    /** One sweep in sixteenths. */
    public int sweepSteps() {
        return RATES.get(sweepRate);
    }

    /** One wave of the tremolo in sixteenths. */
    public int tremoloSteps() {
        return RATES.get(tremoloRate);
    }

    public Voice withInstrument(Instrument i) {
        return new Voice(i, octave, attack, release, volume, sweep, sweepRate, tremolo, tremoloRate);
    }

    public Voice withOctave(int o) {
        return new Voice(instrument, o, attack, release, volume, sweep, sweepRate, tremolo, tremoloRate);
    }

    public Voice withAttack(int a) {
        return new Voice(instrument, octave, a, release, volume, sweep, sweepRate, tremolo, tremoloRate);
    }

    public Voice withRelease(int r) {
        return new Voice(instrument, octave, attack, r, volume, sweep, sweepRate, tremolo, tremoloRate);
    }

    public Voice withVolume(int v) {
        return new Voice(instrument, octave, attack, release, v, sweep, sweepRate, tremolo, tremoloRate);
    }

    public Voice withSweep(int depth, int rate) {
        return new Voice(instrument, octave, attack, release, volume, depth, rate, tremolo, tremoloRate);
    }

    public Voice withTremolo(int depth, int rate) {
        return new Voice(instrument, octave, attack, release, volume, sweep, sweepRate, depth, rate);
    }

    /** "piano,0,50,50,75", for recipes; a voice that moves adds sweep, its length, tremolo and its length. */
    String toText() {
        String text = instrument.id() + "," + octave + "," + attack + "," + release + "," + volume;
        if (sweep == 0 && tremolo == 0 && sweepRate == DEFAULT_SWEEP_RATE && tremoloRate == DEFAULT_TREMOLO_RATE) {
            return text;
        }
        return text + "," + sweep + "," + sweepRate + "," + tremolo + "," + tremoloRate;
    }

    /** Reads {@link #toText()}; parts that are missing or wrong take their default. */
    static Voice parse(String text, Voice fallback) {
        if (text == null || text.isBlank()) return fallback;
        String[] p = text.split(",");
        Instrument instrument = Instrument.ofId(p[0].strip()).orElse(fallback.instrument());
        return new Voice(instrument, number(p, 1, 0), number(p, 2, 50), number(p, 3, 50),
                number(p, 4, DEFAULT_VOLUME), number(p, 5, 0), number(p, 6, DEFAULT_SWEEP_RATE), number(p, 7, 0),
                number(p, 8, DEFAULT_TREMOLO_RATE));
    }

    private static int number(String[] parts, int index, int fallback) {
        if (index >= parts.length) return fallback;
        try {
            return Integer.parseInt(parts[index].strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
