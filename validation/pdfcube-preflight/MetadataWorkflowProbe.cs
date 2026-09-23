#nullable enable

using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Preflight;
using DripSharp.PdfCarton.Preflight.Parser;
using DripSharp.PdfCarton.Xmp;
using DripSharp.PdfCarton.Xmp.Schema;
using DripSharp.PdfCarton.Xmp.Xml;

internal static class Program
{
    private const uint Seed = 0x5eed2408;
    private static readonly string[] Cases =
    [
        "baseline-valid",
        "constructed-properties",
        "utf16-encoding",
        "namespace-remap",
        "date-minimum",
        "number-maximum",
        "missing-optional",
        "missing-required",
        "invalid-control",
        "generated-000",
        "generated-001",
        "generated-002"
    ];

    private static int Main(string[] args)
    {
        if (args.Length != 4)
            throw new ArgumentException(
                "Expected mode, workflow directory, qualified source PDF, and output trace.");

        CultureInfo.CurrentCulture = CultureInfo.InvariantCulture;
        CultureInfo.CurrentUICulture = CultureInfo.InvariantCulture;
        Environment.SetEnvironmentVariable("TZ", "UTC");
        TimeZoneInfo.ClearCachedData();

        if (args[0] == "generate")
            Generate(Path.Combine(args[1], "dotnet"), args[2], "dotnet");
        else if (args[0] == "observe")
            Observe(args[1], args[3]);
        else
            throw new ArgumentException($"Unknown mode: {args[0]}");
        return 0;
    }

    private static void Generate(string output, string source, string producer)
    {
        Directory.CreateDirectory(output);
        byte[] basePacket = MetadataBytes(source);
        string xml = Utf8Packet(basePacket);
        foreach (string id in Cases)
        {
            string destination = Path.Combine(output, id + ".pdf");
            if (id == "baseline-valid")
            {
                File.Copy(source, destination, overwrite: true);
                continue;
            }
            byte[] packet = PacketFor(id, xml, producer);
            File.Delete(destination);
            using var document = Loader.LoadPDF(new FileInfo(source));
            document.GetDocumentCatalog().SetMetadata(
                new PDMetadata(document, new MemoryStream(packet)));
            document.Save(destination);
        }
        File.WriteAllText(
            Path.Combine(output, "generation.txt"),
            $"generator=metadata-workflow-v1\nseed={Seed}\n" +
            $"cases={string.Join(',', Cases)}\n",
            new UTF8Encoding(false));
    }

    private static byte[] PacketFor(string id, string source, string producer)
    {
        if (id == "baseline-valid")
            return Encoding.UTF8.GetBytes(source);
        if (id == "constructed-properties")
            return ConstructedPacket(producer);
        if (id == "utf16-encoding")
        {
            string declared = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n" + source;
            return [0xff, 0xfe, .. Encoding.Unicode.GetBytes(declared)];
        }
        if (id == "namespace-remap")
            return Encoding.UTF8.GetBytes(
                Required(source, "xmlns:xmp=", "xmlns:pcx=")
                    .Replace("<xmp:", "<pcx:", StringComparison.Ordinal)
                    .Replace("</xmp:", "</pcx:", StringComparison.Ordinal));
        if (id == "date-minimum")
            return Encoding.UTF8.GetBytes(Required(
                source,
                "2014-11-18T11:02:14+01:00",
                "0001-01-01T00:00:00Z"));
        if (id == "number-maximum")
            return Encoding.UTF8.GetBytes(Required(
                source,
                "<pdfaid:part>1</pdfaid:part>",
                "<pdfaid:part>2147483647</pdfaid:part>"));
        if (id == "missing-optional")
            return Encoding.UTF8.GetBytes(Regex.Replace(
                source,
                @"\s*<xmp:CreatorTool>.*?</xmp:CreatorTool>",
                "",
                RegexOptions.Singleline | RegexOptions.CultureInvariant));
        if (id == "missing-required")
            return Encoding.UTF8.GetBytes(Regex.Replace(
                source,
                @"\s*<pdfaid:conformance>.*?</pdfaid:conformance>",
                "",
                RegexOptions.Singleline | RegexOptions.CultureInvariant));
        if (id == "invalid-control")
            return Encoding.UTF8.GetBytes(Required(
                source, "Maruan Sahyoun", "Maruan\u0001Sahyoun"));

        int index = int.Parse(
            id["generated-".Length..],
            NumberStyles.None,
            CultureInfo.InvariantCulture);
        uint state = Seed;
        for (int current = 0; current <= index; current++)
            state = unchecked(state * 1664525u + 1013904223u);
        string text = $"generated-{index}-{state:x}-{producer}-café";
        return Encoding.UTF8.GetBytes(Required(source, "Maruan Sahyoun", text));
    }

