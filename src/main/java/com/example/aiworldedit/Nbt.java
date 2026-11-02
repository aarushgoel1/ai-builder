package com.example.aiworldedit;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Minimal NBT reader/writer (no Minecraft classes, so it works the same in every game version).
 * Compounds become LinkedHashMap, lists ArrayList, arrays byte[]/int[]/long[], numbers boxed.
 */
public final class Nbt {
    private Nbt() {}

    /** Wraps a value so the writer knows which numeric NBT type to use. */
    public record Tagged(byte type, Object value) {}

    public static Tagged shortTag(int v) { return new Tagged((byte) 2, (short) v); }

    /** Reads a (normally gzipped) NBT file; returns the root compound. */
    public static Map<String, Object> read(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int b1 = in.read(), b2 = in.read();
        in.reset();
        InputStream src = (b1 == 0x1f && b2 == 0x8b) ? new GZIPInputStream(in) : in;
        DataInputStream d = new DataInputStream(new BufferedInputStream(src));
        byte type = d.readByte();
        if (type != 10) throw new IOException("not an NBT compound");
        d.readUTF(); // root name
        return readCompound(d, 0);
    }

    @SuppressWarnings("unchecked")
    private static Object readPayload(DataInputStream d, byte type, int depth) throws IOException {
        if (depth > 64) throw new IOException("NBT nested too deeply");
        switch (type) {
            case 1: return d.readByte();
            case 2: return d.readShort();
            case 3: return d.readInt();
            case 4: return d.readLong();
            case 5: return d.readFloat();
            case 6: return d.readDouble();
            case 7: { int n = checkLen(d.readInt()); byte[] a = new byte[n]; d.readFully(a); return a; }
            case 8: return d.readUTF();
            case 9: {
                byte et = d.readByte();
                int n = checkLen(d.readInt());
                List<Object> list = new ArrayList<>(Math.min(n, 4096));
                for (int i = 0; i < n; i++) list.add(readPayload(d, et, depth + 1));
                return list;
            }
            case 10: return readCompound(d, depth + 1);
            case 11: { int n = checkLen(d.readInt()); int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = d.readInt(); return a; }
            case 12: { int n = checkLen(d.readInt()); long[] a = new long[n]; for (int i = 0; i < n; i++) a[i] = d.readLong(); return a; }
            default: throw new IOException("bad NBT tag type " + type);
        }
    }

    private static int checkLen(int n) throws IOException {
        if (n < 0 || n > 64_000_000) throw new IOException("bad NBT length " + n);
        return n;
    }

    private static Map<String, Object> readCompound(DataInputStream d, int depth) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        while (true) {
            byte t = d.readByte();
            if (t == 0) return m;
            String name = d.readUTF();
            m.put(name, readPayload(d, t, depth));
        }
    }

    // ---------------- writing ----------------

    /** Writes a gzipped NBT file with the given root compound. */
    public static void write(OutputStream raw, Map<String, Object> root) throws IOException {
        try (DataOutputStream d = new DataOutputStream(new GZIPOutputStream(raw))) {
            d.writeByte(10);
            d.writeUTF("");
            writeCompound(d, root);
        }
    }

    private static void writeCompound(DataOutputStream d, Map<String, Object> m) throws IOException {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            byte t = typeOf(e.getValue());
            d.writeByte(t);
            d.writeUTF(e.getKey());
            writePayload(d, t, e.getValue());
        }
        d.writeByte(0);
    }

    private static byte typeOf(Object v) {
        if (v instanceof Tagged t) return t.type();
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof Float) return 5;
        if (v instanceof Double) return 6;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("can't write " + v);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream d, byte t, Object v) throws IOException {
        if (v instanceof Tagged tg) v = tg.value();
        switch (t) {
            case 1 -> d.writeByte((Byte) v);
            case 2 -> d.writeShort((Short) v);
            case 3 -> d.writeInt((Integer) v);
            case 4 -> d.writeLong((Long) v);
            case 5 -> d.writeFloat((Float) v);
            case 6 -> d.writeDouble((Double) v);
            case 7 -> { byte[] a = (byte[]) v; d.writeInt(a.length); d.write(a); }
            case 8 -> d.writeUTF((String) v);
            case 9 -> {
                List<Object> l = (List<Object>) v;
                byte et = l.isEmpty() ? 10 : typeOf(l.get(0));
                d.writeByte(et);
                d.writeInt(l.size());
                for (Object o : l) writePayload(d, et, o);
            }
            case 10 -> writeCompound(d, (Map<String, Object>) v);
            case 11 -> { int[] a = (int[]) v; d.writeInt(a.length); for (int x : a) d.writeInt(x); }
            case 12 -> { long[] a = (long[]) v; d.writeInt(a.length); for (long x : a) d.writeLong(x); }
            default -> throw new IOException("bad type " + t);
        }
    }

    /** Variable-length ints as used by Sponge schematics. */
    public static byte[] varints(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length);
        for (int v : values) {
            while ((v & ~0x7F) != 0) { out.write((v & 0x7F) | 0x80); v >>>= 7; }
            out.write(v);
        }
        return out.toByteArray();
    }

    public static int[] readVarints(byte[] data, int count) {
        int[] out = new int[count];
        int i = 0, pos = 0;
        while (i < count && pos < data.length) {
            int value = 0, shift = 0, b;
            do {
                b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0 && pos < data.length);
            out[i++] = value;
        }
        return out;
    }

    public static String str(Object o) { return o == null ? null : o.toString(); }

    public static int num(Object o, int def) { return o instanceof Number n ? n.intValue() : def; }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) { return o instanceof Map ? (Map<String, Object>) o : Map.of(); }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) { return o instanceof List ? (List<Object>) o : List.of(); }

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }
}
