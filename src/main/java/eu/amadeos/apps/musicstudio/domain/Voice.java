package eu.amadeos.apps.musicstudio.domain;

import java.util.Objects;

/**
 * How one tonal part sounds: the instrument, an octave up or down, a softer or harder attack, a shorter or
 * longer release and the volume in the mix.
 *
 * @param octave  -2 to 2
 * @param attack  0 (hard) to 100 (soft); 50 is the instrument as it is
 * @param release 0 (short) to 100 (long); 50 is the instrument as it is
 * @param volume  0 to 100
 */
public record Voice(Instrument instrument, int octave, int attack, int release, int volume) {
    public static final int MIN_OCTAVE = -2;
    public static final int MAX_OCTAVE = 2;
    public static final int DEFAULT_VOLUME = 75;

    public Voice {
        Objects.requireNonNull(instrument, "instrument");
        octave = Math.max(MIN_OCTAVE, Math.min(MAX_OCTAVE, octave));
        attack = Recipe.clamp(attack);
        release = Recipe.clamp(release);
        volume = Recipe.clamp(volume);
    }

    public static Voice of(Instrument instrument) {
        return new Voice(instrument, 0, 50, 50, DEFAULT_VOLUME);
    }

    public Voice withInstrument(Instrument i) {
        return new Voice(i, octave, attack, release, volume);
    }

    public Voice withOctave(int o) {
        return new Voice(instrument, o, attack, release, volume);
    }

    public Voice withAttack(int a) {
        return new Voice(instrument, octave, a, release, volume);
    }

    public Voice withRelease(int r) {
        return new Voice(instrument, octave, attack, r, volume);
    }

    public Voice withVolume(int v) {
        return new Voice(instrument, octave, attack, release, v);
    }

    /** "piano,0,50,50,75", for recipes. */
    String toText() {
        return instrument.id() + "," + octave + "," + attack + "," + release + "," + volume;
    }

    /** Reads {@link #toText()}; parts that are missing or wrong take their default. */
    static Voice parse(String text, Voice fallback) {
        if (text == null) return fallback;
        String[] p = text.split(",");
        Instrument instrument = Instrument.ofId(p[0].strip()).orElse(fallback.instrument());
        return new Voice(instrument, number(p, 1, 0), number(p, 2, 50), number(p, 3, 50),
                number(p, 4, DEFAULT_VOLUME));
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
