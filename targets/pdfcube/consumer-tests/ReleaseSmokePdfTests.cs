using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Cos;
#if !PDFCARTON_PACKAGE_PROBE
using Xunit;
#endif
using System.Reflection;
using System.Runtime.Loader;
using DripSharp.PdfCarton.Pdmodel.Font;

namespace DripSharp.PdfCarton.ReleaseSmoke;

#if !PDFCARTON_PACKAGE_PROBE
public sealed class ReleaseSmokePdfTests
{
    [Fact]
    public void FontCacheFallbackPreservesJavaFileSemantics() => FontCacheRegression.RunAll();

    [Fact]
    public void DocumentCreationSaveAndReloadExposeObservableBehavior()
    {
        string file = Path.Combine(
            Path.GetTempPath(),
            $"pdfcarton-release-document-{Guid.NewGuid():N}.pdf");
        try
        {
            using (var document = new PDDocument())
            {
                document.GetDocumentInformation().SetTitle(
                    "PdfCarton release smoke");
                document.AddPage(new PDPage(PDRectangle.A4));
                document.Save(file);
            }

            Assert.True(new FileInfo(file).Length > 100);
            using var reopened = Loader.LoadPDF(new FileInfo(file));
            Assert.Equal(1, reopened.GetNumberOfPages());
            Assert.Equal(
                "PdfCarton release smoke",
                reopened.GetDocumentInformation().GetTitle());
            Assert.Equal(PDRectangle.A4.GetWidth(), reopened.GetPage(0).GetMediaBox().GetWidth());
            Assert.Equal(PDRectangle.A4.GetHeight(), reopened.GetPage(0).GetMediaBox().GetHeight());
        }
        finally
        {
            File.Delete(file);
        }
    }

    [Fact]
    public void PromotedJavaRegressionCasesMatchStoredObservations()
    {
        string fixture = Path.Combine(
            AppContext.BaseDirectory,
            "Fixtures",
            "promoted-regressions.tsv");
        string[] lines = File.ReadAllLines(fixture);
        Assert.Equal(
            "layer\tfamily\tcase\tstep\toperation\tjava-contract\t" +
            "upstream-reference\texpected-exception\texpected-result\t" +
            "expected-state\targuments",
            lines[0]);

        string[][] cases = lines.Skip(1)
            .Select(line => line.Split('\t'))
            .Where(fields => fields[0] == "public-pdfcarton")
            .ToArray();
        string[] regression = Assert.Single(cases);
        Assert.Equal(11, regression.Length);
        Assert.Equal("cos-identity", regression[1]);
        Assert.Equal("integer-cache", regression[2]);
        Assert.Equal("get", regression[4]);
        Assert.Equal("none", regression[7]);
        Assert.Equal("n:", regression[9]);

        int value = int.Parse(
            regression[10].Substring(2),
            System.Globalization.CultureInfo.InvariantCulture);
        string observation = ReferenceEquals(
            COSInteger.Get(value), COSInteger.Get(value)) ? "i:1" : "i:0";
        Assert.Equal(regression[8], observation);
    }
}
#endif

// Shared by the mandatory shipped smoke tests and the isolated one-package
// consumer. Each case loads fresh product statics before setting properties.
internal static class FontCacheRegression
{
    internal static void RunAll()
    {
        RuntimeBoundaryRegression.RunAll();
        foreach (string scenario in new[] { "empty-home", "empty-both", "valid-cache", "valid-home", "missing-cache", "file-home", "nul-home", "spaces-cache" })
        {
            var context = new IsolatedFonts();
            try
            {
                var assembly = context.LoadFromAssemblyPath(typeof(FontCacheRegression).Assembly.Location);
                assembly.GetType(typeof(FontCacheRegression).FullName!, true)!
                    .GetMethod(nameof(ExecuteCase), BindingFlags.NonPublic | BindingFlags.Static)!
                    .Invoke(null, new object[] { scenario });
            }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            {
                System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
                throw;
            }
            finally { context.Unload(); }
        }
    }

    private sealed class IsolatedFonts : AssemblyLoadContext
    {
        private readonly AssemblyDependencyResolver resolver = new(typeof(FontCacheRegression).Assembly.Location);
        internal IsolatedFonts() : base(isCollectible: true) { }
        protected override Assembly? Load(AssemblyName name)
        {
            string? path = resolver.ResolveAssemblyToPath(name);
            return path is null ? null : LoadFromAssemblyPath(path);
        }
        protected override nint LoadUnmanagedDll(string name)
        {
            string? path = resolver.ResolveUnmanagedDllToPath(name);
            return path is null ? 0 : LoadUnmanagedDllFromPath(path);
        }
    }

