package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.service.synth.Audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 16-bit stereo WAV; a comment (the recipe) goes into the {@code LIST INFO ICMT} chunk. */
public final class WavWriter {
    public void write(Audio audio, String comment, OutputStream out) throws IOException {
        byte[] info = info(comment);
        int data = audio.frames() * 4;
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        ascii(header, "RIFF");
        int32(header, 4 + 24 + 8 + data + info.length);
        ascii(header, "WAVE");
        ascii(header, "fmt ");
        int32(header, 16);
        int16(header, 1);
        int16(header, 2);
        int32(header, audio.sampleRate());
        int32(header, audio.sampleRate() * 4);
        int16(header, 4);
        int16(header, 16);
        header.write(info);
        ascii(header, "data");
        int32(header, data);
        out.write(header.toByteArray());
        byte[] buffer = new byte[4 * 4096];
        short[] pcm = audio.toPcm16();
        for (int from = 0; from < pcm.length; from += buffer.length / 2) {
            int n = Math.min(buffer.length / 2, pcm.length - from);
            for (int i = 0; i < n; i++) {
                buffer[2 * i] = (byte) pcm[from + i];
                buffer[2 * i + 1] = (byte) (pcm[from + i] >> 8);
            }
            out.write(buffer, 0, 2 * n);
        }
    }

    private static byte[] info(String comment) {
        if (comment == null || comment.isEmpty()) return new byte[0];
        byte[] text = (comment + "\0").getBytes(StandardCharsets.UTF_8);
        int padded = text.length + (text.length % 2);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        ascii(b, "LIST");
        int32(b, 4 + 8 + padded);
        ascii(b, "INFO");
        ascii(b, "ICMT");
        int32(b, text.length);
        b.writeBytes(text);
        if (padded > text.length) b.write(0);
        return b.toByteArray();
    }

    static void ascii(ByteArrayOutputStream b, String s) {
        b.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    static void int32(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
        b.write(v >> 16);
        b.write(v >> 24);
    }

    static void int16(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
    }
}
