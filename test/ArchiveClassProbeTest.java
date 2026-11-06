package io.github.okaidev.pickupcode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Generated archives only; no proprietary system files, Android runtime or Xposed dependency. */
public final class ArchiveClassProbeTest {
    private static final String TARGET = "com.android.server.am.ContentProviderHelperExtImpl";
    private static final String DESC = "L" + TARGET.replace('.', '/') + ";";
    private static int pass;
    private static Path temp;

    private static void check(String name, boolean condition) {
        if (!condition) throw new AssertionError(name);
        pass++;
        System.out.println("PASS " + name);
    }

    private static File archive(String name, Object... entries) throws IOException {
        Path file = temp.resolve(name + ".jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) entries[i]));
                zip.write((byte[]) entries[i + 1]);
                zip.closeEntry();
            }
        }
        return file.toFile();
    }

    private static boolean contains(String name, Object... entries) throws IOException {
        File jar = archive(name, entries);
        // Use the actual entry point called by ColorOs.findGateJar(), not a test copy.
        return ColorOs.containsGate(jar.getAbsolutePath());
    }

    private static void invalid(String name, byte[] dex) throws IOException {
        try {
            contains(name, "classes.dex", dex);
            throw new AssertionError(name + " must be reported as incomplete, not absent");
        } catch (IOException expected) {
            check(name, true);
        }
    }

    /** Generate a small legacy DEX with one type, optional class_def, map_list and valid hashes. */
    private static byte[] dex(String descriptor, boolean defined, ByteOrder order, String version)
            throws Exception {
        int stringIds = 112;
        int typeIds = 116;
        int classDefs = defined ? 120 : 0;
        int dataOff = defined ? 152 : 120;
        byte[] text = descriptor.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        int length = descriptor.length();
        do {
            int b = length & 0x7f;
            length >>>= 7;
            encoded.write(length == 0 ? b : b | 0x80);
        } while (length != 0);
        encoded.write(text);
        encoded.write(0);
        int mapOff = (dataOff + encoded.size() + 3) & ~3;
        int mapCount = defined ? 6 : 5;
        ByteBuffer data = ByteBuffer.allocate(mapOff + 4 + mapCount * 12).order(order);
        data.put(("dex\n" + version + "\0").getBytes(StandardCharsets.US_ASCII));
        data.putInt(32, data.capacity());
        data.putInt(36, 112);
        data.putInt(40, 0x12345678);
        data.putInt(52, mapOff);
        data.putInt(56, 1);
        data.putInt(60, stringIds);
        data.putInt(64, 1);
        data.putInt(68, typeIds);
        data.putInt(96, defined ? 1 : 0);
        data.putInt(100, classDefs);
        data.putInt(104, data.capacity() - dataOff);
        data.putInt(108, dataOff);
        data.putInt(stringIds, dataOff);
        data.putInt(typeIds, 0);
        if (defined) {
            data.putInt(classDefs, 0);
            data.putInt(classDefs + 4, 1); // public
            data.putInt(classDefs + 8, -1); // no superclass
            data.putInt(classDefs + 16, -1); // no source file
        }
        data.position(dataOff);
        data.put(encoded.toByteArray());
        data.position(mapOff);
        data.putInt(mapCount);
        map(data, 0x0000, 1, 0);
        map(data, 0x0001, 1, stringIds);
        map(data, 0x0002, 1, typeIds);
        if (defined) map(data, 0x0006, 1, classDefs);
        map(data, 0x2002, 1, dataOff);
        map(data, 0x1000, 1, mapOff);
        byte[] bytes = data.array();
        byte[] sha = MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(bytes, 32, bytes.length));
        System.arraycopy(sha, 0, bytes, 12, sha.length);
        Adler32 sum = new Adler32();
        sum.update(bytes, 12, bytes.length - 12);
        data.putInt(8, (int) sum.getValue());
        return bytes;
    }

    private static void map(ByteBuffer data, int type, int count, int offset) {
        data.putShort((short) type).putShort((short) 0).putInt(count).putInt(offset);
    }

    private static byte[] dex(String descriptor, boolean defined) throws Exception {
        return dex(descriptor, defined, ByteOrder.LITTLE_ENDIAN, "039");
    }

    public static void main(String[] args) throws Exception {
        temp = Files.createTempDirectory("pickup-class-probe-");
        try {
            check("DEX-only framework JAR", contains("dex-only", "classes.dex", dex(DESC, true)));
            check("secondary DEX", contains("secondary", "classes.dex", dex("Lother/Class;", true),
                    "classes2.dex", dex(DESC, true)));
            check("two-digit multidex", contains("multidex", "classes10.dex", dex(DESC, true)));
            check("JVM .class compatibility", contains("jvm", TARGET.replace('.', '/') + ".class", new byte[]{1}));
            check("reference is not definition", !contains("reference", "classes.dex", dex(DESC, false)));
            check("wrong package is not match", !contains("wrong-package", "classes.dex",
                    dex("Lother/package/ContentProviderHelperExtImpl;", true)));
            check("nested class is not match", !contains("nested", "classes.dex",
                    dex(DESC.substring(0, DESC.length() - 1) + "$Inner;", true)));
            check("wrong JVM package", !contains("wrong-jvm", "other/ContentProviderHelperExtImpl.class", new byte[]{1}));
            check("non-code descriptor ignored", !contains("resource", "note.txt", DESC.getBytes(StandardCharsets.UTF_8)));
            check("nested unrelated DEX ignored", !contains("assets-dex", "assets/classes.dex", dex(DESC, true)));
            check("empty/optimized-only JAR", !contains("optimized", "META-INF/MANIFEST.MF", new byte[]{1}));
            check("reverse endian DEX", contains("reverse", "classes.dex",
                    dex(DESC, true, ByteOrder.BIG_ENDIAN, "039")));
            check("DEX v035", contains("v035", "classes.dex",
                    dex(DESC, true, ByteOrder.LITTLE_ENDIAN, "035")));
            check("DEX v040", contains("v040", "classes.dex",
                    dex(DESC, true, ByteOrder.LITTLE_ENDIAN, "040")));
            check("malformed first DEX does not hide valid second", contains("recover", "classes.dex", new byte[]{1},
                    "classes2.dex", dex(DESC, true)));
            invalid("truncated header", new byte[10]);
            byte[] bad = dex(DESC, true); bad[0] = 'x'; invalid("bad magic", bad);
            bad = dex(DESC, true); bad[6] = 'x'; invalid("bad version digit", bad);
            bad = dex(DESC, true); bad[6] = '1'; bad[5] = '4'; invalid("unsupported v041", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 1);
            invalid("bad file size", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(36, 1);
            invalid("bad header size", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 0);
            invalid("bad endian tag", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(96, -1);
            invalid("unsigned count overflow", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(100, Integer.MAX_VALUE);
            invalid("table out of bounds", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(120, 2);
            invalid("invalid class index", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(116, 2);
            invalid("invalid string index", bad);
            bad = dex(DESC, true); ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(112, -1);
            invalid("invalid string offset", bad);
            bad = dex(DESC, true); Arrays.fill(bad, 152, 157, (byte) 0x80);
            invalid("malformed ULEB128", bad);
            // Valid multi-byte ULEB128 before an unrelated long class name.
            check("long nonmatching descriptor", !contains("long", "classes.dex",
                    dex("L" + "x".repeat(140) + ";", true)));
            Path notZip = temp.resolve("not-zip.jar"); Files.write(notZip, new byte[]{1,2,3});
            try { ColorOs.containsGate(notZip.toString()); throw new AssertionError("bad ZIP"); }
            catch (IOException expected) { check("invalid ZIP reported", true); }
            try { ColorOs.containsGate(temp.resolve("missing.jar").toString()); throw new AssertionError("missing ZIP"); }
            catch (IOException expected) { check("unreadable/missing path reported", true); }

            try {
                contains("too-large", "classes.dex", new byte[33 * 1024 * 1024]);
                throw new AssertionError("size budget");
            } catch (IOException expected) { check("DEX byte budget enforced", true); }

            Object[] tooMany = new Object[130];
            for (int i = 0; i < 65; i++) {
                tooMany[i * 2] = "classes" + (i == 0 ? "" : String.valueOf(i + 1)) + ".dex";
                tooMany[i * 2 + 1] = dex("Lother/Class;", false);
            }
            try { contains("too-many", tooMany); throw new AssertionError("entry budget"); }
            catch (IOException expected) { check("DEX entry budget enforced", true); }

            if (args.length > 0) {
                check("real system JAR (local read-only, not committed)", ColorOs.containsGate(args[0]));
            }
            System.out.println("ArchiveClassProbeTest: PASS=" + pass + " FAIL=0");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
