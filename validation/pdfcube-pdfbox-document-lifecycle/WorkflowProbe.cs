// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using DripSharp.PdfCarton;
using DripSharp.PdfCarton.Cos;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdfwriter.Compress;
using DripSharp.PdfCarton.Text;

internal static class WorkflowProbe
{
    private static COSName Key(string s) => COSName.GetPDFName(s);
    private static string Decode(string s) => Encoding.UTF8.GetString(Convert.FromBase64String(s));
    private static string Hex(string? s) => s == null ? "null" : "u:" + string.Concat(s.Select(c => ((int)c).ToString("x4", CultureInfo.InvariantCulture)));
    private static void Row(List<string> rows, string id, object value) => rows.Add(id + "\t" + (value is bool b ? b ? "true" : "false" : Convert.ToString(value, CultureInfo.InvariantCulture)));
    private static string Bytes(Stream input)
    {
        using (input)
        using (var output = new MemoryStream()) { input.CopyTo(output); return Convert.ToHexString(output.ToArray()).ToLowerInvariant(); }
    }
    private static void Tree(List<string> rows, string id, COSDictionary node, COSDictionary? parent, HashSet<COSDictionary> seen)
    {
        if (!seen.Add(node)) throw new InvalidDataException("page tree cycle or duplicate");
        Row(rows, id + "/parent", ReferenceEquals(node.GetDictionaryObject(COSName.Parent), parent));
        string type = node.GetNameAsString(COSName.Type);
        Row(rows, id + "/type", type);
        if (type == "Pages")
        {
            var kids = (COSArray)node.GetDictionaryObject(COSName.Kids);
            Row(rows, id + "/count", node.GetInt(COSName.Count));
            Row(rows, id + "/kids", kids.Size());
            for (int i = 0; i < kids.Size(); i++) Tree(rows, id + "/" + i, (COSDictionary)kids.GetObject(i), node, seen);
        }
        else Row(rows, id + "/page", Hex(node.GetString(Key("ProbeId"))));
    }
    private static void Inspect(List<string> rows, string id, PDDocument d)
    {
        Row(rows, id + "/title", Hex(d.GetDocumentInformation().GetTitle()));
        Row(rows, id + "/subject", Hex(d.GetDocumentInformation().GetSubject()));
        Row(rows, id + "/pages", d.GetNumberOfPages());
        Tree(rows, id + "/tree", d.GetPages().GetCOSObject(), null, new HashSet<COSDictionary>(ReferenceEqualityComparer.Instance));
        var resources = new List<COSDictionary>();
        for (int i = 0; i < d.GetNumberOfPages(); i++)
        {
            var p = d.GetPage(i);
            string prefix = id + "/page/" + i;
            var r = p.GetResources().GetCOSObject();
            int alias = resources.FindIndex(prior => ReferenceEquals(prior, r));
            if (alias < 0) alias = i;
            resources.Add(r);
            Row(rows, prefix + "/id", Hex(p.GetCOSObject().GetString(Key("ProbeId"))));
            Row(rows, prefix + "/size", (int)p.GetMediaBox().GetWidth() + "," + (int)p.GetMediaBox().GetHeight());
            Row(rows, prefix + "/rotation", p.GetRotation());
            Row(rows, prefix + "/resource-alias", alias);
            Row(rows, prefix + "/resource-indirect", p.GetCOSObject().GetItem(COSName.Resources) is COSObject);
            Row(rows, prefix + "/resource-id", Hex(r.GetString(Key("ProbeId"))));
            Row(rows, prefix + "/resource-value", Hex(r.GetString(Key("Value"))));
            Row(rows, prefix + "/font", p.GetResources().GetFont(Key("F1")).GetName());
            Row(rows, prefix + "/content", Bytes(p.GetContents()));
            int streams = 0;
            var it = p.GetContentStreams();
            while (it.HasNext())
            {
                var s = it.Next()!;
                Row(rows, prefix + "/stream/" + streams + "/filter", Hex(s.GetCOSObject().GetNameAsString(COSName.Filter)));
                Row(rows, prefix + "/stream/" + streams + "/bytes", Bytes(s.CreateInputStream()));
                streams++;
            }
            Row(rows, prefix + "/streams", streams);
        }
        Row(rows, id + "/text", Hex(new PDFTextStripper().GetText(d)));
    }
    private static PDDocument Create(List<string[]> input)
    {
        var d = new PDDocument();
        var resources = new Dictionary<string, PDResources>();
        foreach (var r in input.Where(r => r[0] == "resource"))
        {
            var p = new PDResources();
            p.GetCOSObject().SetString(Key("ProbeId"), r[1]);
            p.GetCOSObject().SetString(Key("Value"), Decode(r[2]));
            p.Put(Key("F1"), new PDType1Font(Standard14Fonts.FontName.Helvetica));
            resources.Add(r[1], p);
        }
        foreach (var r in input.Where(r => r[0] == "page"))
        {
            var p = new PDPage(new PDRectangle(int.Parse(r[3], CultureInfo.InvariantCulture), int.Parse(r[4], CultureInfo.InvariantCulture)));
            p.GetCOSObject().SetString(Key("ProbeId"), r[1]);
            p.SetResources(resources[r[2]]);
            d.AddPage(p);
            using var s = new PDPageContentStream(d, p, PDPageContentStream.AppendMode.Overwrite, bool.Parse(r[6]));
            s.AppendRawCommands(Decode(r[5]));
        }
        var model = input[0];
        d.GetDocumentInformation().SetTitle(Decode(model[2]));
        d.GetDocumentInformation().SetSubject("");
        if (bool.Parse(model[3]))
        {
            var root = d.GetPages().GetCOSObject();
            var kids = (COSArray)root.GetDictionaryObject(COSName.Kids);
            var branch = new COSDictionary();
            branch.SetItem(COSName.Type, COSName.Pages);
            branch.SetItem(COSName.Parent, root);
            branch.SetItem(COSName.Kids, kids);
            branch.SetInt(COSName.Count, d.GetNumberOfPages());
            for (int i = 0; i < kids.Size(); i++) ((COSDictionary)kids.GetObject(i)).SetItem(COSName.Parent, branch);
            var branches = new COSArray(); branches.Add(branch); root.SetItem(COSName.Kids, branches);
        }
        return d;
    }
    private static PDPage Page(PDDocument d, string id) => d.GetPages().First(p => p.GetCOSObject().GetString(Key("ProbeId")) == id);
    private static string Category(Exception e) => e switch
    {
        IOException => "io",
        InvalidOperationException => "state",
        IndexOutOfRangeException => "index",
        ArgumentOutOfRangeException => "index",
        ArgumentException => "argument",
        _ => e.GetType().FullName!
    };
    public static void Run(string[] args)
    {
        var input = File.ReadAllLines(args[1], Encoding.UTF8).Select(line => line.Split('\t')).ToList();
        string dir = args[2]; Directory.CreateDirectory(dir);
        var rows = new List<string>();
        if (args[0] == "cross")
        {
            foreach (var r in input.Where(r => r[0] == "op" && (r[2] == "full" || r[2] == "incremental")))
            {
                try
                {
                    using var d = Loader.LoadPDF(new FileInfo(Path.Combine(dir, "java-" + r[1] + ".pdf")));
                    Inspect(rows, r[1], d);
                }
                catch (Exception e) { Row(rows, r[1] + "/operation", "cross-load"); Row(rows, r[1] + "/exception", Category(e)); }
            }
        }
        else
        {
            PDDocument? d = Create(input);
            string? latest = null;
            try
            {
                foreach (var r in input.Where(r => r[0] == "op"))
                {
                    string id = r[1], op = r[2];
                    try
                    {
                    switch (op)
                    {
                        case "title": d!.GetDocumentInformation().SetTitle(Decode(r[3])); d.GetDocumentInformation().GetCOSObject().SetNeedToBeUpdated(true); break;
                        case "shared":
                            foreach (var p in d!.GetPages())
                            {
                                var res = p.GetResources().GetCOSObject();
                                if (res.GetString(Key("ProbeId")) == r[3]) { res.SetString(Key("Value"), Decode(r[4])); res.SetNeedToBeUpdated(true); }
                            }
                            break;
                        case "rotate": Page(d!, r[3]).SetRotation(int.Parse(r[4], CultureInfo.InvariantCulture)); Page(d!, r[3]).GetCOSObject().SetNeedToBeUpdated(true); break;
                        case "close": d!.Dispose(); Row(rows, id + "/closed", d.GetDocument().IsClosed()); d = null; break;
                        case "load": d = Loader.LoadPDF(new FileInfo(latest!)); break;
                        case "invalid-incremental":
                            using (var b = new MemoryStream())
                            {
                                try { d!.SaveIncremental(b); Row(rows, id + "/exception", "none"); }
                                catch (InvalidOperationException) { Row(rows, id + "/exception", "state"); }
                                Row(rows, id + "/written", b.ToArray().Length);
                            }
                            break;
                        case "full": case "incremental":
                            string output = Path.Combine(dir, "dotnet-" + id + ".pdf");
                            if (op == "full") d!.Save(new FileInfo(output), input[0][1] == "none" ? CompressParameters.NoCompression : CompressParameters.DefaultCompression);
                            else
                            {
                                byte[] before = File.ReadAllBytes(latest!);
                                using (var stream = File.Create(output)) d!.SaveIncremental(stream);
                                byte[] after = File.ReadAllBytes(output);
                                Row(rows, id + "/prefix-preserved", after.Length >= before.Length && before.AsSpan().SequenceEqual(after.AsSpan(0, before.Length)));
                            }
                            latest = output;
                            using (var reopened = Loader.LoadPDF(new FileInfo(output))) Inspect(rows, id, reopened);
                            break;
                        default: throw new ArgumentException(op);
                    }
                    }
                    catch (Exception e) { Row(rows, id + "/operation", op); Row(rows, id + "/exception", Category(e)); break; }
                }
            }
            finally { d?.Dispose(); }
        }
        File.WriteAllLines(args[3], rows, new UTF8Encoding(false));
    }
}
