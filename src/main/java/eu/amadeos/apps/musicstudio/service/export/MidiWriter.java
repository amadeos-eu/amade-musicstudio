package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.compose.Note;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Writes a piece as a Standard MIDI File (type 1, one track per channel) with General MIDI instruments: the drums
 * on channel 10, the recipe as a text event in the first track. Any music program can open it to go on with the
 * notes.
 */
public final class MidiWriter {
    static final int PPQ = 96;
    static final int TICKS_PER_STEP = PPQ / 4;
    static final int TEXT = 0x01;
    static final int TRACK_NAME = 0x03;
    static final int TEMPO = 0x51;
    static final int DRUM_CHANNEL = 9;

    /** @param comment text for a text event, for example the recipe; {@code null} for none */
    public void write(Song song, String comment, OutputStream out) throws IOException {
        try {
            MidiSystem.write(sequence(song, comment), 1, out);
        } catch (InvalidMidiDataException e) {
            throw new IOException(e);
        }
    }

    Sequence sequence(Song song, String comment) throws InvalidMidiDataException {
        Recipe r = song.recipe();
        Sequence seq = new Sequence(Sequence.PPQ, PPQ);
        Track meta = seq.createTrack();
        meta.add(event(meta(TRACK_NAME, r.style().id() + " " + r.seed()), 0));
        int perQuarter = (int) (60_000_000L / r.bpm());
        meta.add(event(new MetaMessage(TEMPO,
                new byte[]{(byte) (perQuarter >> 16), (byte) (perQuarter >> 8), (byte) perQuarter}, 3), 0));
        if (comment != null && !comment.isEmpty()) meta.add(event(meta(TEXT, comment), 0));

        Track[] tracks = new Track[Song.CHANNELS];
        for (int ch = 0; ch < Song.CHANNELS; ch++) {
            Part part = Song.part(ch);
            if (!r.plays(part) && part.extra()) continue;
            Track t = seq.createTrack();
            tracks[ch] = t;
            int mc = channel(ch);
            t.add(event(meta(TRACK_NAME, part.tonal() ? r.voice(part).instrument().id() + " (" + part.id() + ")"
                    : "drums " + ch), 0));
            if (part.tonal()) {
                t.add(event(new ShortMessage(ShortMessage.PROGRAM_CHANGE, mc, r.voice(part).instrument().midiProgram(), 0), 0));
            }
            if (part != Part.DRUMS || ch == Song.KICK) {
                int volume = Math.min(127, Math.round(r.volume(part) * 127 / 100f));
                t.add(event(new ShortMessage(ShortMessage.CONTROL_CHANGE, mc, 7, volume), 0));
            }
            if (part != Part.DRUMS) {
                int pan = (int) Math.round(64 + AudioRenderer.pan(ch) * 63);
                t.add(event(new ShortMessage(ShortMessage.CONTROL_CHANGE, mc, 10, Math.max(0, Math.min(127, pan))), 0));
            }
        }
        for (Note n : song.notes()) {
            int mc = channel(n.channel());
            int key = n.isDrum() ? n.drum().midiNote() : n.pitch();
            if (key < 0 || key > 127) continue;
            long on = Math.round(song.heardAt(n) * TICKS_PER_STEP);
            long off = on + Math.max(1, Math.round((n.isDrum() ? 0.5 : n.length()) * TICKS_PER_STEP) - 1);
            int velocity = (int) Math.max(1, Math.min(127, Math.round(n.velocity() * 115)));
            tracks[n.channel()].add(event(new ShortMessage(ShortMessage.NOTE_ON, mc, key, velocity), on));
            tracks[n.channel()].add(event(new ShortMessage(ShortMessage.NOTE_OFF, mc, key, 0), off));
        }
        return seq;
    }

    /** The MIDI channel (0-based) of a song channel: drums on channel 10, then bass, three chord voices, melody,
     * two pad voices and the arpeggio. */
    static int channel(int songChannel) {
        return songChannel <= Song.HATS ? DRUM_CHANNEL : songChannel - Song.BASS;
    }

    /** The first text event of a MIDI file, if any. */
    public static Optional<String> readComment(Sequence sequence) {
        for (Track t : sequence.getTracks()) {
            for (int i = 0; i < t.size(); i++) {
                MidiMessage m = t.get(i).getMessage();
                if (m instanceof MetaMessage mm && mm.getType() == TEXT) {
                    return Optional.of(new String(mm.getData(), StandardCharsets.UTF_8));
                }
            }
        }
        return Optional.empty();
    }

    private static MetaMessage meta(int type, String text) throws InvalidMidiDataException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        return new MetaMessage(type, data, data.length);
    }

    private static MidiEvent event(MidiMessage message, long tick) {
        return new MidiEvent(message, tick);
    }
}
