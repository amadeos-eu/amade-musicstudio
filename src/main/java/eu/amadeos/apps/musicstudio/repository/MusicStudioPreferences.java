package eu.amadeos.apps.musicstudio.repository;

import com.google.inject.Inject;
import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.service.export.ExportFormat;
import eu.amadeos.apps.musicstudio.service.export.ExportOptions;
import eu.amadeos.commons.config.AppDirectories;
import eu.amadeos.commons.config.PropertiesFile;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings of Music Studio in {@code ~/.config/amadeos/music-studio/music-studio.properties}: which parts the
 * window shows, the parts New Piece keeps, the choices of the last export and the last piece, which comes back at
 * the next start. Each change is written at once.
 */
public class MusicStudioPreferences {
    public static final String FILE = "music-studio.properties";

    /** The parts of the window that can be hidden. */
    public enum Bar {
        TOOLBAR, STYLES, SETTINGS, STATUS_BAR;

        public String key() {
            return "show." + name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    private final Path file;
    private final Properties properties;

    @Inject
    public MusicStudioPreferences(AppDirectories directories) {
        this(directories.configFile(FILE));
    }

    public MusicStudioPreferences(Path file) {
        this.file = Objects.requireNonNull(file, "file");
        this.properties = PropertiesFile.load(file);
    }

    public boolean isVisible(Bar bar) {
        return !"false".equals(properties.getProperty(bar.key()));
    }

    public void setVisible(Bar bar, boolean visible) {
        set(bar.key(), String.valueOf(visible));
    }

    /** The parts New Piece leaves as they are. */
    public Set<Part> keptParts() {
        Set<Part> kept = EnumSet.noneOf(Part.class);
        for (String id : properties.getProperty("new.keep", "").split(",")) {
            for (Part p : Part.values()) if (p.id().equals(id.strip())) kept.add(p);
        }
        return kept;
    }

    public void setKeptParts(Set<Part> kept) {
        set("new.keep", kept.stream().map(Part::id).collect(Collectors.joining(",")));
    }

    /** Whether playback starts again at the end. */
    public boolean loop() {
        return "true".equals(properties.getProperty("play.loop"));
    }

    public void setLoop(boolean loop) {
        set("play.loop", String.valueOf(loop));
    }

    /** The choices of the last export; WAV with the recipe the first time. */
    public ExportOptions exportOptions() {
        ExportFormat format = ExportFormat.ofId(properties.getProperty("export.format", "")).orElse(ExportFormat.WAV);
        return new ExportOptions(format, "true".equals(properties.getProperty("export.loop")),
                "true".equals(properties.getProperty("export.stems")),
                !"false".equals(properties.getProperty("export.recipe")));
    }

    public void setExportOptions(ExportOptions options) {
        set("export.format", options.format().id());
        set("export.loop", String.valueOf(options.loop()));
        set("export.stems", String.valueOf(options.stems()));
        set("export.recipe", String.valueOf(options.includeRecipe()));
    }

    /** The piece shown when the program ended; empty at the first start or if it cannot be read. */
    public Optional<Recipe> lastRecipe() {
        String line = properties.getProperty("last.recipe");
        if (line == null || line.isBlank()) return Optional.empty();
        try {
            return Optional.of(Recipe.parse(line));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public void setLastRecipe(Recipe recipe) {
        set("last.recipe", recipe.toLine());
    }

    /**
     * Stores the value at once.
     *
     * @throws java.io.UncheckedIOException if the file cannot be written
     */
    private void set(String key, String value) {
        if (value.equals(properties.getProperty(key))) return;
        properties.setProperty(key, value);
        PropertiesFile.store(file, properties, "amade-musicstudio");
    }
}
