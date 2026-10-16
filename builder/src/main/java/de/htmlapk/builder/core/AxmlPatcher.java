package de.htmlapk.builder.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ändert ein kompiliertes (binäres) AndroidManifest.xml, ohne aapt2 zu brauchen.
 *
 * Ein binäres Manifest besteht aus einem String-Pool und XML-Knoten, die nur
 * Indizes in diesen Pool enthalten. Wir
 *   1. ersetzen im Pool den alten Paketnamen (auch als Präfix, z. B. in Berechtigungen),
 *   2. hängen neue Strings für Label und versionName an den Pool an und
 *   3. biegen die passenden Attribute auf diese neuen Strings um bzw. setzen versionCode.
 * Anschließend wird der Pool neu kodiert und die Chunk-Größen werden korrigiert.
 */
public final class AxmlPatcher {

    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;

    private static final int UTF8_FLAG = 1 << 8;
    private static final int SORTED_FLAG = 1;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_INT_DEC = 0x10;

    private static final int ATTR_LABEL = 0x01010001;
    private static final int ATTR_ICON = 0x01010002;
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_VERSION_CODE = 0x0101021b;
    private static final int ATTR_VERSION_NAME = 0x0101021c;

    /** Was geändert werden soll. Felder, die null sind, bleiben unverändert. */
    public static final class Changes {
        public String packageName;
        public String appLabel;
        public String versionName;
        public Integer versionCode;
        /** Diese uses-permission-Einträge werden aus dem Manifest entfernt. */
        public java.util.Set<String> removePermissions = new java.util.HashSet<>();
    }

    private AxmlPatcher() {}

    /** Kerndaten einer fremden APK. Referenzen (z. B. @string/app_name) als Ressourcen-ID. */
    public static final class ManifestInfo {
        public String packageName;
        public int versionCode;
        public String versionName;
        public String label;
        public int labelRes;
        public int iconRes;
    }

