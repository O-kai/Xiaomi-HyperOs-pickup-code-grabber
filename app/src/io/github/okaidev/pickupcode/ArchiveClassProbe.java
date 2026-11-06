package io.github.okaidev.pickupcode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only class-definition probe. Does not load/execute system code or require ART/root. */
final class ArchiveClassProbe {
    private static final int DEX_HEADER_SIZE = 112;
    private static final int MAX_DEX_BYTES = 32 * 1024 * 1024;
    private static final Pattern DEX_ENTRY = Pattern.compile("classes(?:[2-9]|[1-9][0-9]+)?\\.dex");
    private static final long MAX_ARCHIVE_DEX_BYTES = 128L * 1024 * 1024;
    private static final int MAX_DEX_ENTRIES = 64;

    private ArchiveClassProbe() { }

    static boolean containsClass(File file, String binaryName) throws IOException {
        String classPath = binaryName.replace('.', '/');
        String descriptor = "L" + classPath + ";";
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry javaClass = zip.getEntry(classPath + ".class");
            if (javaClass != null && !javaClass.isDirectory()) return true;

            long scannedBytes = 0;
            int scannedEntries = 0;
            IOException incomplete = null;
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                // Android JARs contain classes.dex, classes2.dex, ... at the archive root.
                if (entry.isDirectory() || !DEX_ENTRY.matcher(entry.getName()).matches()) {
                    continue;
                }
                if (++scannedEntries > MAX_DEX_ENTRIES) throw invalid("too many DEX entries");
                long size = entry.getSize();
                long heapBudget = Runtime.getRuntime().maxMemory();
                if (size < 0 || size > Math.min(MAX_DEX_BYTES, heapBudget / 4)
                        || size > Math.min(MAX_ARCHIVE_DEX_BYTES, heapBudget / 2) - scannedBytes) {
                    throw invalid("DEX size exceeds probe budget");
                }
                scannedBytes += size;
                try (InputStream in = zip.getInputStream(entry)) {
                    if (containsDexClass(readDex(in, (int) size), descriptor)) return true;
                } catch (OutOfMemoryError e) {
                    // Best-effort diagnostic: low memory is not evidence that the class is absent.
                    throw new IOException("not enough memory for DEX probe", e);
                } catch (IOException e) {
                    // A broken/unsupported DEX must not hide a valid definition in another one.
                    incomplete = e;
                }
            }
            if (incomplete != null) throw incomplete;
            return false;
        }
    }

    private static byte[] readDex(InputStream in, int size) throws IOException {
        byte[] bytes = new byte[size];
        int off = 0;
        while (off < size) {
            int count = in.read(bytes, off, size - off);
            if (count < 0) throw invalid("truncated DEX entry");
            if (count == 0) throw invalid("empty DEX read");
            off += count;
        }
        if (in.read() != -1) throw invalid("DEX entry size mismatch");
        return bytes;
    }

    private static boolean containsDexClass(byte[] bytes, String descriptor) throws IOException {
        if (bytes.length < DEX_HEADER_SIZE || bytes[0] != 'd' || bytes[1] != 'e'
                || bytes[2] != 'x' || bytes[3] != '\n' || bytes[7] != 0) {
            throw invalid("invalid DEX header");
        }
        int version = 0;
        for (int i = 4; i < 7; i++) {
            if (bytes[i] < '0' || bytes[i] > '9') throw invalid("invalid DEX version");
            version = version * 10 + bytes[i] - '0';
        }
        // v041 uses a different container/header layout. Do not interpret it as legacy DEX.
        if (version < 35 || version > 40) throw invalid("unsupported DEX version: " + version);
        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int endian = data.getInt(40);
        if (endian == 0x78563412) data.order(ByteOrder.BIG_ENDIAN);
        else if (endian != 0x12345678) throw invalid("invalid DEX endian tag");
        if (uint(data, 32) != bytes.length || uint(data, 36) != DEX_HEADER_SIZE) {
            throw invalid("invalid DEX size/header size");
        }
        int strings = count(data, 56);
        int stringIds = table(data, 60, strings, 4);
        int types = count(data, 64);
        int typeIds = table(data, 68, types, 4);
        int classes = count(data, 96);
        int classDefs = table(data, 100, classes, 32);
        long dataSize = uint(data, 104);
        long dataOff = uint(data, 108);
        if (dataOff < DEX_HEADER_SIZE || dataOff + dataSize != bytes.length) {
            throw invalid("invalid DEX data section");
        }
        // Only class_defs are evidence of presence. A string/type reference is NOT a definition.
        for (int i = 0; i < classes; i++) {
            long classIndex = uint(data, classDefs + i * 32);
            if (classIndex >= types) throw invalid("invalid class_idx");
            long stringIndex = uint(data, typeIds + (int) classIndex * 4);
            if (stringIndex >= strings) throw invalid("invalid descriptor_idx");
            long stringOff = uint(data, stringIds + (int) stringIndex * 4);
            if (stringOff < dataOff || stringOff >= bytes.length) throw invalid("invalid string_data_off");
            if (matchesString(bytes, (int) stringOff, descriptor)) return true;
        }
        return false;
    }

    private static long uint(ByteBuffer data, int offset) {
        return data.getInt(offset) & 0xffffffffL;
    }

    private static int count(ByteBuffer data, int offset) throws IOException {
        long count = uint(data, offset);
        if (count > Integer.MAX_VALUE) throw invalid("invalid DEX table count");
        return (int) count;
    }

    private static int table(ByteBuffer data, int offset, int count, int width) throws IOException {
        long start = uint(data, offset);
        long end = start + (long) count * width;
        if (start > data.limit() || end > data.limit()
                || (count > 0 && (start < DEX_HEADER_SIZE || (start & 3) != 0))) {
            throw invalid("invalid DEX table bounds");
        }
        return (int) start;
    }

    /** The requested descriptor is ASCII, so no allocation/full MUTF-8 decoder is needed. */
    private static boolean matchesString(byte[] data, int offset, String expected) throws IOException {
        long utf16Length = 0;
        int shift = 0;
        while (true) {
            if (offset >= data.length || shift > 28) throw invalid("invalid ULEB128 length");
            int b = data[offset++] & 0xff;
            if (shift == 28 && (b & 0xf0) != 0) throw invalid("ULEB128 overflow");
            utf16Length |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        if (utf16Length != expected.length()) return false;
        if ((long) offset + expected.length() >= data.length) throw invalid("truncated descriptor");
        for (int i = 0; i < expected.length(); i++) {
            if ((data[offset + i] & 0xff) != expected.charAt(i)) return false;
        }
        return data[offset + expected.length()] == 0;
    }

    private static IOException invalid(String message) {
        return new IOException(message);
    }
}
