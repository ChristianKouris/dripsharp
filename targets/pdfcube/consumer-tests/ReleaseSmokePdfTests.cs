using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
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
}
#endif

// Shared by the mandatory shipped smoke tests and the isolated one-package
// consumer. Each case loads fresh product statics before setting properties.
internal static class FontCacheRegression
{
    internal static void RunAll()
    {
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
