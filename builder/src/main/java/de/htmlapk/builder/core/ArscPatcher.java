package de.htmlapk.builder.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Setzt den Paketnamen im Paket-Chunk von resources.arsc. Der Name steht dort in
 * einem festen Feld (128 UTF-16-Zeichen), daher reicht ein Überschreiben an Ort und Stelle.
 */
public final class ArscPatcher {

    private static final int RES_TABLE_TYPE = 0x0002;
    private static final int RES_TABLE_PACKAGE_TYPE = 0x0200;

    private ArscPatcher() {}

    public static byte[] renamePackage(byte[] arsc, String packageName) {
        if (packageName.length() > 127) throw new IllegalArgumentException("Paketname zu lang");
        byte[] out = arsc.clone();
        ByteBuffer b = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        if ((b.getShort(0) & 0xffff) != RES_TABLE_TYPE) throw new IllegalArgumentException("Keine resources.arsc");
        int pos = b.getShort(2) & 0xffff;
        int end = Math.min(b.getInt(4), out.length);
        boolean found = false;
        while (pos + 8 <= end) {
            int type = b.getShort(pos) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size <= 0) break;
            if (type == RES_TABLE_PACKAGE_TYPE) {
                int nameOff = pos + 12;
                for (int i = 0; i < 128; i++) {
                    char c = i < packageName.length() ? packageName.charAt(i) : 0;
                    b.putChar(nameOff + i * 2, c);
                }
                found = true;
            }
            pos += size;
        }
        if (!found) throw new IllegalArgumentException("Kein Paket in resources.arsc");
        return out;
    }
}
