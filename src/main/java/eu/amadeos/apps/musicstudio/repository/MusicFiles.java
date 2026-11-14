package eu.amadeos.apps.musicstudio.repository;

import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.export.MidiWriter;
import eu.amadeos.apps.musicstudio.service.export.ModWriter;
import eu.amadeos.apps.musicstudio.service.export.RecipeTag;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Recipes as text files ({@code .musicrecipe}), and the recipe inside the files Music Studio exports: the
 * comment of a WAV, FLAC or OGG file, the text event of a MIDI file and the sample names of a module. So a piece
 * can always be opened again and changed further.
 */
public final class MusicFiles {
    public static final String RECIPE_EXTENSION = "musicrecipe";
    /** The files that can be opened. */
    public static final List<String> OPEN_EXTENSIONS = List.of(RECIPE_EXTENSION, "wav", "flac", "ogg", "mid", "mod");
    /** Where an audio file's comment is looked for: the comment is near the start of the file. */
    static final int SCAN_BYTES = 256 * 1024;
    static final int MAX_RECIPE_FILE = 64 * 1024;

    public void writeRecipe(Path path, Recipe recipe) throws IOException {
        Path temporary = temporary(path);
        try {
            Files.writeString(temporary, recipe.toText());
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * The recipe in a recipe file or in a file Music Studio exported.
     *
     * @throws IOException if the file cannot be read or holds no recipe
     */
    public Recipe readRecipe(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        Optional<Recipe> found = switch (extension) {
            case "wav", "flac", "ogg" -> RecipeTag.parse(scan(path));
            case "mid", "midi" -> RecipeTag.parse(midiComment(path));
            case "mod" -> RecipeTag.parse(ModWriter.readComment(head(path, 2048)));
            default -> Optional.of(readRecipeText(path));
        };
        return found.orElseThrow(() -> new IOException("No recipe in " + path.getFileName()));
    }

    private static Recipe readRecipeText(Path path) throws IOException {
        if (Files.size(path) > MAX_RECIPE_FILE) throw new IOException("Not a recipe: " + path.getFileName());
        try {
            return Recipe.parse(Files.readString(path));
        } catch (IllegalArgumentException | MalformedInputException e) {
            throw new IOException("Not a recipe: " + path.getFileName() + " (" + e.getMessage() + ")", e);
        }
    }

    /** The comment that starts with the recipe prefix in the first bytes of a file, up to the first control byte. */
    static String scan(Path path) throws IOException {
        byte[] head = head(path, SCAN_BYTES);
        byte[] prefix = RecipeTag.PREFIX.getBytes(StandardCharsets.US_ASCII);
        int at = indexOf(head, prefix);
        if (at < 0) return null;
        int end = at;
        while (end < head.length && head[end] >= 32 && head[end] < 127) end++;
        return new String(head, at, end - at, StandardCharsets.US_ASCII);
    }

    private static String midiComment(Path path) throws IOException {
        try {
            return MidiWriter.readComment(MidiSystem.getSequence(path.toFile())).orElse(null);
        } catch (InvalidMidiDataException e) {
            throw new IOException("Not a MIDI file: " + path.getFileName(), e);
        }
    }

    private static byte[] head(Path path, int count) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readNBytes(count);
        }
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (data[i + j] != pattern[j]) continue outer;
            return i;
        }
        return -1;
    }

    /** A file next to the target that becomes the target when it is complete. */
    public static Path temporary(Path path) {
        return path.resolveSibling("." + path.getFileName() + ".part");
    }
}
