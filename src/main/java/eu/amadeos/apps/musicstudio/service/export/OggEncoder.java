package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.service.synth.Audio;
import eu.amadeos.commons.process.CommandRunner;
import eu.amadeos.commons.process.ProgramResult;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Writes Ogg Vorbis with the desktop's GStreamer ({@code gst-launch-1.0} with {@code vorbisenc}): the sound goes
 * to a temporary WAV file first, whose comment GStreamer carries over into the Ogg file.
 */
public final class OggEncoder {
    static final String PROGRAM = "gst-launch-1.0";
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    private final CommandRunner runner;
    private final WavWriter wav;

    public OggEncoder(CommandRunner runner, WavWriter wav) {
        this.runner = runner;
        this.wav = wav;
    }

    /**
     * Encodes to {@code target}.
     *
     * @throws eu.amadeos.commons.process.ProgramMissingException if GStreamer's tools are not installed
     */
    public void write(Audio audio, String comment, Path target) throws IOException, InterruptedException {
        Path temp = Files.createTempFile("amade-musicstudio-", ".wav");
        try {
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(temp))) {
                wav.write(audio, comment, out);
            }
            ProgramResult result = runner.run(command(temp, target), TIMEOUT);
            if (!result.succeeded() || !Files.isRegularFile(target) || Files.size(target) == 0) {
                Files.deleteIfExists(target);
                throw new IOException(result.failure(PROGRAM));
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** gst-launch escapes each argument itself, so paths with spaces need no quotes. */
    static List<String> command(Path wavFile, Path target) {
        return List.of(PROGRAM, "-q",
                "filesrc", "location=" + wavFile.toAbsolutePath(), "!",
                "wavparse", "!", "audioconvert", "!",
                "vorbisenc", "quality=0.6", "!", "oggmux", "!",
                "filesink", "location=" + target.toAbsolutePath());
    }
}
