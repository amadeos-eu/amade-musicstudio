package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.domain.Part;
import eu.amadeos.apps.musicstudio.domain.Recipe;
import eu.amadeos.apps.musicstudio.repository.MusicFiles;
import eu.amadeos.apps.musicstudio.service.compose.Composer;
import eu.amadeos.apps.musicstudio.service.compose.Song;
import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.apps.musicstudio.service.synth.AudioRenderer;
import eu.amadeos.apps.musicstudio.service.synth.Stems;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Composes and renders a recipe anew and writes it in one format; with stems, also one file per part next to it
 * ({@code lofi-4711-melody.wav}). Every file appears only when it is complete.
 */
public final class ExportService {
    private final Composer composer;
    private final AudioRenderer renderer;
    private final Executor workers;
    private final WavWriter wav;
    private final FlacWriter flac;
    private final OggEncoder ogg;
    private final ModWriter mod;
    private final MidiWriter midi;

    public ExportService(Composer composer, AudioRenderer renderer, Executor workers, OggEncoder ogg) {
        this.composer = composer;
        this.renderer = renderer;
        this.workers = workers;
        this.wav = new WavWriter();
        this.flac = new FlacWriter();
        this.ogg = ogg;
        this.mod = new ModWriter(renderer);
        this.midi = new MidiWriter();
    }

    /**
     * Writes the piece.
     *
     * @return the files written, the whole piece first
     * @throws CancellationException as soon as {@code cancelled} says so; nothing half-written stays behind
     */
    public List<Path> export(Recipe recipe, ExportOptions options, Path target, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Song song = composer.compose(recipe, options.loop());
        String comment = options.includeRecipe() ? RecipeTag.of(recipe) : null;
        List<Path> written = new ArrayList<>();
        switch (options.format()) {
            case MOD -> written.add(write(target, out -> mod.write(song, comment, out)));
            case MIDI -> written.add(write(target, out -> midi.write(song, comment, out)));
            default -> {
                Stems stems = renderer.render(song, workers, cancelled);
                written.add(writeAudio(options.format(), renderer.mix(stems, recipe), comment, target));
                if (options.stems()) {
                    for (Part part : Part.values()) {
                        if (cancelled.getAsBoolean()) throw new CancellationException();
                        Audio one = renderer.mix(stems, recipe, EnumSet.of(part));
                        written.add(writeAudio(options.format(), one, comment, stemPath(target, part)));
                    }
                }
            }
        }
        return written;
    }

    /** {@code lofi-4711.wav} becomes {@code lofi-4711-drums.wav}. */
    public static Path stemPath(Path target, Part part) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return target.resolveSibling(base + "-" + part.id() + extension);
    }

    /** The target with the format's extension. */
    public static Path withExtension(Path target, ExportFormat format) {
        String name = target.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith("." + format.extension())) return target;
        return target.resolveSibling(name + "." + format.extension());
    }

    private Path writeAudio(ExportFormat format, Audio audio, String comment, Path target)
            throws IOException, InterruptedException {
        return switch (format) {
            case WAV -> write(target, out -> wav.write(audio, comment, out));
            case FLAC -> write(target, out -> flac.write(audio, comment, out));
            case OGG -> {
                Path temporary = MusicFiles.temporary(target);
                try {
                    ogg.write(audio, comment, temporary);
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                yield target;
            }
            default -> throw new IllegalArgumentException("Not a sound format: " + format);
        };
    }

    private interface Writing {
        void to(OutputStream out) throws IOException;
    }

    private static Path write(Path target, Writing writing) throws IOException {
        Path temporary = MusicFiles.temporary(target);
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temporary), 1 << 16)) {
                writing.to(out);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }
}
