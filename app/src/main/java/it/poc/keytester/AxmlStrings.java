package it.poc.keytester;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Estrae la tabella delle stringhe da un AndroidManifest.xml binario (formato AXML).
 * Serve a scoprire i nomi delle azioni (intent) dichiarate dalle app di sistema Hytera.
 */
public final class AxmlStrings {

    private AxmlStrings() {
    }

    public static List<String> parse(byte[] b) {
        List<String> out = new ArrayList<>();
        if (b == null || b.length < 36) return out;
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int pos = bb.getShort(2) & 0xffff;               // dimensione header del file
        if ((bb.getShort(pos) & 0xffff) != 0x0001) return out; // chunk string pool
        int hdr = bb.getShort(pos + 2) & 0xffff;
        int count = bb.getInt(pos + 8);
        int flags = bb.getInt(pos + 16);
        int stringsStart = bb.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int offsets = pos + hdr;
        int data = pos + stringsStart;

        for (int i = 0; i < count; i++) {
            try {
                int p = data + bb.getInt(offsets + i * 4);
                if (utf8) {
                    int l16 = b[p] & 0xff;
                    p += (l16 & 0x80) != 0 ? 2 : 1;
                    int l8 = b[p] & 0xff;
                    if ((l8 & 0x80) != 0) {
                        l8 = ((l8 & 0x7f) << 8) | (b[p + 1] & 0xff);
                        p += 2;
                    } else {
                        p += 1;
                    }
                    out.add(new String(b, p, l8, StandardCharsets.UTF_8));
                } else {
                    int len = bb.getShort(p) & 0xffff;
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7fff) << 16) | (bb.getShort(p) & 0xffff);
                        p += 2;
                    }
                    out.add(new String(b, p, len * 2, StandardCharsets.UTF_16LE));
                }
            } catch (RuntimeException ignored) {
                // stringa malformata: si salta
            }
        }
        return out;
    }
}
