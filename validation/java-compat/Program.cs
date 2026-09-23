#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Xml;
using DripSharp.Runtime;

namespace DripSharp.Validation.JavaCompatProof;

internal static class Program
{
    private const string Header = "DRIPSHARP_DIFFERENTIAL_OBSERVATIONS_V1";

    private sealed record Provenance(
        string CompatType,
        string JdkContract,
        string Targets,
        string[] ProofRows);

    private sealed record Observation(
        string Family,
        string Id,
        string Value);

    private static int Main(string[] arguments)
    {
        try
        {
            if (arguments is ["--runtime-version"])
            {
                Console.WriteLine(System.Runtime.InteropServices.RuntimeInformation.FrameworkDescription);
                return 0;
            }
            if (arguments is ["--cases", var inputFile, var resultFile])
            {
                RunCases(inputFile, resultFile);
                return 0;
            }
            if (arguments is ["--list-types"])
            {
                foreach (var type in RuntimeTypes().OrderBy(
                             TypeName, StringComparer.Ordinal))
                {
                    Console.WriteLine(TypeName(type));
                }

                return 0;
            }

            if (arguments is not [var output, var provenanceFile])
                throw new ArgumentException(
                    "Usage: DripSharp.JavaCompat.DirectProbe " +
                    "<output.tsv> <TypeProvenance.tsv>");

            var provenance = ReadProvenance(provenanceFile);
            ValidateRuntimeInventory(provenance);
            var observations = TypeObservations(provenance)
                .Concat(BehaviorObservations())
                .OrderBy(row => row.Family, StringComparer.Ordinal)
                .ThenBy(row => row.Id, StringComparer.Ordinal)
                .ToArray();
            ValidateProofRows(provenance, observations);
            WriteObservations(output, observations);
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            return 1;
        }
    }

    private static object? CaseValue(string token)
    {
        if (token == "n:") return null;
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Ab:(?:[0-9a-f]{2})*\z"))
            return Convert.FromHexString(token[2..]).Select(b => unchecked((sbyte)b)).ToArray();
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Ai:(?:0|-?[1-9][0-9]*)\z"))
            return System.Numerics.BigInteger.Parse(token[2..], CultureInfo.InvariantCulture);
        if (System.Text.RegularExpressions.Regex.IsMatch(token, @"\Au:(?:[0-9a-f]{4})*\z"))
        {
            var value = new StringBuilder();
            for (var i = 2; i < token.Length; i += 4)
                value.Append((char)ushort.Parse(token.Substring(i, 4), NumberStyles.HexNumber,
                    CultureInfo.InvariantCulture));
            return value.ToString();
        }
        throw new InvalidDataException("Invalid case value: " + token);
    }

    private static string StringToken(string? value) => value is null
        ? "n:"
        : "u:" + string.Concat(value.Select(character =>
            ((int)character).ToString("x4", CultureInfo.InvariantCulture)));

    private static string FileInspection(JavaFile file) => string.Join("\u0001",
        file.Pathname, file.Name, file.Parent ?? "\u0002",
        file.IsAbsolute.ToString().ToLowerInvariant(), file.Exists.ToString().ToLowerInvariant(),
        file.IsFile.ToString().ToLowerInvariant(), file.IsDirectory.ToString().ToLowerInvariant(),
        JavaCompat.FileCanRead(file).ToString().ToLowerInvariant(),
        JavaCompat.FileCanWrite(file).ToString().ToLowerInvariant(),
        JavaCompat.FileIsHidden(file).ToString().ToLowerInvariant(),
        file.IsFile ? file.Length.ToString(CultureInfo.InvariantCulture) : "\u0002",
        file.GetHashCode().ToString(CultureInfo.InvariantCulture));

