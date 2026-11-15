package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramMissingException;
import eu.amadeos.commons.process.ProgramResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExportServiceTest {
    @TempDir
    Path dir;

    private final Recipe recipe = Recipe.of(Style.CHIPTUNE, 99).with(b -> b.lengthSeconds = Recipe.MIN_LENGTH);

    /** Pretends to be gst-launch: copies the WAV it is given to the target, or fails. */
    private static final class FakeGst implements CommandRunner {
        final List<List<String>> commands = new ArrayList<>();
        boolean missing;
        int exitCode;

        @Override
        public Process start(List<String> command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ProgramResult run(List<String> command, Duration timeout) throws IOException {
            if (missing) throw new ProgramMissingException(command.getFirst(), new IOException("not found"));
            commands.add(command);
            if (exitCode == 0) {
                Path from = Path.of(command.get(3).substring("location=".length()));
                Path to = Path.of(command.getLast().substring("location=".length()));
                Files.copy(from, to);
            }
            return new ProgramResult(exitCode, new byte[0], "broken pipe".getBytes(StandardCharsets.UTF_8));
        }
    }

    private ExportService service(FakeGst gst) {
        return new ExportService(new Composer(), new AudioRenderer(), Runnable::run, new OggEncoder(gst, new WavWriter()));
    }

    @Test
    void stemAndExtensionNames() {
        Path target = dir.resolve("lofi-4711.wav");
        assertEquals(dir.resolve("lofi-4711-drums.wav"), ExportService.stemPath(target, Part.DRUMS));
        assertEquals(dir.resolve("noext-bass"), ExportService.stemPath(dir.resolve("noext"), Part.BASS));
        assertEquals(target, ExportService.withExtension(target, ExportFormat.WAV));
        assertEquals(dir.resolve("LOUD.WAV"), ExportService.withExtension(dir.resolve("LOUD.WAV"), ExportFormat.WAV));
        assertEquals(dir.resolve("lofi-4711.wav.mid"), ExportService.withExtension(target, ExportFormat.MIDI));
    }

    @Test
    void wavWithStems() throws Exception {
        Path target = dir.resolve("chip.wav");
        List<Path> written = service(new FakeGst()).export(recipe,
                new ExportOptions(ExportFormat.WAV, false, true, true), target, () -> false);
        long parts = java.util.Arrays.stream(Part.values()).filter(p -> !p.extra() || recipe.plays(p)).count();
        assertEquals(1 + parts, written.size(), "a pad or an arpeggio that does not play gets no file");
        assertEquals(target, written.getFirst());
        for (Path p : written) {
            assertTrue(Files.size(p) > 44, p.toString());
            assertEquals(recipe, new MusicFiles().readRecipe(p));
        }
        assertTrue(Files.exists(dir.resolve("chip-melody.wav")));
        try (var leftovers = Files.list(dir)) {
            assertEquals(written.size(), leftovers.count(), "no temporary files stay behind");
        }
    }

    @Test
    void withoutRecipeAndStemsForMidi() throws Exception {
        Path target = dir.resolve("chip.mid");
        List<Path> written = service(new FakeGst()).export(recipe,
                new ExportOptions(ExportFormat.MIDI, true, true, false), target, () -> false);
        assertEquals(List.of(target), written, "no stems for notes");
        assertThrows(IOException.class, () -> new MusicFiles().readRecipe(target));
    }

    @Test
    void cancellingLeavesNothingBehind() throws IOException {
        Path target = dir.resolve("chip.flac");
        assertThrows(CancellationException.class, () -> service(new FakeGst()).export(recipe,
                new ExportOptions(ExportFormat.FLAC, false, false, true), target, () -> true));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void oggGoesThroughGStreamer() throws Exception {
        FakeGst gst = new FakeGst();
        Path target = dir.resolve("chip.ogg");
        service(gst).export(recipe, new ExportOptions(ExportFormat.OGG, false, false, true), target, () -> false);
        assertEquals(1, gst.commands.size());
        List<String> command = gst.commands.getFirst();
        assertEquals("gst-launch-1.0", command.getFirst());
        assertTrue(command.containsAll(List.of("wavparse", "vorbisenc", "oggmux")), command.toString());
        assertTrue(Files.size(target) > 0);
        assertEquals(recipe, new MusicFiles().readRecipe(target), "the comment survives encoding");
    }

    @Test
    void oggCommand() {
        List<String> command = OggEncoder.command(Path.of("/tmp/a b.wav"), Path.of("/music/x y.ogg"));
        assertEquals("location=/tmp/a b.wav", command.get(3));
        assertEquals("location=/music/x y.ogg", command.getLast());
    }

    @Test
    void oggFailures() throws IOException {
        Audio audio = new Audio(new float[10], new float[10], 44100);
        Path target = dir.resolve("x.ogg");
        FakeGst missing = new FakeGst();
        missing.missing = true;
        assertThrows(ProgramMissingException.class,
                () -> new OggEncoder(missing, new WavWriter()).write(audio, null, target));
        FakeGst failing = new FakeGst();
        failing.exitCode = 1;
        IOException e = assertThrows(IOException.class,
                () -> new OggEncoder(failing, new WavWriter()).write(audio, null, target));
        assertTrue(e.getMessage().contains("broken pipe"), e.getMessage());
        assertFalse(Files.exists(target));
    }
}
