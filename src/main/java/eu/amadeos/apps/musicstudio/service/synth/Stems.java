package eu.amadeos.apps.musicstudio.service.synth;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.service.compose.Song;

import java.util.EnumMap;
import java.util.Map;

/**
 * The four parts of a rendered piece, each in stereo with its share for the hall, before the mixer. Changing
 * a volume or muting a part only mixes them anew; nothing is calculated again.
 *
 * @param gain the factor that brings the dry mix of all parts at their usual volume near full level
 */
public record Stems(Song song, Map<Part, float[]> left, Map<Part, float[]> right, Map<Part, float[]> send,
                    int frames, int sampleRate, double gain) {
    public Stems {
        left = new EnumMap<>(left);
        right = new EnumMap<>(right);
        send = new EnumMap<>(send);
    }
}
