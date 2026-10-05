package com.fherrmann.habits.cohabit.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Liest ein GIF Block fuer Block und schreibt es ohne Metadaten neu - ohne die Bilder
 * anzufassen. Neu kodieren (wie bei Fotos) wuerde die Animation zerlegen; die
 * Blockstruktur ist dagegen einfach: Kopf, Farbtabelle, dann Erweiterungen und Bilder
 * bis zum Abschluss.
 *
 * <p>Bleiben: Bilder, die Steuerung je Bild (Dauer, Transparenz), Klartext-Bloecke und
 * die Schleife ({@code NETSCAPE2.0}/{@code ANIMEXTS1.0}). Weg: Kommentare und alle
 * uebrigen Application-Extensions - darin steckt XMP mit Geraet, Software, Ort.
 */
final class GifSanitizer {

    record Result(byte[] bytes, int width, int height, int frames) {
    }

    /** Wird geworfen, wenn die Datei kein lesbares GIF ist. */
    static final class MalformedGif extends Exception {
        MalformedGif(String message) {
            super(message);
        }
    }

    private static final int EXTENSION = 0x21;
    private static final int IMAGE = 0x2C;
    private static final int TRAILER = 0x3B;
    private static final int GRAPHIC_CONTROL = 0xF9;
    private static final int PLAIN_TEXT = 0x01;
    private static final int APPLICATION = 0xFF;

    private final byte[] in;
    private int pos;
    private final ByteArrayOutputStream out;

    private GifSanitizer(byte[] in) {
        this.in = in;
        this.out = new ByteArrayOutputStream(in.length);
    }

    static boolean isGif(byte[] b) {
        return b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a';
    }

    static Result sanitize(byte[] bytes) throws MalformedGif {
        return new GifSanitizer(bytes).run();
    }

    private Result run() throws MalformedGif {
        if (!isGif(in)) {
            throw new MalformedGif("not a gif");
        }
        copy(6);
        int width = u16(pos);
        int height = u16(pos + 2);
        int packed = u8(pos + 4);
        copy(7);
        if ((packed & 0x80) != 0) {
            copy(colorTable(packed));
        }
        int frames = 0;
        while (true) {
            if (pos >= in.length) {
                // Viele GIFs enden ohne Abschluss-Byte; nach einem vollstaendigen Block ist das harmlos.
                break;
            }
            int block = u8(pos);
            if (block == TRAILER) {
                break;
            } else if (block == IMAGE) {
                image();
                frames++;
            } else if (block == EXTENSION) {
                extension();
            } else {
                throw new MalformedGif("unknown block " + block);
            }
        }
        out.write(TRAILER);
        return new Result(out.toByteArray(), width, height, frames);
    }

    private void image() throws MalformedGif {
        need(10);
        int packed = u8(pos + 9);
        copy(10);
        if ((packed & 0x80) != 0) {
            copy(colorTable(packed));
        }
        copy(1); // LZW-Mindestcodelaenge
        copySubBlocks();
    }

    private void extension() throws MalformedGif {
        need(2);
        int label = u8(pos + 1);
        boolean keep = label == GRAPHIC_CONTROL || label == PLAIN_TEXT || (label == APPLICATION && isLoop());
        if (keep) {
            copy(2);
            copySubBlocks();
        } else {
            pos += 2;
            skipSubBlocks();
        }
    }

    /** Die Schleifen-Erweiterung: erster Unterblock mit 11 Bytes Kennung. */
    private boolean isLoop() throws MalformedGif {
        need(3);
        if (u8(pos + 2) != 11) {
            return false;
        }
        need(14);
        String id = new String(in, pos + 3, 11, StandardCharsets.US_ASCII);
        return id.equals("NETSCAPE2.0") || id.equals("ANIMEXTS1.0");
    }

    private void copySubBlocks() throws MalformedGif {
        while (true) {
            need(1);
            int size = u8(pos);
            copy(1 + size);
            if (size == 0) {
                return;
            }
        }
    }

    private void skipSubBlocks() throws MalformedGif {
        while (true) {
            need(1);
            int size = u8(pos);
            need(1 + size);
            pos += 1 + size;
            if (size == 0) {
                return;
            }
        }
    }

    private static int colorTable(int packed) {
        return 3 * (1 << ((packed & 0x07) + 1));
    }

    private void copy(int n) throws MalformedGif {
        need(n);
        out.write(in, pos, n);
        pos += n;
    }

    private void need(int n) throws MalformedGif {
        if (pos + n > in.length) {
            throw new MalformedGif("truncated");
        }
    }

    private int u8(int at) throws MalformedGif {
        if (at >= in.length) {
            throw new MalformedGif("truncated");
        }
        return in[at] & 0xFF;
    }

    private int u16(int at) throws MalformedGif {
        return u8(at) | (u8(at + 1) << 8);
    }
}
