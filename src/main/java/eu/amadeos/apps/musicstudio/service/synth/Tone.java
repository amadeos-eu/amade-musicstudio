package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Voice;

/**
 * How a voice's attack and release sliders change an instrument: factors on its attack time and on its
 * release, 1 for the instrument as it is (slider at 50), up to four times longer or shorter.
 */
public record Tone(double attack, double release) {
    public static final Tone NEUTRAL = new Tone(1, 1);

    public static Tone of(Voice voice) {
        return new Tone(factor(voice.attack()), factor(voice.release()));
    }

    static double factor(int slider) {
        return Math.pow(2, (slider - 50) / 25.0);
    }
}
