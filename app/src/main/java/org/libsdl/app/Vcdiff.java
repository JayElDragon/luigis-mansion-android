package org.libsdl.app;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Adler32;

/**
 * Applies a VCDIFF delta (RFC 3284) as written by xdelta3 (e.g. the Premium Deluxe .xdelta) to a source file.
 * Default code table, VCD_SOURCE windows and xdelta3's per-window Adler-32 checksum are supported; secondary
 * compression and custom code tables are not (the player gets a clear message).
 */
final class Vcdiff {
    private Vcdiff() { }

    interface Progress { void update(long written); }

    private static final int NOOP = 0, ADD = 1, RUN = 2, COPY = 3;
    private static final int[][] TABLE = table();

    private static int[][] table() {
        int[][] t = new int[256][];
        int i = 0;
        t[i++] = new int[] {RUN, 0, 0, NOOP, 0, 0};
        for (int s = 0; s < 18; s++) t[i++] = new int[] {ADD, s, 0, NOOP, 0, 0};
        for (int m = 0; m < 9; m++) {
            t[i++] = new int[] {COPY, 0, m, NOOP, 0, 0};
            for (int s = 4; s < 19; s++) t[i++] = new int[] {COPY, s, m, NOOP, 0, 0};
        }
        for (int m = 0; m < 6; m++)
            for (int a = 1; a < 5; a++)
                for (int c = 4; c < 7; c++) t[i++] = new int[] {ADD, a, 0, COPY, c, m};
        for (int m = 6; m < 9; m++)
            for (int a = 1; a < 5; a++) t[i++] = new int[] {ADD, a, 0, COPY, 4, m};
        for (int m = 0; m < 9; m++) t[i++] = new int[] {COPY, 4, m, ADD, 1, 0};
        return t;
    }

    private static long varint(DataInputStream in) throws IOException {
        long n = 0;
        for (int k = 0; k < 10; k++) {
            int c = in.readUnsignedByte();
            n = (n << 7) | (c & 0x7f);
            if (c < 0x80) return n;
        }
        throw new IOException("Damaged patch (bad number)");
    }

    /** Reads a varint from buf at pos[0]. */
    private static int varint(byte[] buf, int[] pos, int end) throws IOException {
        long n = 0;
        while (true) {
            if (pos[0] >= end) throw new IOException("Damaged patch (section ends early)");
            int c = buf[pos[0]++] & 0xff;
            n = (n << 7) | (c & 0x7f);
            if (c < 0x80) return (int) n;
        }
    }

    static void apply(DiscSource source, InputStream deltaStream, OutputStream out, Progress progress) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(deltaStream, 1 << 20));
        if (in.readUnsignedByte() != 0xD6 || in.readUnsignedByte() != 0xC3 || in.readUnsignedByte() != 0xC4 || in.readUnsignedByte() != 0)
            throw new IOException("This is not an xdelta / VCDIFF patch file.");
        int ind = in.readUnsignedByte();
        if ((ind & 1) != 0) throw new IOException("This patch uses secondary compression, which isn't supported. Use the original Premium Deluxe .xdelta.");
        if ((ind & 2) != 0) throw new IOException("This patch uses a custom code table, which isn't supported.");
        if ((ind & 4) != 0) in.skipBytes((int) varint(in));
        byte[] seg = new byte[0], tgt = new byte[0], data = new byte[0], inst = new byte[0], addr = new byte[0];
        long written = 0;
        int[] near = new int[4];
        int[] same = new int[768];
        Adler32 adler = new Adler32();
        while (true) {
            int wi;
            try { wi = in.readUnsignedByte(); } catch (EOFException eof) { break; }
            int segLen = 0;
            if ((wi & 3) != 0) {
                if ((wi & 2) != 0) throw new IOException("This patch type (target windows) isn't supported.");
                segLen = (int) varint(in);
                long segPos = varint(in);
                if (seg.length < segLen) seg = new byte[segLen];
                source.readFully(segPos, seg, 0, segLen);
            }
            varint(in);  // delta encoding length
            int tlen = (int) varint(in);
            if (in.readUnsignedByte() != 0) throw new IOException("This patch uses compressed sections, which aren't supported.");
            int dlen = (int) varint(in), ilen = (int) varint(in), alen = (int) varint(in);
            long cks = -1;
            if ((wi & 4) != 0) cks = in.readInt() & 0xffffffffL;
            if (data.length < dlen) data = new byte[dlen];
            if (inst.length < ilen) inst = new byte[ilen];
            if (addr.length < alen) addr = new byte[alen];
            if (tgt.length < tlen) tgt = new byte[tlen];
            in.readFully(data, 0, dlen);
            in.readFully(inst, 0, ilen);
            in.readFully(addr, 0, alen);
            int tp = 0, dp = 0, nxt = 0;
            int[] ip = {0}, ap = {0};
            java.util.Arrays.fill(near, 0);
            java.util.Arrays.fill(same, 0);
            while (ip[0] < ilen) {
                int[] e = TABLE[inst[ip[0]++] & 0xff];
                for (int h = 0; h < 2; h++) {
                    int type = e[h * 3], size = e[h * 3 + 1], mode = e[h * 3 + 2];
                    if (type == NOOP) continue;
                    if (size == 0) size = varint(inst, ip, ilen);
                    if (tp + size > tlen) throw new IOException("Damaged patch (window overflow)");
                    if (type == ADD) {
                        System.arraycopy(data, dp, tgt, tp, size);
                        dp += size; tp += size;
                    } else if (type == RUN) {
                        byte b = data[dp++];
                        java.util.Arrays.fill(tgt, tp, tp + size, b);
                        tp += size;
                    } else {
                        int here = segLen + tp, a;
                        if (mode == 0) a = varint(addr, ap, alen);
                        else if (mode == 1) a = here - varint(addr, ap, alen);
                        else if (mode < 6) a = near[mode - 2] + varint(addr, ap, alen);
                        else a = same[(mode - 6) * 256 + (addr[ap[0]++] & 0xff)];
                        near[nxt] = a; nxt = (nxt + 1) & 3; same[a % 768] = a;
                        if (a + size <= segLen) {
                            System.arraycopy(seg, a, tgt, tp, size);
                            tp += size;
                        } else {
                            for (int k = 0; k < size; k++) {
                                int q = a + k;
                                tgt[tp++] = q < segLen ? seg[q] : tgt[q - segLen];
                            }
                        }
                    }
                }
            }
            if (tp != tlen) throw new IOException("Damaged patch (short window)");
            if (cks >= 0) {
                adler.reset();
                adler.update(tgt, 0, tlen);
                if (adler.getValue() != cks)
                    throw new IOException("The patch doesn't match this disc. Use an unmodified USA (GLME01) copy of Luigi's Mansion.");
            }
            out.write(tgt, 0, tlen);
            written += tlen;
            if (progress != null) progress.update(written);
        }
    }
}
