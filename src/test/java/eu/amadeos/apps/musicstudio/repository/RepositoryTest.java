package eu.amadeos.apps.musicstudio.repository;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.domain.Style;
import eu.amadeos.apps.musicstudio.repository.MusicStudioPreferences.Bar;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.apps.musicstudio.service.export.MidiWriter;
import eu.amadeos.apps.musicstudio.service.export.ModWriter;
import eu.amadeos.apps.musicstudio.service.export.RecipeTag;
import eu.amadeos.apps.musicstudio.service.export.WavWriter;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryTest {
    @TempDir
    Path dir;

    private final MusicFiles files = new MusicFiles();
    private final Recipe recipe = Recipe.of(Style.ELECTRO, 4711).with(b -> {
        b.bpm = 101;
        b.lengthSeconds = Recipe.MIN_LENGTH;
    });

    private void write(Path path, IoWriter writer) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            writer.write(out);
        }
    }

    private interface IoWriter {
        void write(OutputStream out) throws IOException;
    }

    @Test
    void aRecipeFileReadsBack() throws IOException {
        Path file = dir.resolve("piece.musicrecipe");
        files.writeRecipe(file, recipe);
        assertEquals(recipe, files.readRecipe(file));
        assertFalse(Files.exists(MusicFiles.temporary(file)));
        assertEquals(dir.resolve(".piece.musicrecipe.part"), MusicFiles.temporary(file));
    }

    @Test
    void theRecipeReadsBackFromWavMidiAndMod() throws IOException {
        Song song = new Composer().compose(recipe, false);
        String comment = RecipeTag.of(recipe);
        Path wav = dir.resolve("a.wav");
        write(wav, out -> new WavWriter().write(new Audio(new float[100], new float[100], 44100), comment, out));
        assertEquals(recipe, files.readRecipe(wav));
        Path mid = dir.resolve("a.mid");
        write(mid, out -> new MidiWriter().write(song, comment, out));
        assertEquals(recipe, files.readRecipe(mid));
        Path mod = dir.resolve("a.mod");
        write(mod, out -> new ModWriter(new AudioRenderer()).write(song, comment, out));
        assertEquals(recipe, files.readRecipe(mod));
    }

    @Test
    void aFileWithoutRecipe() throws IOException {
        Path wav = dir.resolve("plain.wav");
        write(wav, out -> new WavWriter().write(new Audio(new float[10], new float[10], 44100), null, out));
        assertThrows(IOException.class, () -> files.readRecipe(wav));
        Path text = dir.resolve("notes.musicrecipe");
        Files.writeString(text, "hello");
        assertThrows(IOException.class, () -> files.readRecipe(text));
        assertThrows(IOException.class, () -> files.readRecipe(dir.resolve("missing.musicrecipe")));
    }

    @Test
    void preferencesAreKept() {
        Path file = dir.resolve("music-studio.properties");
        MusicStudioPreferences prefs = new MusicStudioPreferences(file);
        assertTrue(prefs.isVisible(Bar.STYLES));
        assertEquals(Set.of(), prefs.keptParts());
        assertFalse(prefs.loop());
        assertEquals(ExportOptions.DEFAULT, prefs.exportOptions());
        assertTrue(prefs.exportOptions().includeRecipe());
        assertTrue(prefs.lastRecipe().isEmpty());

        prefs.setVisible(Bar.SETTINGS, false);
        prefs.setKeptParts(EnumSet.of(Part.DRUMS, Part.BASS));
        prefs.setLoop(true);
        ExportOptions options = new ExportOptions(ExportFormat.FLAC, true, true, false);
        prefs.setExportOptions(options);
        prefs.setLastRecipe(recipe);

        MusicStudioPreferences again = new MusicStudioPreferences(file);
        assertFalse(again.isVisible(Bar.SETTINGS));
        assertTrue(again.isVisible(Bar.TOOLBAR));
        assertEquals(EnumSet.of(Part.DRUMS, Part.BASS), again.keptParts());
        assertTrue(again.loop());
        assertEquals(options, again.exportOptions());
        assertEquals(recipe, again.lastRecipe().orElseThrow());
    }

    @Test
    void brokenPreferencesTakeTheDefaults() throws IOException {
        Path file = dir.resolve("music-studio.properties");
        Files.writeString(file, "new.keep=drums,nothing\nexport.format=mp9\nlast.recipe=garbage\n");
        MusicStudioPreferences prefs = new MusicStudioPreferences(file);
        assertEquals(Set.of(Part.DRUMS), prefs.keptParts());
        assertEquals(ExportOptions.DEFAULT.format(), prefs.exportOptions().format());
        assertTrue(prefs.lastRecipe().isEmpty());
    }
}
