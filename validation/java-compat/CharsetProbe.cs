// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Globalization;
using System.IO;
using System.Text;
using DripSharp.Runtime;

// Authored direct-runtime subject. It deliberately does not reuse translated
// CharsetMachine code or JavaCompat helpers to format expected observations.
internal static class CharsetProbe
{
    private static sbyte[] Bytes(string text)
    {
        var result = new sbyte[text.Length / 2];
        for (var index = 0; index < result.Length; index++)
            result[index] = unchecked((sbyte)int.Parse(text.Substring(index * 2, 2),
                NumberStyles.HexNumber, CultureInfo.InvariantCulture));
        return result;
    }

    private static int[] Chunks(string text) => text.Length == 0
        ? new[] { 1 }
        : Array.ConvertAll(text.Split(','), value => int.Parse(value, CultureInfo.InvariantCulture));

    private static string Units(string value)
    {
        var result = new StringBuilder();
        foreach (var unit in value) result.Append(((int)unit).ToString("x4", CultureInfo.InvariantCulture));
        return result.ToString();
    }

    internal static string Decode(string charsetName, int policy, string hex)
    {
        using var input = JavaByteBuffer.wrap(Bytes(hex));
        var action = policy == 0 ? JavaCodingErrorAction.Report : JavaCodingErrorAction.Replace;
        var decoder = new JavaCharsetDecoder(JavaCompat.CharsetForName(charsetName))
            .ReportErrors(action).ReportErrors(action);
        try
        {
            var output = decoder.Decode(input);
            return "ok;op=decode;pos=" + input.position().ToString(CultureInfo.InvariantCulture)
                + ";rem=" + input.remaining().ToString(CultureInfo.InvariantCulture)
                + ";out=" + Units(output);
        }
        catch (DecoderFallbackException)
        {
            return "error;op=decode;pos=" + input.position().ToString(CultureInfo.InvariantCulture)
                + ";rem=" + input.remaining().ToString(CultureInfo.InvariantCulture) + ";out=";
        }
    }

    private sealed class ChunkInput : Stream
    {
        private readonly byte[] data;
        private readonly int[] chunks;
        private int position;
        private int calls;
        internal ChunkInput(sbyte[] data, int[] chunks)
        {
            this.data = Array.ConvertAll(data, value => unchecked((byte)value));
            this.chunks = chunks;
        }
        internal int PositionValue => position;
        internal bool Closed { get; private set; }
        public override bool CanRead => !Closed;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => data.Length;
        public override long Position { get => position; set => throw new NotSupportedException(); }
        public override int Read(byte[] buffer, int offset, int count)
        {
            calls++;
            if (count == 0) return 0;
            if (position == data.Length) return 0;
            var chunk = chunks[Math.Min(calls - 1, chunks.Length - 1)];
            var actual = Math.Min(count, Math.Min(chunk, data.Length - position));
            Array.Copy(data, position, buffer, offset, actual);
            position += actual;
            return actual;
        }
        public override int ReadByte()
        {
            calls++;
            return position == data.Length ? -1 : data[position++];
        }
        public override void Flush() { }
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
        protected override void Dispose(bool disposing) { Closed = true; base.Dispose(disposing); }
    }

    internal static string Stream(string charsetName, string hex, string chunkText)
    {
        var source = new ChunkInput(Bytes(hex), Chunks(chunkText));
        var output = new StringBuilder();
        var reads = new StringBuilder();
        var unit = new char[1];
        using (var reader = JavaCompat.NewInputStreamReader(source, charsetName))
        {
            while (true)
            {
                var count = JavaCompat.ReaderRead(reader, unit, 0, 1);
                reads.Append(count).Append(',');
                if (count < 0) break;
                output.Append(unit[0]);
            }
        }
        return "ok;op=stream;pos=" + source.PositionValue.ToString(CultureInfo.InvariantCulture)
            + ";closed=" + (source.Closed ? "1" : "0") + ";reads=" + reads
            + ";out=" + Units(output.ToString());
    }

    internal static string StringOps(string value, int begin, int end)
    {
        var points = new StringBuilder();
        for (var index = 0; index < value.Length;)
        {
            var point = JavaCompat.CodePointAt(value, index);
            points.Append(point.ToString(CultureInfo.InvariantCulture)).Append(',');
            index += JavaCompat.CharacterCharCount(point);
        }
        var at = begin < value.Length ? ((int)value[begin]).ToString(CultureInfo.InvariantCulture) : "none";
        var pointAt = begin < value.Length
            ? JavaCompat.CodePointAt(value, begin).ToString(CultureInfo.InvariantCulture) : "none";
        return "len=" + value.Length.ToString(CultureInfo.InvariantCulture) + ";at=" + at
            + ";sub=" + Units(JavaCompat.StringSubstring(value, begin, end)) + ";point=" + pointAt
            + ";count=" + JavaCompat.StringCodePointCount(value, 0, value.Length).ToString(CultureInfo.InvariantCulture)
            + ";points=" + points;
    }
}
