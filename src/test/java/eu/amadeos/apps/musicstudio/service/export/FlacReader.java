package eu.amadeos.apps.musicstudio.service.export;

import java.io.IOException;

/**
 * Just enough of a FLAC decoder to check {@link FlacWriter}: 16-bit stereo, fixed predictors, Rice-coded
 * residuals, left/right or mid/side. Checks both CRCs of every frame.
 */
final class FlacReader {
    private final byte[] data;
    private int bytePos;
    private int bitPos;

    private FlacReader(byte[] data) {
        this.data = data;
    }

    /** The samples, left and right in turn. */
    static short[] decode(byte[] flac) throws IOException {
        return new FlacReader(flac).read();
    }

    private short[] read() throws IOException {
        if (data[0] != 'f' || data[1] != 'L' || data[2] != 'a' || data[3] != 'C') throw new IOException("not FLAC");
        bytePos = 4;
        long total = 0;
        boolean last = false;
        while (!last) {
            int header = data[bytePos] & 0xFF;
            last = (header & 0x80) != 0;
            int length = ((data[bytePos + 1] & 0xFF) << 16) | ((data[bytePos + 2] & 0xFF) << 8) | (data[bytePos + 3] & 0xFF);
            if ((header & 0x7F) == 0) {
                // STREAMINFO: total samples are the low 36 bits of bytes 13..17 of the block
                int b = bytePos + 4 + 13;
                total = ((long) (data[b] & 0x0F) << 32) | ((long) (data[b + 1] & 0xFF) << 24)
                        | ((data[b + 2] & 0xFF) << 16) | ((data[b + 3] & 0xFF) << 8) | (data[b + 4] & 0xFF);
            }
            bytePos += 4 + length;
        }
        short[] out = new short[(int) total * 2];
        int done = 0;
        while (done < total) {
            int frameStart = bytePos;
            bitPos = 0;
            if (bits(16) != 0xFFF8) throw new IOException("lost sync at " + bytePos);
            int sizeCode = (int) bits(4);
            bits(4);
            int assignment = (int) bits(4);
            bits(3);
            bits(1);
            int first = (int) bits(8);
            int extra = first < 0x80 ? 0 : first < 0xE0 ? 1 : first < 0xF0 ? 2 : 3;
            for (int i = 0; i < extra; i++) bits(8);
            int n = sizeCode == 12 ? 4096 : sizeCode == 7 ? (int) bits(16) + 1 : -1;
            if (n < 0) throw new IOException("block size code " + sizeCode);
            int crc8 = (int) bits(8);
            if (crc8 != FlacWriter.crc8(java.util.Arrays.copyOfRange(data, frameStart, bytePos - 1))) {
                throw new IOException("header CRC");
            }
            boolean midSide = assignment == 10;
            int[] a = subframe(n, 16);
            int[] b = subframe(n, midSide ? 17 : 16);
            if (bitPos != 0) {
                bytePos++;
                bitPos = 0;
            }
            int crc16 = (int) bits(16);
            if (crc16 != FlacWriter.crc16(java.util.Arrays.copyOfRange(data, frameStart, bytePos - 2))) {
                throw new IOException("frame CRC");
            }
            for (int i = 0; i < n; i++) {
                int left;
                int right;
                if (midSide) {
                    int side = b[i];
                    int mid = (a[i] << 1) | (side & 1);
                    left = (mid + side) >> 1;
                    right = (mid - side) >> 1;
                } else {
                    left = a[i];
                    right = b[i];
                }
                out[2 * (done + i)] = (short) left;
                out[2 * (done + i) + 1] = (short) right;
            }
            done += n;
        }
        return out;
    }

    private int[] subframe(int n, int bps) throws IOException {
        bits(1);
        int type = (int) bits(6);
        bits(1);
        if ((type & 0b111000) != 0b001000) throw new IOException("not a fixed subframe: " + type);
        int order = type & 7;
        int[] x = new int[n];
        for (int i = 0; i < order; i++) x[i] = signed(bps);
        if (bits(2) != 0) throw new IOException("not Rice");
        int po = (int) bits(4);
        long[] res = new long[n];
        for (int p = 0; p < (1 << po); p++) {
            int k = (int) bits(4);
            int from = p == 0 ? order : p * (n >> po);
            int to = (p + 1) * (n >> po);
            for (int i = from; i < to; i++) {
                long q = 0;
                while (bits(1) == 0) q++;
                long u = (q << k) | (k > 0 ? bits(k) : 0);
                res[i] = (u & 1) == 0 ? u >>> 1 : -((u + 1) >>> 1);
            }
        }
        for (int i = order; i < n; i++) {
            long p = switch (order) {
                case 0 -> 0;
                case 1 -> x[i - 1];
                case 2 -> 2L * x[i - 1] - x[i - 2];
                case 3 -> 3L * x[i - 1] - 3L * x[i - 2] + x[i - 3];
                default -> 4L * x[i - 1] - 6L * x[i - 2] + 4L * x[i - 3] - x[i - 4];
            };
            x[i] = (int) (p + res[i]);
        }
        return x;
    }

    private int signed(int n) {
        long v = bits(n);
        return (int) ((v << (64 - n)) >> (64 - n));
    }

    private long bits(int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            int bit = (data[bytePos] >> (7 - bitPos)) & 1;
            v = (v << 1) | bit;
            if (++bitPos == 8) {
                bitPos = 0;
                bytePos++;
            }
        }
        return v;
    }
}