    private static void ExecuteCase(string scenario)
    {
        if (scenario == "spaces-cache" && OperatingSystem.IsWindows()) return;
        string root = Path.Combine(Path.GetTempPath(), "pdfcarton-font-cache-" + Guid.NewGuid().ToString("N"));
        string temp = Path.Combine(root, "temporary");
        string home = Path.Combine(root, "home");
        string cache = Path.Combine(root, "cache");
        Directory.CreateDirectory(temp);
        Directory.CreateDirectory(home);
        Directory.CreateDirectory(cache);
        string plainFile = Path.Combine(root, "ordinary-file");
        File.WriteAllText(plainFile, "not a directory");
        string? cacheSetting = null;
        string homeSetting = "";
        string expected = temp;
        switch (scenario)
        {
            case "empty-home": break;
            case "empty-both": cacheSetting = ""; break;
            case "valid-cache": cacheSetting = cache; expected = cache; break;
            case "valid-home": homeSetting = home; expected = home; break;
            case "missing-cache": cacheSetting = Path.Combine(root, "missing"); homeSetting = home; expected = home; break;
            case "file-home": homeSetting = plainFile; break;
            case "nul-home": homeSetting = "bad\0path"; break;
            case "spaces-cache":
                cacheSetting = Path.Combine(root, " ");
                Directory.CreateDirectory(cacheSetting);
                expected = cacheSetting;
                break;
            default: throw new ArgumentException(scenario);
        }
        try
        {
            var runtime = typeof(DripSharp.PdfCarton.IO.RandomAccessReadBuffer).Assembly.GetType("DripSharp.Runtime.JavaCompat", true)!;
            var properties = (IDictionary<string, string>)runtime
                .GetField("SystemProperties", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
            properties.Remove("pdfbox.fontcache");
            if (cacheSetting is not null) properties["pdfbox.fontcache"] = cacheSetting;
            properties["user.home"] = homeSetting;
            properties["java.io.tmpdir"] = temp;
            // Exercise discovery and initialization without repeatedly hashing
            // every installed font; checksum behavior has separate coverage.
            properties["pdfbox.fontcache.skipchecksums"] = "true";

            // Public font initialization must complete with these properties.
            var font = new PDType1Font(Standard14Fonts.FontName.Helvetica);
            using var document = new PDDocument();
            var page = new PDPage(PDRectangle.A4);
            document.AddPage(page);
            using (var content = new PDPageContentStream(document, page))
            {
                content.BeginText();
                content.SetFont(font, 12);
                content.NewLineAtOffset(40, 700);
                content.ShowText("Font cache fallback");
                content.EndText();
            }
            string output = Path.Combine(root, "font.pdf");
            document.Save(output);
            using var reopened = Loader.LoadPDF(new FileInfo(output));
            if (reopened.GetNumberOfPages() != 1) throw new Exception("Font PDF roundtrip failed: " + scenario);

            // Observe the chosen directory even on hosts with no installed
            // fonts, where upstream legitimately does not create a cache.
            var provider = typeof(PDDocument).Assembly.GetType("DripSharp.PdfCarton.Pdmodel.Font.FileSystemFontProvider", true)!;
            var instance = System.Runtime.CompilerServices.RuntimeHelpers.GetUninitializedObject(provider);
            object selected = provider.GetMethod("getDiskCacheFile", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(instance, null)!;
            if (Path.GetFullPath(selected.ToString()!) != Path.Combine(expected, ".pdfbox.cache"))
                throw new Exception("Wrong cache directory: " + scenario + ": " + selected);
        }
        finally { Directory.Delete(root, true); }
    }
}

// These assertions also run in the isolated combined-package consumer. Reflection
// reaches compatibility internals without adding a public testing/configuration API.
internal static class RuntimeBoundaryRegression
{
    private const BindingFlags Static = BindingFlags.Static | BindingFlags.NonPublic;
    private const BindingFlags Instance = BindingFlags.Instance | BindingFlags.NonPublic;

    internal static void RunAll()
    {
        Assembly io = typeof(DripSharp.PdfCarton.IO.RandomAccessReadBuffer).Assembly;
        Assembly fonts = typeof(DripSharp.PdfCarton.Fonts.Ttf.TTFParser).Assembly;
        Type ioCompat = io.GetType("DripSharp.Runtime.JavaCompat", true)!;
        Type fontCompat = fonts.GetType("DripSharp.PdfCarton.Runtime.Fonts.JavaCompat", true)!;
        Type fileType = io.GetType("DripSharp.Runtime.JavaFile", true)!;
        Type[] runtimes = { ioCompat, fontCompat };

        foreach (Type writer in runtimes)
        foreach (Type reader in runtimes)
        {
            foreach (string text in new[] {
                "file:/C:/Windows/Fonts/font%20name.ttf", "file:/tmp/font%25name.ttf",
                "file:relative.ttf", "file:///C:", "http:///example",
                "https://example.test/a/%2e%2e/b", "file://server/share/font.ttf" })
            {
                var uri = (Uri)Call(writer, "CreateUri", typeof(string), text)!;
                Equal(text, Call(reader, "UriToString", typeof(Uri), uri), "URI spelling across assemblies");
                foreach (string operation in new[] { "UriRawAuthority", "UriRawPath", "UriIsOpaque", "UriUsesSingleSlashFileSyntax" })
                    Equal(Call(writer, operation, typeof(Uri), uri), Call(reader, operation, typeof(Uri), uri), operation);
                if (text.StartsWith("file:/C:/", StringComparison.Ordinal) || text.StartsWith("file:/tmp/", StringComparison.Ordinal))
                {
                    object roundtrip = Call(reader, "NewJavaFile", typeof(Uri), uri)!;
                    string path = Uri.UnescapeDataString(text.Substring("file:".Length));
                    if (OperatingSystem.IsWindows())
                    {
                        if (path.Length > 2 && path[2] == ':') path = path.Substring(1);
                        path = path.Replace('/', '\\');
                    }
                    Equal(path, Call(ioCompat, "FileGetPath", fileType, roundtrip), "File URI pathname");
                }
                else if (text == "file:relative.ttf" || text.StartsWith("file://server", StringComparison.Ordinal))
                {
                    try
                    {
                        Call(reader, "NewJavaFile", typeof(Uri), uri);
                        throw new InvalidOperationException("Invalid Java File URI was accepted: " + text);
                    }
                    catch (ArgumentException) { }
                }
            }

            // A real host pathname exercises FileToUri, including the drive URI
            // carrier on Windows. Exact text assertions above also run on macOS.
            string filename = Path.Combine(Path.GetTempPath(), "font name%#é.ttf");
            object file = Call(ioCompat, "NewJavaFile", typeof(string), filename)!;
            var fileUri = (Uri)Call(writer, "FileToUri", fileType, file)!;
            Equal(Call(writer, "UriToString", typeof(Uri), fileUri),
                Call(reader, "UriToString", typeof(Uri), fileUri), "FileToUri cross-assembly text");
            object restored = Call(reader, "NewJavaFile", typeof(Uri), fileUri)!;
            Equal(filename, Call(ioCompat, "FileGetPath", fileType, restored), "FileToUri roundtrip");
        }

        VerifyExceptions(io, "DripSharp.Runtime");
        VerifyExceptions(fonts, "DripSharp.PdfCarton.Runtime.Fonts");
        VerifyExceptions(typeof(DripSharp.PdfCarton.Xmp.XMPMetadata).Assembly, "DripSharp.PdfCarton.Runtime.Xmp");
    }

    private static object? Call(Type type, string method, Type argumentType, object argument)
    {
        try { return type.GetMethod(method, Static, null, new[] { argumentType }, null)!.Invoke(null, new[] { argument }); }
        catch (TargetInvocationException error) when (error.InnerException is not null)
        {
            System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(error.InnerException).Throw();
            throw;
        }
    }

    private static void Equal(object? expected, object? actual, string operation)
    {
        if (!Equals(expected, actual))
            throw new InvalidOperationException($"{operation}: expected <{expected}>, got <{actual}>.");
    }

    // Test the netstandard2.0 legacy contract directly with trusted state. No
    // BinaryFormatter or untrusted deserialization is used by these net10 tests.
#pragma warning disable SYSLIB0050, SYSLIB0051
    private static void VerifyExceptions(Assembly assembly, string ns)
    {
        foreach (string name in new[] { "ArgumentNullException", "ArgumentException", "ArgumentOutOfRangeException", "ObjectDisposedException" })
        {
            Type type = assembly.GetType(ns + "." + name, true)!;
            Equal(true, type.IsDefined(typeof(SerializableAttribute), false), name + " Serializable");
            Exception original = (Exception)Activator.CreateInstance(type, Instance, null, new object[] { "argument-or-object" }, null)!;
            if (name == "ArgumentException")
                original = (Exception)Activator.CreateInstance(type, Instance, null,
                    new object[] { "message", "parameter", new InvalidOperationException("inner") }, null)!;
            original.Data["context"] = "retained";
            var info = new System.Runtime.Serialization.SerializationInfo(type, new System.Runtime.Serialization.FormatterConverter());
            var context = new System.Runtime.Serialization.StreamingContext(System.Runtime.Serialization.StreamingContextStates.All);
            original.GetObjectData(info, context);
            var constructor = type.GetConstructor(Instance, null,
                new[] { typeof(System.Runtime.Serialization.SerializationInfo), typeof(System.Runtime.Serialization.StreamingContext) }, null)
                ?? throw new InvalidOperationException("Missing serialization constructor: " + type.FullName);
            Equal(true, constructor.IsFamily, name + " protected constructor");
            var restored = (Exception)constructor.Invoke(new object[] { info, context });
            Equal(original.Message, restored.Message, name + " message");
            Equal(original.HResult, restored.HResult, name + " HResult");
            Equal(original.InnerException, restored.InnerException, name + " inner exception");
            Equal(original.Data["context"], restored.Data["context"], name + " data");
            if (original is ArgumentException argument)
                Equal(argument.ParamName, ((ArgumentException)restored).ParamName, name + " parameter");
            if (original is ObjectDisposedException disposed)
                Equal(disposed.ObjectName, ((ObjectDisposedException)restored).ObjectName, name + " object name");
        }
    }
#pragma warning restore SYSLIB0050, SYSLIB0051
}
