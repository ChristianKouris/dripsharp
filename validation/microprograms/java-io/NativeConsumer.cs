// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.IO;
using System.Runtime.Loader;
using DripSharp.Microprograms;

public static class NativeConsumer
{
    private sealed class ShortStream : Stream
    {
        private int position;
        internal int Closes;
        internal int Reads;
        internal readonly IOException ReadFailure = new("native-read");
        internal readonly IOException CloseFailure = new("native-close");
        internal bool FailRead;
        internal bool FailClose;
        public override int Read(byte[] buffer, int offset, int count)
        {
            Reads++;
            if (count != 2) throw new Exception("Translated API changed requested count");
            if (FailRead && position == 1) throw ReadFailure;
            if (position == 3) return 0;
            buffer[offset] = new byte[] {128, 255, 0}[position++];
            return 1;
        }
        protected override void Dispose(bool disposing)
        {
            if (disposing) { Closes++; if (FailClose) throw CloseFailure; }
            base.Dispose(disposing);
        }
        public override bool CanRead => true;
        public override bool CanSeek => false;
        public override bool CanWrite => false;
        public override long Length => throw new NotSupportedException();
        public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => throw new NotSupportedException();
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => throw new NotSupportedException();
    }
    private static void Require(bool condition, string detail)
    {
        if (!condition) throw new Exception(detail);
    }
    public static void Run(string inputFile)
    {
        _ = inputFile;
        var stream = new ShortStream();
        Require(IoFlows.Consume(stream) ==
            "1;buffer:42,-128,42,42,;1;buffer:42,-1,42,42,;1;buffer:42,0,42,42,;-1;buffer:42,0,42,42,;",
            "Short read counts, signed bytes, EOF or buffer boundaries differ");
        Require(stream.Reads == 4 && stream.Closes == 1, "Read/close counts differ");
        foreach (bool failRead in new[] {false, true})
        {
            stream = new ShortStream {FailRead = failRead, FailClose = true};
            try { IoFlows.Consume(stream); throw new Exception("Failure was lost"); }
            catch (IOException failure)
            {
                Require(ReferenceEquals(failure, failRead ? stream.ReadFailure : stream.CloseFailure),
                    "Primary native exception identity was replaced");
                Require(IoFlows.Describe(failure) == (failRead
                    ? "io:native-read/cause:null/suppressed:io:native-close/cause:null/suppressed:;,;"
                    : "io:native-close/cause:null/suppressed:;"), "Native suppression differs");
                // A second generated assembly has independent JavaCompat statics.
                // The exception must carry its suppression across that boundary.
                var context = new AssemblyLoadContext("independent-compat", isCollectible: true);
                try
                {
                    var peer = context.LoadFromAssemblyPath(typeof(IoFlows).Assembly.Location)
                        .GetType(typeof(IoFlows).FullName!, true)!;
                    Require((string)peer.GetMethod("Describe")!.Invoke(null, new object[] {failure})!
                        == IoFlows.Describe(failure), "Suppression was lost across generated assemblies");
                }
                finally { context.Unload(); }
            }
            Require(stream.Closes == 1, "Native stream must close exactly once after failure");
        }
        Console.WriteLine("Native Stream short-read, EOF, buffer and cleanup assertions passed.");
    }
}
