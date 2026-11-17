package org.libsdl.app;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * The player's disc as plain ISO bytes, whatever file it is stored in (.iso or .rvz).
 * Used to build the Premium Deluxe DLC, whose patch is made against the plain disc.
 */
abstract class DiscSource implements Closeable {
    abstract void readFully(long pos, byte[] b, int off, int len) throws IOException;

    /** Opens path (a file or /proc/self/fd/N) as an ISO or RVZ, picked by its contents. */
    static DiscSource open(String path) throws IOException {
        RandomAccessFile f = new RandomAccessFile(path, "r");
        try {
            if (f.length() >= 4) {
                f.seek(0);
                if (f.readInt() == Rvz.MAGIC) return new Rvz(f);
            }
            return new Iso(f);
        } catch (IOException | RuntimeException e) {
            f.close();
            throw e;
        }
    }

    static final class Iso extends DiscSource {
        private final RandomAccessFile f;
        Iso(RandomAccessFile f) { this.f = f; }
        @Override void readFully(long pos, byte[] b, int off, int len) throws IOException { f.seek(pos); f.readFully(b, off, len); }
        @Override public void close() throws IOException { f.close(); }
    }

    /**
     * Reads a GameCube RVZ (Dolphin's format). Uncompressed and "purged" RVZ files are read directly; RVZ packing
     * (padding stored as a seed for its pseudo-random filler) is expanded the same way Dolphin does.
     */
    static final class Rvz extends DiscSource {
        static final int MAGIC = 0x52565A01;   // "RVZ\1"
        private static final int BLOCK = 0x8000;
        private final RandomAccessFile f;
        private final byte[] discHeader = new byte[0x80];
        private final int chunk;
        private final long[] rawStart, rawEnd;
        private final int[] rawGroup;
        private final long[] groupFileOff;
        private final int[] groupSize, groupPacked;
        private int cachedGroup = -1;
        private byte[] cache;
        private final Lfg lfg = new Lfg();

        Rvz(RandomAccessFile f) throws IOException {
            this.f = f;
            byte[] h = new byte[0x120];
            f.seek(0);
            f.readFully(h);
            if (i32(h, 0x48) != 1) throw new IOException("This .rvz is not a GameCube disc.");
            int comp = i32(h, 0x4C);
            if (comp > 1)
                throw new IOException("This .rvz is compressed (" + (comp == 5 ? "Zstandard" : comp == 2 ? "bzip2" : "LZMA")
                        + "), which the DLC installer can't read. Use your .iso, or an .rvz saved with compression \"None\".");
            chunk = i32(h, 0x54);
            if (chunk <= 0 || chunk > (64 << 20)) throw new IOException("This .rvz is damaged (chunk size).");
            System.arraycopy(h, 0x58, discHeader, 0, 0x80);
            if (i32(h, 0xD8) != 0) throw new IOException("This .rvz is not a GameCube disc.");
            int nRaw = i32(h, 0xFC);
            byte[] raw = read(i64(h, 0x100), nRaw * 24);
            rawStart = new long[nRaw]; rawEnd = new long[nRaw]; rawGroup = new int[nRaw];
            for (int i = 0; i < nRaw; i++) {
                long off = i64(raw, i * 24), size = i64(raw, i * 24 + 8);
                long skip = off % chunk;
                rawStart[i] = off - skip;
                rawEnd[i] = off + size;
                rawGroup[i] = i32(raw, i * 24 + 16);
            }
            int nGroups = i32(h, 0x10C);
            byte[] g = read(i64(h, 0x110), nGroups * 12);
            groupFileOff = new long[nGroups]; groupSize = new int[nGroups]; groupPacked = new int[nGroups];
            for (int i = 0; i < nGroups; i++) {
                groupFileOff[i] = (i32(g, i * 12) & 0xffffffffL) << 2;
                groupSize[i] = i32(g, i * 12 + 4);
                groupPacked[i] = i32(g, i * 12 + 8);
            }
        }

        private byte[] read(long pos, int len) throws IOException {
            byte[] b = new byte[len];
            f.seek(pos);
            f.readFully(b);
            return b;
        }

