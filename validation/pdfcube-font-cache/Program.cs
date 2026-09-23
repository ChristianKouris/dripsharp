// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System.Collections;
using System.Reflection;
using System.Runtime.CompilerServices;
using System.Text;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Text;

internal static class Program
{
    private const BindingFlags Fields = BindingFlags.Instance | BindingFlags.NonPublic | BindingFlags.Public;
    private static readonly List<string> Observations = new();
    private static void Observe(string key, object value) => Observations.Add(key + "\t" + (value is bool b ? b.ToString().ToLowerInvariant() : value));
    private static object? Call(object target, string name, params object[] args) =>
        target.GetType().GetMethod(name, Fields)!.Invoke(target, args);
    private static void Field(object target, string name, object value) => target.GetType().GetField(name, Fields)!.SetValue(target, value);
    private static string? Decode(string value) => value == "-" ? null : Encoding.UTF8.GetString(Convert.FromBase64String(value));
    private static void Main(string[] args)
    {
        if (args is ["--smoke"]) { DripSharp.PdfCarton.ReleaseSmoke.FontCacheRegression.RunAll(); return; }
        string root = args[0];
        string[] settings = File.ReadAllText(args[1]).TrimEnd('\n', '\r').Split('\t');
        var io = typeof(DripSharp.PdfCarton.IO.RandomAccessReadBuffer).Assembly;
        var runtime = io.GetType("DripSharp.Runtime.JavaCompat", true)!;
        var properties = (IDictionary<string, string>)runtime.GetField("SystemProperties", BindingFlags.Static | BindingFlags.NonPublic)!.GetValue(null)!;
        string[] names = { "pdfbox.fontcache", "user.home" };
        for (int i = 0; i < names.Length; i++)
        {
            string? value = Decode(settings[i]);
            properties.Remove(names[i]);
            if (value is not null) properties[names[i]] = value;
        }
        properties["java.io.tmpdir"] = Path.Combine(root, "temporary");
        properties["pdfbox.fontcache.skipchecksums"] = "true";
        Execute(root, args, io);
    }

    // Do not let JIT hoisting initialize product font statics before properties.
    [MethodImpl(MethodImplOptions.NoInlining)]
    private static void Execute(string root, string[] args, Assembly io)
    {
        // Same narrow discovery seam as Java; no product source is replaced.
        var type = typeof(PDDocument).Assembly.GetType("DripSharp.PdfCarton.Pdmodel.Font.FileSystemFontProvider", true)!;
        object provider = RuntimeHelpers.GetUninitializedObject(type);
        var infoType = type.GetField("fontInfoList", Fields)!.FieldType.GetGenericArguments()[0];
        Field(provider, "fontInfoList", Activator.CreateInstance(typeof(List<>).MakeGenericType(infoType))!);
        Field(provider, "cache", new FontCache());
        var javaFile = io.GetType("DripSharp.Runtime.JavaFile", true)!;
        var files = (IList)Activator.CreateInstance(typeof(List<>).MakeGenericType(javaFile))!;
        files.Add(Activator.CreateInstance(javaFile, Fields, null, new object[] { args[2] }, null)!);
        Call(provider, "scanFonts", files);
        string path = Path.GetFullPath(Call(provider, "getDiskCacheFile")!.ToString()!);
        Observe("selected", Path.GetRelativePath(root, path));
        if (!path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.Ordinal)) throw new Exception("Cache escaped scenario");
        Call(provider, "saveDiskCache");
        Observe("cache-write", "executed");
        Observe("cache-present", File.Exists(path));
        string[] rows = File.ReadAllLines(path);
        Observe("cache-rows", rows.Length);
        Observe("cache-font", rows[0].Split('|')[0]);
        var loaded = (ICollection?)Call(provider, "loadDiskCache", files);
        Observe("cache-reloaded", loaded?.Count ?? -1);
        Call(FontMappers.Instance(), "SetProvider", provider);
        var font = new PDType1Font(Standard14Fonts.FontName.Helvetica);
        Observe("substitute", font.GetFontBoxFont().GetName());
        string output = Path.Combine(root, "font.pdf");
        using (var document = new PDDocument())
        {
            var page = new PDPage(); document.AddPage(page);
            using (var content = new PDPageContentStream(document, page))
            {
                content.BeginText(); content.SetFont(font, 12); content.NewLineAtOffset(40, 700);
                content.ShowText("Font cache fallback"); content.EndText();
            }
            document.Save(output);
        }
        Observe("pdf-created", new FileInfo(output).Length > 100);
        using (var reopened = Loader.LoadPDF(new FileInfo(output)))
        {
            Observe("pages", reopened.GetNumberOfPages());
            var stripper = new PDFTextStripper();
            stripper.SetLineSeparator("\n");
            Observe("text", Convert.ToBase64String(Encoding.UTF8.GetBytes(stripper.GetText(reopened))));
        }
        Observe("side-effects", string.Join(";", Directory.GetFiles(root, "*", SearchOption.AllDirectories)
            .Select(file => Path.GetRelativePath(root, file)).Order(StringComparer.Ordinal)));
        File.WriteAllLines(args[3], Observations);
    }
}