    public static ManifestInfo readManifestInfo(byte[] axml) {
        Parsed p = parse(axml);
        ByteBuffer b = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);
        ManifestInfo m = new ManifestInfo();
        for (int pos = 0; pos < p.body.length; ) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == RES_XML_START_ELEMENT_TYPE) {
                String el = str(p, b.getInt(pos + 20));
                for (Attr a : attrs(b, pos)) {
                    int id = a.name >= 0 && a.name < p.resourceIds.length ? p.resourceIds[a.name] : 0;
                    int vType = b.get(a.offset + 15) & 0xff;
                    int data = b.getInt(a.offset + 16);
                    String raw = a.raw >= 0 ? str(p, a.raw) : null;
                    if ("manifest".equals(el)) {
                        if ("package".equals(str(p, a.name))) m.packageName = raw;
                        else if (id == ATTR_VERSION_CODE && vType >= 0x10 && vType <= 0x11) m.versionCode = data;
                        else if (id == ATTR_VERSION_NAME) m.versionName = raw != null ? raw
                                : (vType == TYPE_STRING ? str(p, data) : null);
                    } else if ("application".equals(el)) {
                        if (id == ATTR_LABEL) {
                            if (raw != null) m.label = raw;
                            else if (vType == TYPE_STRING) m.label = str(p, data);
                            else if (vType == 0x01) m.labelRes = data;
                        } else if (id == ATTR_ICON && vType == 0x01) {
                            m.iconRes = data;
                        }
                    }
                }
            }
            pos += size;
        }
        return m;
    }

    /** Erste Ressourcen-Referenz des Attributs attrId im Element elementName (z. B. adaptive Icons). */
    public static int findReference(byte[] axml, String elementName, int attrId) {
        Parsed p = parse(axml);
        ByteBuffer b = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);
        for (int pos = 0; pos < p.body.length; ) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == RES_XML_START_ELEMENT_TYPE && elementName.equals(str(p, b.getInt(pos + 20)))) {
                for (Attr a : attrs(b, pos)) {
                    int id = a.name >= 0 && a.name < p.resourceIds.length ? p.resourceIds[a.name] : 0;
                    if (id == attrId && (b.get(a.offset + 15) & 0xff) == 0x01) return b.getInt(a.offset + 16);
                }
            }
            pos += size;
        }
        return 0;
    }

    /** Liest den Paketnamen aus einem binären Manifest. */
    public static String readPackageName(byte[] axml) {
        Parsed p = parse(axml);
        ByteBuffer b = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);
        for (int pos = 0; pos < p.body.length; ) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (type == RES_XML_START_ELEMENT_TYPE && "manifest".equals(str(p, b.getInt(pos + 20)))) {
                for (Attr a : attrs(b, pos)) {
                    if ("package".equals(str(p, a.name)) && a.raw >= 0) return p.strings.get(a.raw);
                }
            }
            pos += size;
        }
        throw new IllegalArgumentException("Kein package-Attribut im Manifest gefunden");
    }

    /** Alle per uses-permission angeforderten Berechtigungen. */
    public static List<String> readPermissions(byte[] axml) {
        Parsed p = parse(axml);
        ByteBuffer b = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);
        List<String> out = new ArrayList<>();
        for (int pos = 0; pos < p.body.length; ) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (type == RES_XML_START_ELEMENT_TYPE) {
                String perm = permissionName(p, b, pos);
                if (perm != null) out.add(perm);
            }
            pos += size;
        }
        return out;
    }

    private static String permissionName(Parsed p, ByteBuffer b, int chunk) {
        if (!"uses-permission".equals(str(p, b.getInt(chunk + 20)))) return null;
        for (Attr a : attrs(b, chunk)) {
            int id = a.name >= 0 && a.name < p.resourceIds.length ? p.resourceIds[a.name] : 0;
            if (id == ATTR_NAME && a.raw >= 0) return p.strings.get(a.raw);
        }
        return null;
    }

    public static byte[] patch(byte[] axml, Changes c) {
        Parsed p = parse(axml);
        ByteBuffer b = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);

        // 1) Paketnamen im Pool ersetzen (exakt oder als Präfix "alt.")
        if (c.packageName != null) {
            String oldPkg = readPackageName(axml);
            for (int i = 0; i < p.strings.size(); i++) {
                String s = p.strings.get(i);
                if (s.equals(oldPkg)) {
                    p.strings.set(i, c.packageName);
                } else if (s.startsWith(oldPkg + ".")) {
                    p.strings.set(i, c.packageName + s.substring(oldPkg.length()));
                }
            }
        }

        int labelIdx = c.appLabel != null ? addString(p, c.appLabel) : -1;
        int verNameIdx = c.versionName != null ? addString(p, c.versionName) : -1;
        boolean labelDone = labelIdx < 0, verNameDone = verNameIdx < 0, verCodeDone = c.versionCode == null;

        // 2) Attribute umbiegen
        for (int pos = 0; pos < p.body.length; ) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) throw new IllegalArgumentException("Defekter XML-Chunk");
            if (type == RES_XML_START_ELEMENT_TYPE) {
                String element = str(p, b.getInt(pos + 20));
                for (Attr a : attrs(b, pos)) {
                    int id = a.name >= 0 && a.name < p.resourceIds.length ? p.resourceIds[a.name] : 0;
                    if ("application".equals(element) && id == ATTR_LABEL && labelIdx >= 0) {
                        setString(b, a, labelIdx);
                        labelDone = true;
                    } else if ("manifest".equals(element) && id == ATTR_VERSION_NAME && verNameIdx >= 0) {
                        setString(b, a, verNameIdx);
                        verNameDone = true;
                    } else if ("manifest".equals(element) && id == ATTR_VERSION_CODE && c.versionCode != null) {
                        b.put(a.offset + 15, (byte) TYPE_INT_DEC);
                        b.putInt(a.offset + 16, c.versionCode);
                        verCodeDone = true;
                    }
                }
            }
            pos += size;
        }
        if (!labelDone) throw new IllegalStateException("android:label nicht im <application>-Tag der Vorlage");
        if (!verNameDone) throw new IllegalStateException("android:versionName nicht in der Vorlage");
        if (!verCodeDone) throw new IllegalStateException("android:versionCode nicht in der Vorlage");

        // 3) Nicht gewünschte Berechtigungen entfernen (Start- und End-Knoten)
        if (!c.removePermissions.isEmpty()) {
            ByteArrayOutputStream kept = new ByteArrayOutputStream();
            boolean skipEnd = false;
            for (int pos = 0; pos < p.body.length; ) {
                int type = b.getShort(pos) & 0xffff;
                int size = b.getInt(pos + 4);
                boolean drop = false;
                if (type == RES_XML_START_ELEMENT_TYPE) {
                    String perm = permissionName(p, b, pos);
                    drop = perm != null && c.removePermissions.contains(perm);
                    skipEnd = drop;
                } else if (type == RES_XML_END_ELEMENT_TYPE && skipEnd) {
                    drop = true;
                    skipEnd = false;
                }
                if (!drop) kept.write(p.body, pos, size);
                pos += size;
            }
            p.body = kept.toByteArray();
        }

        // 4) Neu zusammensetzen
        byte[] pool = encodePool(p);
        int total = 8 + pool.length + p.body.length;
        ByteBuffer out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_XML_TYPE);
        out.putShort((short) 8);
        out.putInt(total);
        out.put(pool);
        out.put(p.body);
        return out.array();
    }

    // ------------------------------------------------------------------ Parsing

    private static final class Parsed {
        List<String> strings = new ArrayList<>();
        int flags;
        int styleCount;
        int[] styleOffsets;
        byte[] styleData;
        int[] resourceIds = new int[0];
        byte[] body; // alles nach dem String-Pool
    }

    private static final class Attr {
        int offset; // absolute Position im body
        int name;
        int raw;
    }

    private static Parsed parse(byte[] axml) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != RES_XML_TYPE) {
            throw new IllegalArgumentException("Kein binäres AndroidManifest");
        }
        int headerSize = b.getShort(2) & 0xffff;
        int sp = headerSize;
        if ((b.getShort(sp) & 0xffff) != RES_STRING_POOL_TYPE) {
            throw new IllegalArgumentException("String-Pool fehlt");
        }
        int poolSize = b.getInt(sp + 4);
        int stringCount = b.getInt(sp + 8);
        int styleCount = b.getInt(sp + 12);
        int flags = b.getInt(sp + 16);
        int stringsStart = b.getInt(sp + 20);
        int stylesStart = b.getInt(sp + 24);
        int poolHeader = b.getShort(sp + 2) & 0xffff;

        Parsed p = new Parsed();
        p.flags = flags;
        p.styleCount = styleCount;
        boolean utf8 = (flags & UTF8_FLAG) != 0;
        int offTable = sp + poolHeader;
        for (int i = 0; i < stringCount; i++) {
            int off = sp + stringsStart + b.getInt(offTable + i * 4);
            p.strings.add(utf8 ? readUtf8(b, off) : readUtf16(b, off));
        }
        p.styleOffsets = new int[styleCount];
        for (int i = 0; i < styleCount; i++) {
            p.styleOffsets[i] = b.getInt(offTable + (stringCount + i) * 4);
        }
        if (styleCount > 0) {
            int from = sp + stylesStart;
            p.styleData = new byte[sp + poolSize - from];
            System.arraycopy(axml, from, p.styleData, 0, p.styleData.length);
        } else {
            p.styleData = new byte[0];
        }

        int bodyStart = sp + poolSize;
        int fileSize = Math.min(b.getInt(4), axml.length);
        p.body = new byte[fileSize - bodyStart];
        System.arraycopy(axml, bodyStart, p.body, 0, p.body.length);

        ByteBuffer bb = ByteBuffer.wrap(p.body).order(ByteOrder.LITTLE_ENDIAN);
        if (p.body.length >= 8 && (bb.getShort(0) & 0xffff) == RES_XML_RESOURCE_MAP_TYPE) {
            int hs = bb.getShort(2) & 0xffff;
            int sz = bb.getInt(4);
            p.resourceIds = new int[(sz - hs) / 4];
            for (int i = 0; i < p.resourceIds.length; i++) p.resourceIds[i] = bb.getInt(hs + i * 4);
        }
        return p;
    }

    private static List<Attr> attrs(ByteBuffer b, int chunk) {
        int headerSize = b.getShort(chunk + 2) & 0xffff;
        int ext = chunk + headerSize;
        int attrStart = b.getShort(ext + 8) & 0xffff;
        int attrSize = b.getShort(ext + 10) & 0xffff;
        int attrCount = b.getShort(ext + 12) & 0xffff;
        List<Attr> list = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            Attr a = new Attr();
            a.offset = ext + attrStart + i * attrSize;
            a.name = b.getInt(a.offset + 4);
            a.raw = b.getInt(a.offset + 8);
            list.add(a);
        }
        return list;
    }

    private static void setString(ByteBuffer b, Attr a, int idx) {
        b.putInt(a.offset + 8, idx);              // rawValue
        b.putShort(a.offset + 12, (short) 8);     // Res_value.size
        b.put(a.offset + 14, (byte) 0);
        b.put(a.offset + 15, (byte) TYPE_STRING); // dataType
        b.putInt(a.offset + 16, idx);             // data
    }

    private static String str(Parsed p, int idx) {
        return idx >= 0 && idx < p.strings.size() ? p.strings.get(idx) : null;
    }

    private static int addString(Parsed p, String s) {
        p.strings.add(s);
        return p.strings.size() - 1;
    }

    private static String readUtf8(ByteBuffer b, int off) {
        int[] o = {off};
        readLen8(b, o);              // Länge in UTF-16-Zeichen (ignoriert)
        int byteLen = readLen8(b, o);
        byte[] data = new byte[byteLen];
        for (int i = 0; i < byteLen; i++) data[i] = b.get(o[0] + i);
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int readLen8(ByteBuffer b, int[] o) {
        int len = b.get(o[0]++) & 0xff;
        if ((len & 0x80) != 0) len = ((len & 0x7f) << 8) | (b.get(o[0]++) & 0xff);
        return len;
    }

    private static String readUtf16(ByteBuffer b, int off) {
        int len = b.getShort(off) & 0xffff;
        off += 2;
        if ((len & 0x8000) != 0) {
            len = ((len & 0x7fff) << 16) | (b.getShort(off) & 0xffff);
            off += 2;
        }
        char[] chars = new char[len];
        for (int i = 0; i < len; i++) chars[i] = b.getChar(off + i * 2);
        return new String(chars);
    }

    // ----------------------------------------------------------------- Encoding

    private static byte[] encodePool(Parsed p) {
        boolean utf8 = (p.flags & UTF8_FLAG) != 0;
        int count = p.strings.size();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = data.size();
            String s = p.strings.get(i);
            if (utf8) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                writeLen8(data, s.length());
                writeLen8(data, bytes.length);
                data.write(bytes, 0, bytes.length);
                data.write(0);
            } else {
                int len = s.length();
                if (len > 0x7fff) {
                    writeU16(data, 0x8000 | (len >>> 16));
                    writeU16(data, len & 0xffff);
                } else {
                    writeU16(data, len);
                }
                for (int k = 0; k < len; k++) writeU16(data, s.charAt(k));
                writeU16(data, 0);
            }
        }
        while (data.size() % 4 != 0) data.write(0);

        int headerSize = 28;
        int stringsStart = headerSize + 4 * (count + p.styleCount);
        int stylesStart = p.styleCount > 0 ? stringsStart + data.size() : 0;
        int size = stringsStart + data.size() + p.styleData.length;

        ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        out.putShort((short) RES_STRING_POOL_TYPE);
        out.putShort((short) headerSize);
        out.putInt(size);
        out.putInt(count);
        out.putInt(p.styleCount);
        out.putInt(p.flags & ~SORTED_FLAG);
        out.putInt(stringsStart);
        out.putInt(stylesStart);
        for (int off : offsets) out.putInt(off);
        for (int off : p.styleOffsets) out.putInt(off);
        out.put(data.toByteArray());
        out.put(p.styleData);
        return out.array();
    }

    private static void writeLen8(ByteArrayOutputStream o, int len) {
        if (len > 0x7fff) throw new IllegalArgumentException("Text zu lang");
        if (len > 0x7f) {
            o.write(0x80 | (len >>> 8));
            o.write(len & 0xff);
        } else {
            o.write(len);
        }
    }

    private static void writeU16(ByteArrayOutputStream o, int v) {
        o.write(v & 0xff);
        o.write((v >>> 8) & 0xff);
    }
}
