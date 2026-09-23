// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

/** Charset and UTF-16 observations without locale-dependent formatting. */
public final class CharsetMachine {
    private static final String HEX = "0123456789abcdef";

    private static byte[] bytes(String text) {
        byte[] result = new byte[text.length() / 2];
        for (int index = 0; index < result.length; index++) {
            int high = HEX.indexOf(text.charAt(index * 2));
            int low = HEX.indexOf(text.charAt(index * 2 + 1));
            if (high < 0 || low < 0) throw new IllegalArgumentException("invalid hex");
            result[index] = (byte)((high << 4) | low);
        }
        return result;
    }

    private static int[] chunks(String text) {
        if (text.length() == 0) return new int[] {1};
        String[] fields = text.split(",");
        int[] result = new int[fields.length];
        for (int index = 0; index < result.length; index++) {
            result[index] = Integer.parseInt(fields[index]);
            if (result[index] <= 0) throw new IllegalArgumentException("invalid chunk");
        }
        return result;
    }

    private static String units(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            int unit = value.charAt(index);
            result.append(HEX.charAt((unit >>> 12) & 15));
            result.append(HEX.charAt((unit >>> 8) & 15));
            result.append(HEX.charAt((unit >>> 4) & 15));
            result.append(HEX.charAt(unit & 15));
        }
        return result.toString();
    }

    public static String decode(String charsetName, int policy, String hex) {
        ByteBuffer input = ByteBuffer.wrap(bytes(hex));
        CodingErrorAction action = policy == 0 ? CodingErrorAction.REPORT : CodingErrorAction.REPLACE;
        CharsetDecoder decoder = Charset.forName(charsetName).newDecoder()
            .onMalformedInput(action).onUnmappableCharacter(action);
        try {
            String output = decoder.decode(input).toString();
            return "ok;op=decode;pos=" + input.position() + ";rem=" + input.remaining()
                + ";out=" + units(output);
        } catch (CharacterCodingException error) {
            return "error;op=decode;pos=" + input.position() + ";rem=" + input.remaining()
                + ";out=";
        }
    }

    private static final class ChunkInput extends InputStream {
        private final byte[] data;
        private final int[] chunks;
        private int position;
        private int calls;
        private boolean closed;

        private ChunkInput(byte[] data, int[] chunks) {
            this.data = data;
            this.chunks = chunks;
        }

        public int read() throws IOException {
            calls++;
            return position == data.length ? -1 : data[position++] & 255;
        }

        public int read(byte[] destination, int offset, int length) throws IOException {
            calls++;
            if (length == 0) return 0;
            if (position == data.length) return -1;
            int chunk = chunks[Math.min(calls - 1, chunks.length - 1)];
            int count = Math.min(length, Math.min(chunk, data.length - position));
            System.arraycopy(data, position, destination, offset, count);
            position += count;
            return count;
        }

        public void close() { closed = true; }
    }

    public static String stream(String charsetName, String hex, String chunkText) throws IOException {
        ChunkInput source = new ChunkInput(bytes(hex), chunks(chunkText));
        StringBuilder output = new StringBuilder();
        StringBuilder reads = new StringBuilder();
        char[] unit = new char[1];
        try (InputStreamReader reader = new InputStreamReader(source, Charset.forName(charsetName))) {
            while (true) {
                int count = reader.read(unit, 0, 1);
                reads.append(count).append(',');
                if (count < 0) break;
                output.append(unit[0]);
            }
        }
        return "ok;op=stream;pos=" + source.position + ";closed=" + (source.closed ? 1 : 0)
            + ";reads=" + reads + ";out=" + units(output.toString());
    }

    public static String stringOps(String value, int begin, int end) {
        StringBuilder points = new StringBuilder();
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index);
            points.append(point).append(',');
            index += Character.charCount(point);
        }
        String at = begin < value.length() ? Integer.toString(value.charAt(begin)) : "none";
        String point = begin < value.length() ? Integer.toString(value.codePointAt(begin)) : "none";
        return "len=" + value.length() + ";at=" + at + ";sub=" + units(value.substring(begin, end))
            + ";point=" + point + ";count=" + value.codePointCount(0, value.length())
            + ";points=" + points;
    }
}
