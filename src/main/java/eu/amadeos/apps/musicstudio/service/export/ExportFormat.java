package eu.amadeos.apps.musicstudio.service.export;

import java.util.Locale;
import java.util.Optional;

/** The file formats Music Studio writes. */
public enum ExportFormat {
    WAV("wav", true), FLAC("flac", true), OGG("ogg", true), MOD("mod", false), MIDI("mid", false);

    private final String extension;
    private final boolean audio;

    ExportFormat(String extension, boolean audio) {
        this.extension = extension;
        this.audio = audio;
    }

    public String extension() {
        return extension;
    }

    /** Whether the file holds sound; MOD and MIDI hold notes (MOD with its instruments as samples). */
    public boolean audio() {
        return audio;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "format." + id();
    }

    public static Optional<ExportFormat> ofId(String id) {
        for (ExportFormat f : values()) if (f.id().equals(id)) return Optional.of(f);
        return Optional.empty();
    }
}
