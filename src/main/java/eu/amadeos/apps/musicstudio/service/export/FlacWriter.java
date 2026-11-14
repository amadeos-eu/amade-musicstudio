package eu.amadeos.apps.musicstudio.service.export;

import eu.amadeos.apps.musicstudio.service.synth.Audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small lossless FLAC encoder: 16-bit stereo in blocks of 4096 samples, each channel predicted by the best of
 * the fixed polynomial predictors (order 0 to 4) and its residual Rice-coded in up to 16 partitions; left and
 * right or mid and side, whichever is smaller. A comment (the recipe) goes into a Vorbis comment block.
 */
public final class FlacWriter {
    static final int BLOCK = 4096;
    private static final int MAX_PARTITION_ORDER = 4;

    public void write(Audio audio, String comment, OutputStream out) throws IOException {
        short[] pcm = audio.toPcm16();
        int frames = audio.frames();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.write(streamInfo(audio.sampleRate(), frames));
        out.write(vorbisComment(comment));
        int[] left = new int[BLOCK];
        int[] right = new int[BLOCK];
        int frameNumber = 0;
        for (int start = 0; start < frames; start += BLOCK) {
            int n = Math.min(BLOCK, frames - start);
            for (int i = 0; i < n; i++) {
                left[i] = pcm[2 * (start + i)];
                right[i] = pcm[2 * (start + i) + 1];
            }
            out.write(frame(left, right, n, frameNumber++));
        }
    }

    private static byte[] streamInfo(int sampleRate, long totalSamples) {
        BitWriter b = new BitWriter();
        b.bits(0, 1); // not the last block
        b.bits(0, 7); // STREAMINFO
        b.bits(34, 24);
        b.bits(BLOCK, 16);
        b.bits(BLOCK, 16);
        b.bits(0, 24); // frame sizes unknown
        b.bits(0, 24);
        b.bits(sampleRate, 20);
        b.bits(1, 3); // two channels
        b.bits(15, 5); // 16 bits
        b.bits(totalSamples >>> 32, 4);
        b.bits(totalSamples & 0xFFFFFFFFL, 32);
        for (int i = 0; i < 16; i++) b.bits(0, 8); // no MD5
        return b.bytes();
    }