        @Override void readFully(long pos, byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n;
                if (pos < 0x80) {
                    n = (int) Math.min(len, 0x80 - pos);
                    System.arraycopy(discHeader, (int) pos, b, off, n);
                } else {
                    int e = -1;
                    for (int i = 0; i < rawStart.length; i++) if (pos >= rawStart[i] && pos < rawEnd[i]) { e = i; break; }
                    if (e < 0) {   // outside the stored data: zeros
                        long next = Long.MAX_VALUE;
                        for (int i = 0; i < rawStart.length; i++) if (rawStart[i] > pos) next = Math.min(next, rawStart[i]);
                        n = (int) Math.min(len, next - pos);
                        java.util.Arrays.fill(b, off, off + n, (byte) 0);
                    } else {
                        long k = (pos - rawStart[e]) / chunk;
                        long gStart = rawStart[e] + k * chunk;
                        int gLen = (int) Math.min(chunk, rawEnd[e] - gStart);
                        byte[] data = group(rawGroup[e] + (int) k, gStart, gLen);
                        int in = (int) (pos - gStart);
                        n = Math.min(len, gLen - in);
                        System.arraycopy(data, in, b, off, n);
                    }
                }
                pos += n; off += n; len -= n;
            }
        }

        private byte[] group(int idx, long gStart, int gLen) throws IOException {
            if (idx == cachedGroup) return cache;
            if (idx < 0 || idx >= groupSize.length) throw new IOException("This .rvz is damaged (group " + idx + ").");
            byte[] out = new byte[gLen];
            int size = groupSize[idx] & 0x7fffffff;
            if ((groupSize[idx] & 0x80000000) != 0) throw new IOException("This .rvz has compressed data the DLC installer can't read.");
            if (size != 0) {
                byte[] in = read(groupFileOff[idx], size);
                if (groupPacked[idx] == 0) {
                    System.arraycopy(in, 0, out, 0, Math.min(size, gLen));
                } else {
                    int ip = 0, op = 0;
                    while (op < gLen && ip + 4 <= size) {
                        int s = i32(in, ip); ip += 4;
                        boolean junk = (s & 0x80000000) != 0;
                        s &= 0x7fffffff;
                        int n = Math.min(s, gLen - op);
                        if (junk) {
                            lfg.setSeed(in, ip); ip += 68;
                            lfg.forward((int) ((gStart + op) % BLOCK));
                            lfg.get(out, op, n);
                        } else {
                            System.arraycopy(in, ip, out, op, n); ip += s;
                        }
                        op += n;
                    }
                }
            }
            cachedGroup = idx;
            cache = out;
            return out;
        }

        @Override public void close() throws IOException { f.close(); }

        private static int i32(byte[] b, int o) {
            return ((b[o] & 0xff) << 24) | ((b[o + 1] & 0xff) << 16) | ((b[o + 2] & 0xff) << 8) | (b[o + 3] & 0xff);
        }

        private static long i64(byte[] b, int o) { return ((i32(b, o) & 0xffffffffL) << 32) | (i32(b, o + 4) & 0xffffffffL); }
    }

    /** Dolphin's lagged Fibonacci generator: rebuilds the disc's padding from a 68-byte seed. */
    static final class Lfg {
        private static final int K = 521, J = 32, BYTES = K * 4;
        private final int[] buf = new int[K];
        private int pos;

        void setSeed(byte[] s, int o) {
            for (int i = 0; i < 17; i++)
                buf[i] = ((s[o + i * 4] & 0xff) << 24) | ((s[o + i * 4 + 1] & 0xff) << 16) | ((s[o + i * 4 + 2] & 0xff) << 8) | (s[o + i * 4 + 3] & 0xff);
            for (int i = 17; i < K; i++) buf[i] = (buf[i - 17] << 23) ^ (buf[i - 16] >>> 9) ^ buf[i - 1];
            for (int i = 0; i < K; i++) { int x = buf[i]; buf[i] = (x & 0xFF00FFFF) | ((x >>> 2) & 0x00FF0000); }
            for (int i = 0; i < 4; i++) step();
            pos = 0;
        }

        private void step() {
            for (int i = 0; i < J; i++) buf[i] ^= buf[i + K - J];
            for (int i = J; i < K; i++) buf[i] ^= buf[i - J];
        }

        void forward(int n) {
            pos += n;
            while (pos >= BYTES) { step(); pos -= BYTES; }
        }

        void get(byte[] out, int off, int n) {
            for (int i = 0; i < n; i++) {
                out[off + i] = (byte) (buf[pos >> 2] >>> (24 - 8 * (pos & 3)));
                if (++pos == BYTES) { step(); pos = 0; }
            }
        }
    }
}
