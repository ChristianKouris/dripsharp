// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Text;
using DripSharp.Microprograms;

// Public .NET contract assertions, deliberately separate from JVM parity.
// No reflection into, or expected values computed by, JavaFile/JavaCompat.
public static class NativeConsumer
{
    private static int checks;
    private static void Check(bool condition, string operation)
    {
        checks++;
        if (!condition) throw new Exception("Native FileInfo boundary: " + operation);
    }
    private static void Reject(Action operation, string label)
    {
        checks++;
        try { operation(); }
        catch (ArgumentException) { return; }
        throw new Exception("Expected ArgumentException at " + label);
    }
    private static string? Decode(string token)
    {
        if (token == "n:") return null;
        var value = new StringBuilder();
        for (int i = 2; i < token.Length; i += 4)
            value.Append((char)int.Parse(token.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
        return value.ToString();
    }
    public static void Run(string inputFile)
    {
        var inputs = new HashSet<string?>(StringComparer.Ordinal);
        foreach (string row in File.ReadAllLines(inputFile))
            if (row.Contains('\t')) inputs.Add(Decode(row.Split('\t')[5]));
        var marker = new FileInfo("native-override");
        foreach (string? path in inputs)
        {
            var box = new FileBox(null!);
            var external = new ExternalBox(marker);
            Check(FileFlows.FromSource(external, path!) == marker.FullName && external.Gets == 1,
                  "external public override, including invalid Java pathname");
            var source = new ExplicitSource(marker);
            Check(FileFlows.FromSource(source, path!) == marker.FullName && source.Calls == 1,
                  "explicit external interface callback");
            if (path is null)
            {
                Check(FileFlows.Nullable(null!) is null && box.Stored() is null, "null return and constructor");
                Check(box.Echo(null!) is null && box.Remember(null!) is null && box.RememberArray(null!) is null,
                      "null references remain null");
                Check(FileFlows.FromEcho(external, null!) is null && external.Echoes == 1, "null override argument");
                continue;
            }
            // The native constructor independently defines the host's other
            // unrepresentable paths; never ask the bridge for its own oracle.
            bool unrepresentable = path.Length == 0 || path.IndexOf('\0') >= 0;
            FileInfo? file = null;
            if (!unrepresentable)
            {
                try { file = new FileInfo(path); }
                catch (ArgumentException) { unrepresentable = true; }
                catch (NotSupportedException) { unrepresentable = true; }
                catch (PathTooLongException) { unrepresentable = true; }
            }
            if (unrepresentable)
            {
                Reject(() => box.Get(path), "direct public export");
                Reject(() => FileFlows.Nullable(path), "static public export");
                Reject(() => FileFlows.FromEcho(external, path), "override argument export");
                Reject(() => FileFlows.FromProtected(external, path), "protected override argument export");
                int calls = 0;
                Reject(() => FileFlows.FromCallback(f => { calls++; return f; }, path), "delegate argument export");
                Check(external.Echoes == 0 && external.ProtectedCalls == 0 && calls == 0,
                      "unrepresentable argument rejected before native callback");
                continue;
            }
            if (file is null) throw new Exception("Missing native pathname expectation");
            Check(FileFlows.Path(file) == file.FullName, "native import preserves FullName");
            FileInfo exported = box.Get(path);
            Check(exported.FullName == file.FullName, "representable public export");
            var value = new DerivedFileBox(file);
            Check(ReferenceEquals(file, value.Stored()), "constructor chain and inherited return identity");
            Check(ReferenceEquals(file, value.Echo(file)), "public FileInfo parameter and return");
            Check(FileFlows.FromEcho(external, path) == path && external.Echoes == 1,
                  "external override preserves exported Java value on reimport");
            Check(FileFlows.FromProtected(external, path) == path && external.ProtectedCalls == 1,
                  "external protected override and base call");
            int callbacks = 0;
            Check(FileFlows.FromCallback(f => { callbacks++; Check(f.FullName == file.FullName, "delegate FileInfo"); return f; }, path) == path
                  && callbacks == 1, "external delegate invoked");
            IList<FileInfo> list = new List<FileInfo> { file };
            IList<FileInfo> alias = value.Remember(list);
            Check(ReferenceEquals(list, alias), "mutable list roundtrip identity");
            alias.Add(marker);
            Check(list.Count == 2 && ReferenceEquals(marker, list[1]), "native list mutation is shared");
            value.ReplaceList("translated-list");
            Check(list[0].FullName == Path.GetFullPath("translated-list"), "translated list mutation is shared");
            list[0] = file;
            Check(value.ReadList() == file.FullName, "native list replacement visible inside translation");
            FileInfo[] array = { file };
            Check(ReferenceEquals(array, value.RememberArray(array)), "array roundtrip identity");
            value.ReplaceArray("translated-array");
            Check(array[0].FullName == Path.GetFullPath("translated-array"), "translated array write");
            array[0] = file;
            Check(value.ReadArray() == file.FullName, "native array write");
        }
        Console.WriteLine("Native FileInfo boundary passed: " + inputs.Count + " inputs, " + checks + " assertions");
    }
    private sealed class ExplicitSource : FileSource
    {
        private readonly FileInfo marker;
        public int Calls;
        public ExplicitSource(FileInfo marker) { this.marker = marker; }
        FileInfo FileSource.Get(string path) { Calls++; return marker; }
    }
    private sealed class ExternalBox : DerivedFileBox
    {
        private readonly FileInfo marker;
        public int Gets, Echoes, ProtectedCalls;
        public ExternalBox(FileInfo marker) : base(marker) { this.marker = marker; }
        public override FileInfo Get(string path) { Gets++; return marker; }
        public override FileInfo Echo(FileInfo file) { Echoes++; return file; }
        protected override FileInfo ProtectedEcho(FileInfo file) { ProtectedCalls++; return base.ProtectedEcho(file); }
    }
}