    private static byte[] vorbisComment(String comment) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] vendor = "amadeOS Music Studio".getBytes(StandardCharsets.UTF_8);
        le32(body, vendor.length);
        body.writeBytes(vendor);
        boolean has = comment != null && !comment.isEmpty();
        le32(body, has ? 1 : 0);
        if (has) {
            byte[] c = ("COMMENT=" + comment).getBytes(StandardCharsets.UTF_8);
            le32(body, c.length);
            body.writeBytes(c);
        }
        byte[] data = body.toByteArray();
        ByteArrayOutputStream block = new ByteArrayOutputStream();
        block.write(0x80 | 4); // the last metadata block, VORBIS_COMMENT
        block.write(data.length >> 16);
        block.write(data.length >> 8);
        block.write(data.length);
        block.writeBytes(data);
        return block.toByteArray();
    }

    private static void le32(ByteArrayOutputStream b, int v) {
        b.write(v);
        b.write(v >> 8);
        b.write(v >> 16);
        b.write(v >> 24);
    }

    // ---------------------------------------------------------------- frames

    private static byte[] frame(int[] left, int[] right, int n, int number) {
        int[] mid = new int[n];
        int[] side = new int[n];
        for (int i = 0; i < n; i++) {
            mid[i] = (left[i] + right[i]) >> 1;
            side[i] = left[i] - right[i];
        }
        Subframe l = Subframe.best(left, n, 16);
        Subframe r = Subframe.best(right, n, 16);
        Subframe m = Subframe.best(mid, n, 16);
        Subframe s = Subframe.best(side, n, 17);
        boolean midSide = m.bits + s.bits < l.bits + r.bits;

        BitWriter b = new BitWriter();
        b.bits(0xFFF8, 16); // sync, fixed block size
        boolean full = n == BLOCK;
        b.bits(full ? 12 : 7, 4); // 4096, or the size at the end of the header
        b.bits(9, 4); // 44.1 kHz
        b.bits(midSide ? 10 : 1, 4); // mid/side or left/right
        b.bits(4, 3); // 16 bits
        b.bits(0, 1);
        utf8(b, number);
        if (!full) b.bits(n - 1, 16);
        b.bits(crc8(b.bytes()), 8);
        if (midSide) {
            m.write(b, 16);
            s.write(b, 17);
        } else {
            l.write(b, 16);
            r.write(b, 16);
        }
        b.align();
        int crc = crc16(b.bytes());
        b.bits(crc, 16);
        return b.bytes();
    }

    /** One channel of a frame, coded with a fixed predictor. */
    private static final class Subframe {
        final int[] samples;
        final int n;
        final int order;
        final int partitionOrder;
        final int[] parameters;
        final long[] residual;
        final long bits;

        private Subframe(int[] samples, int n, int order, int partitionOrder, int[] parameters, long[] residual,
                         long bits) {
            this.samples = samples;
            this.n = n;
            this.order = order;
            this.partitionOrder = partitionOrder;
            this.parameters = parameters;
            this.residual = residual;
            this.bits = bits;
        }

        static Subframe best(int[] samples, int n, int bitsPerSample) {
            Subframe best = null;
            for (int order = 0; order <= Math.min(4, n - 1); order++) {
                long[] res = residual(samples, n, order);
                for (int po = 0; po <= MAX_PARTITION_ORDER; po++) {
                    if (n % (1 << po) != 0 || (n >> po) <= order) break;
                    int partitions = 1 << po;
                    int[] params = new int[partitions];
                    long total = 8 + (long) order * bitsPerSample + 6;
                    boolean ok = true;
                    for (int p = 0; p < partitions; p++) {
                        int from = p == 0 ? order : p * (n >> po);
                        int to = (p + 1) * (n >> po);
                        long sum = 0;
                        for (int i = from; i < to; i++) sum += zigzag(res[i]);
                        int count = to - from;
                        int k = 0;
                        if (count > 0) {
                            long mean = sum / count;
                            while (k < 14 && (1L << (k + 1)) <= mean) k++;
                        }
                        long cost = 4;
                        for (int i = from; i < to; i++) cost += 1 + k + (zigzag(res[i]) >>> k);
                        if (cost > Integer.MAX_VALUE) {
                            ok = false;
                            break;
                        }
                        params[p] = k;
                        total += cost;
                    }
                    if (ok && (best == null || total < best.bits)) {
                        best = new Subframe(samples, n, order, po, params, res, total);
                    }
                }
            }
            return best;
        }

        static long[] residual(int[] x, int n, int order) {
            long[] r = new long[n];
            for (int i = order; i < n; i++) {
                r[i] = switch (order) {
                    case 0 -> x[i];
                    case 1 -> (long) x[i] - x[i - 1];
                    case 2 -> (long) x[i] - 2L * x[i - 1] + x[i - 2];
                    case 3 -> (long) x[i] - 3L * x[i - 1] + 3L * x[i - 2] - x[i - 3];
                    default -> (long) x[i] - 4L * x[i - 1] + 6L * x[i - 2] - 4L * x[i - 3] + x[i - 4];
                };
            }
            return r;
        }

        void write(BitWriter b, int bitsPerSample) {
            b.bits(0, 1);
            b.bits(0b001000 | order, 6); // FIXED
            b.bits(0, 1); // no wasted bits
            for (int i = 0; i < order; i++) b.signed(samples[i], bitsPerSample);
            b.bits(0, 2); // Rice, 4-bit parameters
            b.bits(partitionOrder, 4);
            int partitions = 1 << partitionOrder;
            for (int p = 0; p < partitions; p++) {
                int k = parameters[p];
                b.bits(k, 4);
                int from = p == 0 ? order : p * (n >> partitionOrder);
                int to = (p + 1) * (n >> partitionOrder);
                for (int i = from; i < to; i++) {
                    long u = zigzag(residual[i]);
                    b.unary(u >>> k);
                    if (k > 0) b.bits(u & ((1L << k) - 1), k);
                }
            }
        }

        static long zigzag(long v) {
            return v >= 0 ? v << 1 : ((-v) << 1) - 1;
        }
    }

    private static void utf8(BitWriter b, int v) {
        if (v < 0x80) {
            b.bits(v, 8);
        } else if (v < 0x800) {
            b.bits(0xC0 | (v >> 6), 8);
            b.bits(0x80 | (v & 0x3F), 8);
        } else if (v < 0x10000) {
            b.bits(0xE0 | (v >> 12), 8);
            b.bits(0x80 | ((v >> 6) & 0x3F), 8);
            b.bits(0x80 | (v & 0x3F), 8);
        } else {
            b.bits(0xF0 | (v >> 18), 8);
            b.bits(0x80 | ((v >> 12) & 0x3F), 8);
            b.bits(0x80 | ((v >> 6) & 0x3F), 8);
            b.bits(0x80 | (v & 0x3F), 8);
        }
    }

    static int crc8(byte[] data) {
        int crc = 0;
        for (byte d : data) {
            crc ^= d & 0xFF;
            for (int i = 0; i < 8; i++) crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
        }
        return crc;
    }

    static int crc16(byte[] data) {
        int crc = 0;
        for (byte d : data) {
            crc ^= (d & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x8005) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return crc;
    }

    /** Writes bits from the most significant on. */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void bits(long value, int n) {
            for (int i = n - 1; i >= 0; i--) {
                buffer = (buffer << 1) | ((value >>> i) & 1);
                if (++count == 8) {
                    out.write((int) buffer);
                    buffer = 0;
                    count = 0;
                }
            }
        }

        void signed(int value, int n) {
            bits(value & ((1L << n) - 1), n);
        }

        void unary(long zeros) {
            for (long i = 0; i < zeros; i++) bits(0, 1);
            bits(1, 1);
        }

        void align() {
            while (count != 0) bits(0, 1);
        }

        /** The whole bytes written so far. */
        byte[] bytes() {
            return out.toByteArray();
        }
    }
}