    private static string FileCaseRoot(string input, string caseId)
    {
        var parent = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(input))!, "file-sandboxes");
        var root = Path.Combine(parent, caseId);
        Directory.CreateDirectory(Path.Combine(root, "dir"));
        Directory.CreateDirectory(Path.Combine(root, "empty-dir"));
        Directory.CreateDirectory(Path.Combine(root, "unicode-\u00e9"));
        Directory.CreateDirectory(Path.Combine(root, ".hidden"));
        File.WriteAllText(Path.Combine(root, "file.txt"), "abc", new UTF8Encoding(false));
        File.WriteAllText(Path.Combine(root, "unicode-\u00e9", "\u96ea.txt"), "snow", new UTF8Encoding(false));
        return root;
    }

    private static void RunCases(string input, string output)
    {
        var lines = File.ReadAllLines(input, Encoding.UTF8);
        if (lines.Length < 2 || lines[0] != "DRIPSHARP_DIFFERENTIAL_CASES_V1")
            throw new InvalidDataException("Invalid cases header or empty input");
        var results = new List<string> { "DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1" };
        var buffers = new Dictionary<string, JavaByteBuffer>();
        var identities = new HashSet<string>();
        var sandboxParent = Path.Combine(Path.GetDirectoryName(Path.GetFullPath(input))!, "file-sandboxes");
        if (Directory.Exists(sandboxParent)) Directory.Delete(sandboxParent, true);
        try
        {
            foreach (var line in lines.Skip(1))
            {
                var fields = line.Split('\t');
                if (fields.Length < 5) throw new InvalidDataException("Malformed case");
                if (fields.Take(5).Any(field => !System.Text.RegularExpressions.Regex.IsMatch(
                        field, @"\A[A-Za-z0-9_.-]+\z")))
                    throw new InvalidDataException("Invalid case identity");
                var key = fields[0] + "\t" + fields[1];
                if (!identities.Add(key + "\t" + fields[2]))
                    throw new InvalidDataException("Duplicate case step");
                var operation = fields[0] + "/" + fields[3];
                var arity = operation switch
                {
                    "byte-buffer/wrap" or "string/hash" or "long/hash" => 1,
                    "byte-buffer/get" or "byte-buffer/get-int" => 0,
                    "file/inspect" or "file/absolute" or "file/uri" or "file/to-path" or
                    "file/create" or "file/delete" or "file/open" or "file/list-count" => 1,
                    "file/parent-child" or "file/file-parent-child" or "file/equals" => 2,
                    _ => throw new InvalidDataException("Unknown operation: " + operation)
                };
                if (fields.Length != 5 + arity)
                    throw new InvalidDataException("Wrong argument count");
                var values = fields.Skip(5).Select(CaseValue).ToArray();
                if (operation == "byte-buffer/wrap" && values[0] is not null and not sbyte[] ||
                    operation == "string/hash" && values[0] is not null and not string ||
                    operation == "long/hash" && values[0] is not System.Numerics.BigInteger ||
                    operation.StartsWith("file/", StringComparison.Ordinal) &&
                    values.Any(value => value is not null and not string))
                    throw new InvalidDataException("Wrong argument type");
                var number = operation == "long/hash" ? (long)(System.Numerics.BigInteger)values[0]! : 0;
                var caseRoot = operation.StartsWith("file/", StringComparison.Ordinal)
                    ? FileCaseRoot(input, fields[1]) : null;
                for (var index = 0; index < values.Length; index++)
                    if (values[index] is string text)
                        values[index] = text.Replace("$ROOT", caseRoot ?? "$ROOT", StringComparison.Ordinal);
                var result = "n:";
                var exception = "none";
                buffers.TryGetValue(key, out var buffer);
                try
                {
                    switch (operation)
                    {
                        case "byte-buffer/wrap":
                            var replacement = JavaByteBuffer.wrap((sbyte[])values[0]!);
                            buffer?.Dispose();
                            buffers[key] = buffer = replacement;
                            break;
                        case "byte-buffer/get": result = "i:" + buffer!.get().ToString(CultureInfo.InvariantCulture); break;
                        case "byte-buffer/get-int": result = "i:" + buffer!.getInt().ToString(CultureInfo.InvariantCulture); break;
                        case "string/hash": result = "i:" + JavaCompat.StringHashCode((string)values[0]!).ToString(CultureInfo.InvariantCulture); break;
                        case "long/hash": result = "i:" + JavaCompat.LongHashCode(number).ToString(CultureInfo.InvariantCulture); break;
                        case "file/inspect": result = StringToken(FileInspection(new JavaFile((string)values[0]!))); break;
                        case "file/absolute": result = StringToken(new JavaFile((string)values[0]!).AbsolutePath); break;
                        case "file/parent-child": result = StringToken(new JavaFile((string?)values[0], (string)values[1]!).Pathname); break;
                        case "file/file-parent-child": result = StringToken(new JavaFile(
                            values[0] is null ? (JavaFile?)null : new JavaFile((string)values[0]!), (string)values[1]!).Pathname); break;
                        case "file/uri": result = StringToken(new JavaFile(new Uri((string)values[0]!, UriKind.RelativeOrAbsolute)).Pathname); break;
                        case "file/equals": result = "i:" + (new JavaFile((string)values[0]!).Equals(new JavaFile((string)values[1]!)) ? 1 : 0); break;
                        case "file/to-path": result = StringToken(JavaCompat.FileToPath(new JavaFile((string)values[0]!)).ToString()); break;
                        case "file/create": result = "i:" + (JavaCompat.FileCreateNewFile(new JavaFile((string)values[0]!)) ? 1 : 0); break;
                        case "file/delete": result = "i:" + (JavaCompat.FileDelete(new JavaFile((string)values[0]!)) ? 1 : 0); break;
                        case "file/open":
                            using (var stream = JavaCompat.OpenFileInput(new JavaFile((string)values[0]!)))
                                result = "i:" + stream.ReadByte().ToString(CultureInfo.InvariantCulture);
                            break;
                        case "file/list-count":
                            var children = new JavaFile((string)values[0]!).ListFiles();
                            result = children is null ? "n:" : "i:" + children.Length.ToString(CultureInfo.InvariantCulture);
                            break;
                    }
                }
                catch (ArgumentNullException) { exception = "null-pointer"; }
                catch (NullReferenceException) { exception = "null-pointer"; }
                catch (EndOfStreamException) { exception = "buffer-underflow"; }
                catch (FileNotFoundException) { exception = "file-not-found"; }
                catch (ArgumentException) { exception = operation == "file/to-path" ? "invalid-path" : "illegal-argument"; }
                catch (IOException) { exception = "io"; }
                var state = buffer is null ? "n:" : "i:" + buffer.position().ToString(CultureInfo.InvariantCulture);
                results.Add(string.Join("\t", fields[0], fields[1], fields[2], fields[3],
                    exception, result, state));
            }
            File.WriteAllText(output, string.Join("\n", results) + "\n", new UTF8Encoding(false));
        }
        finally
        {
            foreach (var buffer in buffers.Values) buffer.Dispose();
            if (Directory.Exists(sandboxParent)) Directory.Delete(sandboxParent, true);
        }
    }

    private static Type[] RuntimeTypes() =>
        typeof(JavaCompat).Assembly
            .GetTypes()
            .Where(type =>
                type.Namespace == "DripSharp.Runtime" &&
                type.DeclaringType is null)
            .ToArray();

    private static string TypeName(Type type) =>
        type.Name.Split('`')[0];

    private static Provenance[] ReadProvenance(string file)
    {
        var lines = File.ReadAllLines(file, Encoding.UTF8);
        if (lines.Length == 0 ||
            lines[0] != "compat-type\tjdk-contract\ttargets\tproof-rows")
        {
            throw new InvalidDataException(
                "JavaCompat provenance has the wrong header.");
        }

        return lines.Skip(1).Select((line, index) =>
        {
            var fields = line.Split('\t');
            if (fields.Length != 4 || fields.Any(string.IsNullOrWhiteSpace))
                throw new InvalidDataException(
                    $"Malformed JavaCompat provenance row {index + 2}.");
            return new Provenance(
                fields[0],
                fields[1],
                fields[2],
                fields[3].Split(',', StringSplitOptions.RemoveEmptyEntries));
        }).ToArray();
    }

    private static void ValidateRuntimeInventory(Provenance[] provenance)
    {
        var runtimeTypes = RuntimeTypes();
        var actual = runtimeTypes.Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        var expected = provenance.Select(row => row.CompatType)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (!actual.SequenceEqual(expected, StringComparer.Ordinal))
        {
            throw new InvalidDataException(
                "JavaCompat provenance does not exactly cover the compiled " +
                $"runtime inventory.\nExpected: {string.Join(", ", expected)}" +
                $"\nActual: {string.Join(", ", actual)}");
        }

        var publicTypes = runtimeTypes.Where(type => type.IsPublic)
            .Select(TypeName)
            .OrderBy(value => value, StringComparer.Ordinal)
            .ToArray();
        if (publicTypes.Length != 0)
        {
            throw new InvalidDataException(
                "The direct probe must retain package-internal JavaCompat " +
                $"visibility: {string.Join(", ", publicTypes)}");
        }
    }

    private static IEnumerable<Observation> TypeObservations(
        IEnumerable<Provenance> provenance) =>
        provenance.Select(row =>
            new Observation("type-contract", row.CompatType, "available"));

    private static void ValidateProofRows(
        IEnumerable<Provenance> provenance,
        IEnumerable<Observation> observations)
    {
        var identities = observations
            .Select(row => row.Family + "/" + row.Id)
            .ToHashSet(StringComparer.Ordinal);
        foreach (var row in provenance)
        {
            var requiredTypeRow = "type-contract/" + row.CompatType;
            if (!row.ProofRows.Contains(requiredTypeRow, StringComparer.Ordinal))
            {
                throw new InvalidDataException(
                    $"{row.CompatType} does not cite its direct type proof row.");
            }

            var missing = row.ProofRows
                .Where(proof => !identities.Contains(proof))
                .ToArray();
            if (missing.Length != 0)
            {
                throw new InvalidDataException(
                    $"{row.CompatType} cites missing proof rows: " +
                    string.Join(", ", missing));
            }
        }
    }

    private static IEnumerable<Observation> BehaviorObservations()
    {
        yield return Observe("atomic-primitives", AtomicPrimitives());
        yield return Observe("base64", Base64Contract());
        yield return Observe("bit-set", BitSetContract());
        yield return Observe("byte-buffer", ByteBufferContract());
        yield return Observe("charset-malformed", CharsetMalformedContract());
        yield return Observe("collections", CollectionsContract());
        yield return Observe("compression", CompressionContract());
        yield return Observe("crc32", Crc32Contract());
        yield return Observe("data-output", DataOutputContract());
        yield return Observe("file-paths", FilePathsContract());
        yield return Observe("decimal-format", DecimalFormatContract());
        yield return Observe("message-digest", MessageDigestContract());
        yield return Observe("message-format", MessageFormatContract());
        yield return Observe("optional", OptionalContract());
        yield return Observe("regex", RegexContract());
        yield return Observe("signed-byte-or", SignedByteOrContract());
        yield return Observe("strict-math", StrictMathContract());
        yield return Observe("string-identity", StringIdentityContract());
        yield return Observe("string-tools", StringToolsContract());
        yield return Observe("time-format", TimeFormatContract());
        yield return Observe("uri", UriContract());
        yield return Observe("xpath", XPathContract());
    }

    private static Observation Observe(string id, string value) =>
        new("behavior", id, value);

    private static string FilePathsContract()
    {
        var values = new List<string>();
        foreach (var path in new[] { "", "missing-file-path", "a//b/", "a/../b", "\0", " " })
        {
            var file = new JavaFile(path);
            values.Add(Convert.ToBase64String(Encoding.UTF8.GetBytes(file.Pathname)));
            values.Add(Convert.ToBase64String(Encoding.UTF8.GetBytes(file.Name)));
            values.Add(file.Exists.ToString().ToLowerInvariant());
            values.Add(file.IsDirectory.ToString().ToLowerInvariant());
            values.Add(file.GetHashCode().ToString(CultureInfo.InvariantCulture));
        }
        foreach (string path in new[] { "/a/../b", "/a b", "/a#b", "/a%z", "/aé", "/a\0b" })
            values.Add(JavaCompat.UriToString(JavaCompat.FileToUri(new JavaFile(path))));
        foreach (string? parent in new string?[] { null, "", "a", "/" })
            values.Add(new JavaFile(parent, "/b").Pathname);
        try { _ = new JavaFile((string)null!); values.Add("accepted-null"); }
        catch (NullReferenceException) { values.Add("null-rejected"); }
        foreach (string path in new[] { "", "\0" })
        {
            try { _ = new JavaFile(path).OpenPath(); values.Add("opened"); }
            catch (FileNotFoundException) { values.Add("open-rejected"); }
            try { _ = new JavaFile(path).ToFileInfo(); throw new Exception("Unrepresentable file exported"); }
            catch (ArgumentException) { }
        }
        foreach (string uri in new[] { "file:/a/../b", "file:///a%20b", "file:/a%00b", "file:/", "file://host/a", "file:/a?x", "file:/a#x", "file:a", "/a" })
        {
            try { values.Add(new JavaFile(new Uri(uri, UriKind.RelativeOrAbsolute)).Pathname); }
            catch (ArgumentException) { values.Add("uri-rejected"); }
            catch (UriFormatException) { values.Add("uri-rejected"); }
        }
        foreach (string? parent in new string?[] { null, "", "a", "/" })
        {
            values.Add(new JavaFile(parent is null ? null : new JavaFile(parent), "b").Pathname);
            try { _ = new JavaFile(parent, (string)null!); values.Add("accepted-child"); }
            catch (NullReferenceException) { values.Add("child-rejected"); }
        }
        var empty = new JavaFile("");
        values.Add($"{Lower(JavaCompat.FileCanRead(empty))}:{Lower(JavaCompat.FileCanWrite(empty))}:{empty.Length}:{JavaCompat.FileLastModified(empty)}:{Lower(empty.ListFiles() is null)}");
        values.Add(JavaCompat.FileToPath(empty).ToString());
        try { _ = JavaCompat.FileToPath(new JavaFile("\0")); values.Add("path-accepted"); }
        catch (ArgumentException) { values.Add("path-rejected"); }
        try { _ = new JavaFile((Uri)null!); values.Add("accepted-null-uri"); }
        catch (NullReferenceException) { values.Add("null-uri-rejected"); }
        try { using var file = new JavaRandomAccessFile(new JavaFile(""), "bad"); values.Add("mode-accepted"); }
        catch (ArgumentException) { values.Add("mode-rejected"); }
        var command = OperatingSystem.IsWindows() ? new[] { "cmd.exe", "/c", "exit" } : new[] { "/bin/true" };
        try { using var process = new JavaProcessBuilder(command).Directory(new JavaFile("")).Start(); values.Add("process-started"); }
        catch (IOException) { values.Add("process-dir-rejected"); }
        string root = Path.Combine(Path.GetTempPath(), "dripsharp-file-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string spaced = Path.Combine(root, " ");
            Directory.CreateDirectory(spaced);
            string ordinary = Path.Combine(root, "ordinary");
            File.WriteAllText(ordinary, "abc");
            var directory = new JavaFile(spaced);
            var file = new JavaFile(ordinary);
            values.Add(directory.IsDirectory.ToString().ToLowerInvariant());
            values.Add(file.IsFile.ToString().ToLowerInvariant());
            values.Add(file.Length.ToString(CultureInfo.InvariantCulture));
            values.Add(new JavaFile(root).ListFiles()!.Length.ToString(CultureInfo.InvariantCulture));
            if (!JavaFile.FromFileInfo(file.ToFileInfo())!.Equals(file)) throw new Exception("FileInfo roundtrip failed");
        }
        finally { Directory.Delete(root, true); }
        return string.Join("|", values);
    }

    private static string AtomicPrimitives()
    {
        var boolean = new JavaAtomicBoolean();
        var integer = new JavaAtomicInteger(40);
        var first = new object();
        var second = new object();
        var reference = new JavaAtomicReference<object>(first);
        using var local = JavaThreadLocal<string>.WithInitial(() => "initial");
        var previous = boolean.GetAndSet(true);
        var swapped = boolean.CompareAndSet(true, false);
        local.Set("thread");
        return $"{Lower(previous)}|{Lower(swapped)}|" +
               $"{Lower(boolean.Get())}|{integer.IncrementAndGet()}|" +
               $"{Lower(ReferenceEquals(reference.GetAndSet(second), first))}|" +
               local.Get();
    }

    private static string Base64Contract()
    {
        var value = Encoding.UTF8.GetBytes("compat")
            .Select(item => unchecked((sbyte)item)).ToArray();
        return JavaBase64.GetEncoder().EncodeToString(value);
    }

    private static string BitSetContract()
    {
        var bits = new JavaBitSet();
        bits.set(2, 5);
        bits.clear(3);
        return $"{bits.get(2)}|{bits.get(3)}|{bits.nextSetBit(3)}"
            .ToLowerInvariant();
    }

    private static string ByteBufferContract()
    {
        using var buffer = JavaByteBuffer.wrap([1, 2, 3, 4, 5]);
        var integer = buffer.getInt();
        return $"{integer}|{buffer.Remaining}|{buffer.get()}";
    }

    private static string CharsetMalformedContract()
    {
        try
        {
            using var buffer = JavaByteBuffer.wrap(
                [unchecked((sbyte)0xc3), 0x28]);
            _ = new JavaCharsetDecoder(Encoding.UTF8).Decode(buffer);
            return "accepted";
        }
        catch (DecoderFallbackException)
        {
            return "rejected";
        }
    }

    private static string CollectionsContract()
    {
        var linked = new JavaLinkedHashMap<string, string>();
        linked.Add("first", "before");
        linked.Add("second", "two");
        using var entries = JavaCompat.MapEntrySet(
            (IDictionary<string, string>)linked).GetEnumerator();
        if (!entries.MoveNext())
            throw new InvalidDataException("Missing linked-map entry.");
        var entry = entries.Current;
        var prior = entry.SetValue("after");

        var deque = new JavaDeque<string>();
        deque.Add("tail");
        deque.Push("head");
        return $"{prior}|{entry.Value}|" +
               $"{string.Join(",", linked.Keys)}|{deque.Pop()},{deque.Pop()}";
    }

    private static string CompressionContract()
    {
        using var compressed = new MemoryStream();
        using (var compressor = new ZLibStream(
                   compressed, CompressionLevel.Optimal, leaveOpen: true))
        {
            compressor.Write(Encoding.UTF8.GetBytes("deflate-body"));
        }

        using var decoded = new MemoryStream();
        using var inflater = new JavaInflaterOutputStream(decoded);
        var encoded = compressed.ToArray();
        inflater.Write(encoded, 0, encoded.Length);
        inflater.Flush();
        return Encoding.UTF8.GetString(decoded.ToArray());
    }

    private static string Crc32Contract()
    {
        var crc = new JavaCrc32();
        var value = Encoding.ASCII.GetBytes("123456789")
            .Select(item => unchecked((sbyte)item)).ToArray();
        crc.Update(value, 0, value.Length);
        return crc.GetValue().ToString(CultureInfo.InvariantCulture);
    }

    private static string DataOutputContract()
    {
        using var output = new MemoryStream();
        using (var data = new JavaDataOutputStream(output))
        {
            data.writeByte(0x7f);
            data.writeShort(0x1234);
            data.writeInt(unchecked((int)0x89abcdef));
        }
        return Convert.ToHexString(output.ToArray()).ToLowerInvariant();
    }

    private static string DecimalFormatContract()
    {
        var format = new JavaDecimalFormat(
            "0.0000000", CultureInfo.InvariantCulture.NumberFormat);
        return format.Format(123456789.123456789d) + "|" +
               format.Format(-0.0d);
    }

    private static string MessageDigestContract()
    {
        var value = Encoding.ASCII.GetBytes("abc")
            .Select(item => unchecked((sbyte)item)).ToArray();
        var digest = JavaMessageDigest.GetInstance("SHA-256").Digest(value);
        return Convert.ToHexString(
            digest.Select(item => unchecked((byte)item)).ToArray())
            .ToLowerInvariant();
    }

    private static string MessageFormatContract()
    {
        var format = new JavaMessageFormat(
            "{0}={1,number,000.00}", CultureInfo.GetCultureInfo("en-US"));
        return format.Format(new object?[] { "value", 12.5d });
    }

    private static string OptionalContract()
    {
        var present = JavaOptional<string>.Of("x")
            .Map(value => value + "y").OrElse("missing");
        var empty = JavaOptional<string>.Empty().OrElse("fallback");
        return present + "|" + empty;
    }

    private static string RegexContract()
    {
        var pattern = JavaCompat.CompileRegex(@"(?<word>\p{L}+)");
        var matcher = JavaCompat.RegexMatcher(pattern, "é42");
        if (!matcher.Find())
            throw new InvalidDataException("Regex did not match.");
        return $"{matcher.Group("word")}|{matcher.Start()}|{matcher.End()}";
    }

    private static string SignedByteOrContract()
    {
        sbyte value = -128;
        var first = JavaCompat.OrAssign(ref value, 1);
        value = 1;
        var second = JavaCompat.OrAssign(ref value, 0x180);
        return $"{first}|{second}";
    }

    private static string StrictMathContract() =>
        string.Join("|",
            JavaCompat.StringValueOf(JavaStrictMath.Sin(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Cos(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Log10(2.34d)),
            JavaCompat.StringValueOf(JavaStrictMath.Pow(2.3d, -4.0d)));

    private static string StringIdentityContract() =>
        $"{JavaCompat.StringHashCode("DripSharp")}|" +
        $"{JavaCompat.ToHexString(-65536)}|" +
        $"{JavaCompat.ToHexString(-65536L)}|" +
        JavaCompat.StringValueOf((object?)null);

    private static string StringToolsContract()
    {
        var joiner = new JavaStringJoiner(",", "[", "]")
            .add("a").add("b");
        var tokenizer = new JavaStringTokenizer("one two\tthree");
        var tokens = new List<string>();
        while (tokenizer.hasMoreTokens())
            tokens.Add(tokenizer.nextToken());
        return joiner + "|" + string.Join(",", tokens);
    }

    private static string TimeFormatContract()
    {
        var instant = new DateTimeOffset(
            2024, 1, 2, 3, 4, 5, TimeSpan.Zero);
        return JavaDateTimeFormatter.Rfc1123.Format(instant) + "|" +
               JavaTimeUnits.ToTimeSpan(1500, JavaTimeUnit.MILLISECONDS)
                   .TotalMilliseconds.ToString(CultureInfo.InvariantCulture);
    }

    private static string UriContract()
    {
        var hostless = JavaCompat.NewUri("http:///submit");
        var basis = JavaCompat.CreateUri("file:///tmp/PklProject");
        var resolved = JavaCompat.ResolveUri(basis, ".");
        return $"{JavaCompat.UriHost(hostless) ?? "null"}|" +
               $"{JavaCompat.UriRawPath(hostless)}|" +
               $"{JavaCompat.UriAuthority(resolved) ?? "null"}|" +
               JavaCompat.UriRawPath(resolved);
    }

    private static string XPathContract()
    {
        var document = new XmlDocument();
        document.LoadXml("<root><item>value</item></root>");
        return JavaXPathFactory.Instance.NewXPath()
            .Evaluate("/root/item", document);
    }

    private static string Lower(bool value) =>
        value ? "true" : "false";

    private static void WriteObservations(
        string file,
        IEnumerable<Observation> observations)
    {
        var lines = new[] { Header }.Concat(observations.Select(row =>
            $"{row.Family}\t{row.Id}\t{row.Value}"));
        File.WriteAllText(
            file,
            string.Join("\n", lines) + "\n",
            new UTF8Encoding(false));
    }
}