    private static byte[] ConstructedPacket(string producer)
    {
        XMPMetadata metadata = XMPMetadata.CreateXMPMetadata(
            "\uFEFF", "metadata-workflow", "4096", "UTF-8");
        metadata.SetEndXPacket("w");
        DublinCoreSchema dc = metadata.CreateAndAddDublinCoreSchema();
        dc.SetFormat("application/pdf");
        dc.SetTitle("x-default", $"Constructed {producer} metadata");
        dc.AddCreator($"Workflow {producer}");
        XMPBasicSchema basic = metadata.CreateAndAddXMPBasicSchema();
        basic.SetModifyDate(DateTimeOffset.Parse(
            "2024-02-29T23:59:59-07:00",
            CultureInfo.InvariantCulture,
            DateTimeStyles.None));
        basic.SetCreatorTool("MetadataWorkflow/v1");
        PDFAIdentificationSchema pdfaid =
            metadata.CreateAndAddPDFAIdentificationSchema();
        pdfaid.SetPart(1);
        pdfaid.SetConformance("B");
        using var output = new MemoryStream();
        new XmpSerializer().Serialize(metadata, output, withXpacket: true);
        return output.ToArray();
    }

    private static void Observe(string workflow, string output)
    {
        var rows = new List<string>();
        foreach (string producer in new[] { "java", "dotnet" })
        foreach (string id in Cases)
        {
            string pdf = Path.Combine(workflow, producer, id + ".pdf");
            rows.Add($"metadata-workflow\t{producer}/{id}\t{Observation(pdf)}");
        }
        File.WriteAllLines(output, rows, new UTF8Encoding(false));
    }

    private static string Observation(string pdf)
    {
        string parse = "missing";
        string title = "null";
        string creator = "null";
        string modifyDate = "null";
        string part = "null";
        string conformance = "null";
        string extension = "null";
        string stage = "reopen";
        try
        {
            using var document = Loader.LoadPDF(new FileInfo(pdf));
            stage = "metadata";
            var stream = document.GetDocumentCatalog().GetMetadata();
            if (stream is not null)
            {
                try
                {
                    XMPMetadata metadata =
                        new DomXmpParser().Parse(stream.ExportXMPMetadata());
                    parse = "ok";
                    var dc = metadata.GetDublinCoreSchema();
                    var basic = metadata.GetXMPBasicSchema();
                    var pdfaid = metadata.GetPDFAIdentificationSchema();
                    var custom = metadata.GetSchema(
                        "urn:dripsharp:metadata-workflow/");
                    title = Value(dc?.GetTitle("x-default"));
                    creator = dc?.GetCreators() is null
                        ? "null"
                        : string.Join(',', dc.GetCreators());
                    modifyDate = basic?.GetModifyDateProperty()?.GetStringValue()
                        ?? "null";
                    part = Value(pdfaid?.GetPart());
                    conformance = Value(pdfaid?.GetConformance());
                    extension = Value(
                        custom?.GetUnqualifiedTextPropertyValue("sample"));
                }
                catch (XmpParsingException exception)
                {
                    parse = "xmp:" + exception.GetErrorType().ToString();
                }
                catch (System.Exception exception)
                {
                    parse = "runtime:" + exception.GetType().Name;
                }
            }
            stage = "validate";
        }
        catch (System.Exception exception)
        {
            return Join($"stage={stage}", $"failure={exception.GetType().Name}");
        }

        try
        {
            ValidationResult result = PreflightParser.Validate(new FileInfo(pdf));
            var errors = result.GetErrorsList();
            string codes = string.Join(
                ',', errors.Select(error => error.GetErrorCode()).OrderBy(value => value));
            string pages = string.Join(
                ',', errors.Select(error => Value(error.GetPageNumber()))
                    .OrderBy(value => value));
            return Join(
                "stage=complete", $"parse={parse}", $"title={title}",
                $"creator={creator}", $"modify-date={modifyDate}",
                $"part={part}", $"conformance={conformance}",
                $"extension={extension}",
                $"valid={result.IsValid().ToString().ToLowerInvariant()}",
                $"error-count={errors.Count}", $"codes={codes}", $"pages={pages}");
        }
        catch (System.Exception exception)
        {
            return Join(
                $"stage={stage}", $"parse={parse}",
                $"failure={exception.GetType().Name}");
        }
    }

    private static byte[] MetadataBytes(string pdf)
    {
        using var document = Loader.LoadPDF(new FileInfo(pdf));
        using var input =
            document.GetDocumentCatalog().GetMetadata()!.ExportXMPMetadata();
        using var output = new MemoryStream();
        input.CopyTo(output);
        return output.ToArray();
    }

    private static string Utf8Packet(byte[] bytes)
    {
        int length = bytes.Length;
        while (length > 0 && bytes[length - 1] == 0)
            length--;
        return Encoding.UTF8.GetString(bytes, 0, length);
    }

    private static string Required(string value, string before, string after)
    {
        if (!value.Contains(before, StringComparison.Ordinal))
            throw new InvalidDataException(
                $"Qualified metadata fixture misses: {before}");
        return value.Replace(before, after, StringComparison.Ordinal);
    }

    private static string Value(object? value) => value?.ToString() ?? "null";

    private static string Join(params string[] values) =>
        string.Join('|', values.Select(Escape));

    private static string Escape(string value) => value
        .Replace("\\", "\\\\", StringComparison.Ordinal)
        .Replace("|", "\\|", StringComparison.Ordinal)
        .Replace("\t", "\\t", StringComparison.Ordinal)
        .Replace("\r", "\\r", StringComparison.Ordinal)
        .Replace("\n", "\\n", StringComparison.Ordinal);
}
