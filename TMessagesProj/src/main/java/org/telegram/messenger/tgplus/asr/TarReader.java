package org.telegram.messenger.tgplus.asr;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Minimal tar reader: regular files and directories, ustar prefixes, GNU long names and pax path/size records.
 * Pure Java so it can be tested without Android. Entry names are only used to match an allow-list; nothing is
 * ever written to a path taken from the archive.
 */
public final class TarReader implements Closeable {

    public static final class Entry {
        public final String name;
        public final long size;
        public final boolean regularFile;

        Entry(String name, long size, boolean regularFile) {
            this.name = name;
            this.size = size;
            this.regularFile = regularFile;
        }
    }

    private static final int BLOCK = 512;
    private static final int MAX_METADATA = 1 << 20;

    private final InputStream in;
    private long remaining;
    private long padding;
    private boolean finished;

    public TarReader(InputStream in) {
        this.in = in;
    }

    /** Next entry, or null at the end of the archive. Unread data of the previous entry is skipped. */
    public Entry next() throws IOException {
        if (finished) return null;
        skipFully(remaining + padding);
        remaining = 0;
        padding = 0;

        String longName = null;
        long paxSize = -1;
        byte[] header = new byte[BLOCK];
        while (true) {
            if (!readBlock(header)) {
                finished = true;
                return null;
            }
            if (isZero(header)) {
                finished = true;
                return null;
            }
            if (!checksumOk(header)) throw new IOException("BAD_TAR: header checksum");
            char type = (char) (header[156] & 0xff);
            long size = parseNumber(header, 124, 12);
            if (size < 0) throw new IOException("BAD_TAR: size");

            if (type == 'L' || type == 'x' || type == 'g' || type == 'K') {
                if (size > MAX_METADATA) throw new IOException("BAD_TAR: metadata too large");
                byte[] data = new byte[(int) size];
                readFully(data);
                skipFully(paddingFor(size));
                if (type == 'L') {
                    longName = cString(data, 0, data.length);
                } else if (type == 'x') {
                    String[] records = parsePax(data);
                    if (records[0] != null) longName = records[0];
                    if (records[1] != null) paxSize = Long.parseLong(records[1]);
                }
                continue;
            }

            String name = longName != null ? longName : entryName(header);
            if (paxSize >= 0) size = paxSize;
            boolean regular = type == '0' || type == 0;
            remaining = size;
            padding = paddingFor(size);
            return new Entry(name, size, regular);
        }
    }

    /** Reads data of the current entry; -1 at its end. */
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0) return -1;
        int n = in.read(buffer, offset, (int) Math.min(length, remaining));
        if (n < 0) throw new EOFException("BAD_TAR: truncated entry");
        remaining -= n;
        return n;
    }

    /** The current entry's data as a stream. */
    public InputStream data() {
        return new InputStream() {
            private final byte[] one = new byte[1];

            @Override
            public int read() throws IOException {
                int n = TarReader.this.read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return TarReader.this.read(b, off, len);
            }
        };
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private static long paddingFor(long size) {
        long rest = size % BLOCK;
        return rest == 0 ? 0 : BLOCK - rest;
    }

    private boolean readBlock(byte[] block) throws IOException {
        int read = 0;
        while (read < BLOCK) {
            int n = in.read(block, read, BLOCK - read);
            if (n < 0) {
                if (read == 0) return false;
                throw new EOFException("BAD_TAR: truncated header");
            }
            read += n;
        }
        return true;
    }

    private void readFully(byte[] buffer) throws IOException {
        int read = 0;
        while (read < buffer.length) {
            int n = in.read(buffer, read, buffer.length - read);
            if (n < 0) throw new EOFException("BAD_TAR: truncated metadata");
            read += n;
        }
    }

    private void skipFully(long count) throws IOException {
        byte[] scratch = null;
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (scratch == null) scratch = new byte[8192];
                int n = in.read(scratch, 0, (int) Math.min(scratch.length, count));
                if (n < 0) throw new EOFException("BAD_TAR: truncated archive");
                skipped = n;
            }
            count -= skipped;
        }
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) if (b != 0) return false;
        return true;
    }

    private static boolean checksumOk(byte[] header) {
        long stored = parseNumber(header, 148, 8);
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
        return stored == sum;
    }

    /** Octal text, or GNU base-256 when the top bit of the first byte is set. */
    private static long parseNumber(byte[] b, int offset, int length) {
        if ((b[offset] & 0x80) != 0) {
            long value = b[offset] & 0x7f;
            for (int i = 1; i < length; i++) {
                if (value > (Long.MAX_VALUE >> 8)) return -1;
                value = (value << 8) | (b[offset + i] & 0xff);
            }
            return value;
        }
        long value = 0;
        int i = offset, end = offset + length;
        while (i < end && (b[i] == ' ' || b[i] == 0)) i++;
        boolean any = false;
        while (i < end && b[i] >= '0' && b[i] <= '7') {
            value = value * 8 + (b[i] - '0');
            any = true;
            i++;
        }
        return any ? value : (isBlankField(b, offset, length) ? 0 : -1);
    }

    private static boolean isBlankField(byte[] b, int offset, int length) {
        for (int i = offset; i < offset + length; i++) if (b[i] != 0 && b[i] != ' ') return false;
        return true;
    }

    private static String entryName(byte[] header) {
        String name = cString(header, 0, 100);
        boolean ustar = header[257] == 'u' && header[258] == 's' && header[259] == 't' && header[260] == 'a' && header[261] == 'r';
        if (ustar) {
            String prefix = cString(header, 345, 155);
            if (!prefix.isEmpty()) return prefix + "/" + name;
        }
        return name;
    }

    private static String cString(byte[] b, int offset, int length) {
        int end = offset;
        int limit = Math.min(b.length, offset + length);
        while (end < limit && b[end] != 0) end++;
        return new String(Arrays.copyOfRange(b, offset, end), StandardCharsets.UTF_8);
    }

    /** Pax records are "<length> <key>=<value>\n"; returns {path, size}. */
    private static String[] parsePax(byte[] data) throws IOException {
        String[] out = new String[2];
        int pos = 0;
        while (pos < data.length) {
            int space = pos;
            while (space < data.length && data[space] != ' ') space++;
            if (space >= data.length) throw new IOException("BAD_TAR: pax record");
            int length;
            try {
                length = Integer.parseInt(new String(data, pos, space - pos, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IOException("BAD_TAR: pax length");
            }
            if (length <= space - pos + 1 || pos + length > data.length) throw new IOException("BAD_TAR: pax length");
            String record = new String(data, space + 1, pos + length - space - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String key = record.substring(0, eq), value = record.substring(eq + 1);
                if (key.equals("path")) out[0] = value;
                else if (key.equals("size")) out[1] = value;
            }
            pos += length;
        }
        return out;
    }
}
