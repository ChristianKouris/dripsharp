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
using DripSharp.PdfCarton.Multipdf;
using DripSharp.PdfCarton.Pdfwriter.Compress;
using DripSharp.PdfCarton.Pdmodel;
using DripSharp.PdfCarton.Pdmodel.Common;
using DripSharp.PdfCarton.Pdmodel.Font;
using DripSharp.PdfCarton.Pdmodel.Interactive.Annotation;
using DripSharp.PdfCarton.Pdmodel.Interactive.Form;

internal static class PageFormWorkflowProbe
{
    private static readonly COSName ProbeId = COSName.GetPDFName("ProbeId");
    private static readonly COSName ResourceId = COSName.GetPDFName("ResourceId");
    private static readonly COSName ResourceValue = COSName.GetPDFName("ResourceValue");

    private static string Decode(string value) =>
        Encoding.UTF8.GetString(Convert.FromBase64String(value));

    private static List<string[]> Input(string path) =>
        File.ReadAllLines(path, Encoding.UTF8).Select(line => line.Split('\t')).ToList();

    private static string[] Model(List<string[]> rows) =>
        rows.First(row => row[0] == "model");

    private static List<string> Selection(List<string[]> rows) =>
        rows.First(row => row[0] == "select").Skip(1).ToList();

    private static PDDocument Create(List<string[]> rows)
    {
        var document = new PDDocument();
        var resources = new Dictionary<string, PDResources>(StringComparer.Ordinal);
        foreach (var row in rows.Where(row => row[0] == "resource"))
        {
            var resource = new PDResources();
            resource.GetCOSObject().SetString(ResourceId, row[1]);
            resource.GetCOSObject().SetString(ResourceValue, Decode(row[2]));
            resources.Add(row[1], resource);
        }
        var pages = new Dictionary<string, PDPage>(StringComparer.Ordinal);
        foreach (var row in rows.Where(row => row[0] == "page"))
        {
            var page = new PDPage(new PDRectangle(
                int.Parse(row[3], CultureInfo.InvariantCulture),
                int.Parse(row[4], CultureInfo.InvariantCulture)));
            page.GetCOSObject().SetString(ProbeId, row[1]);
            page.SetResources(resources[row[2]]);
            page.SetRotation(int.Parse(row[5], CultureInfo.InvariantCulture));
            document.AddPage(page);
            var stream = new PDStream(document);
            page.SetContents(stream);
            using (var output = stream.CreateOutputStream())
            {
                var content = Encoding.ASCII.GetBytes(Decode(row[6]));
                output.Write(content, 0, content.Length);
            }
            pages.Add(row[1], page);
        }

        var form = new PDAcroForm(document);
        form.SetNeedAppearances(false);
        var defaults = new PDResources();
        defaults.Put(COSName.GetPDFName("Helv"),
            new PDType1Font(Standard14Fonts.FontName.Helvetica));
        form.SetDefaultResources(defaults);
        form.SetDefaultAppearance("/Helv 10 Tf 0 g");
        document.GetDocumentCatalog().SetAcroForm(form);
        form = document.GetDocumentCatalog().GetAcroForm();
        var fields = new List<PDField>();
        foreach (var row in rows.Where(row => row[0] == "field"))
        {
            var page = pages[row[3]];
            if (row[2] == "text")
            {
                var field = new PDTextField(form);
                field.SetPartialName(row[1]);
                AddWidget(field, page, new PDRectangle(24, 40, 160, 24));
                fields.Add(field);
                field.SetValue(Decode(row[4]));
            }
            else
            {
                var field = new PDCheckBox(form);
                field.SetPartialName(row[1]);
                var widget = field.GetWidgets()[0];
                widget.SetRectangle(new PDRectangle(24, 80, 24, 24));
                widget.SetPage(page);
                var appearances = new COSDictionary();
                appearances.SetItem(COSName.Off, AppearanceStream(document));
                appearances.SetItem(COSName.GetPDFName("Yes"), AppearanceStream(document));
                var dictionary = new PDAppearanceDictionary();
                dictionary.SetNormalAppearance(new PDAppearanceEntry(appearances));
                widget.SetAppearance(dictionary);
                widget.SetAppearanceState("Off");
                page.GetAnnotations().Add(widget);
                fields.Add(field);
                field.SetValue(bool.Parse(row[4]) ? "Yes" : "Off");
            }
        }
        form.SetFields(fields);
        return document;
    }

    private static void AddWidget(PDField field, PDPage page, PDRectangle rectangle)
    {
        var widget = field.GetWidgets()[0];
        widget.SetRectangle(rectangle);
        widget.SetPage(page);
        page.GetAnnotations().Add(widget);
    }

    private static PDAppearanceStream AppearanceStream(PDDocument document)
    {
        var stream = new PDAppearanceStream(document);
        stream.SetBBox(new PDRectangle(24, 24));
        stream.SetResources(new PDResources());
        using var output = stream.GetCOSObject().CreateOutputStream();
        var bytes = Encoding.ASCII.GetBytes("q Q\n");
        output.Write(bytes, 0, bytes.Length);
        return stream;
    }

    private static int PageIndex(PDDocument document, string id)
    {
        for (var index = 0; index < document.GetNumberOfPages(); index++)
            if (document.GetPage(index).GetCOSObject().GetString(ProbeId) == id)
                return index;
        throw new ArgumentException("Missing selected page " + id);
    }

    private static void Perform(List<string[]> rows, string output)
    {
        var template = Model(rows)[2];
        using var source = Create(rows);
        if (template == "split-and-merge")
        {
            var splitter = new Splitter();
            splitter.SetSplitAtPage(1);
            var parts = splitter.Split(source);
            try
            {
                using var destination = new PDDocument();
                var merger = new PDFMergerUtility();
                foreach (var id in Selection(rows))
                    merger.AppendDocument(destination, parts[PageIndex(source, id)]);
                destination.Save(new FileInfo(output), CompressParameters.NoCompression);
            }
            finally
            {
                foreach (var part in parts) part.Dispose();
            }
        }
        else if (template == "import-and-reorder")
        {
            using var destination = new PDDocument();
            foreach (var id in Selection(rows))
                destination.ImportPage(source.GetPage(PageIndex(source, id)));
            destination.Save(new FileInfo(output), CompressParameters.NoCompression);
        }
        else if (template == "set-form-value-save-reopen")
        {
            var form = source.GetDocumentCatalog().GetAcroForm();
            foreach (var row in rows.Where(row => row[0] == "field"))
            {
                var field = form.GetField(row[1]);
                field.SetValue(row[2] == "text"
                    ? Decode(row[5])
                    : bool.Parse(row[5]) ? "Yes" : "Off");
            }
            source.Save(new FileInfo(output), CompressParameters.NoCompression);
        }
        else
        {
            throw new ArgumentException("Unknown template " + template);
        }
    }

    private static string Bytes(Stream input)
    {
        using (input)
        using (var output = new MemoryStream())
        {
            input.CopyTo(output);
            return Convert.ToHexString(output.ToArray()).ToLowerInvariant();
        }
    }

    private static string AppearanceKind(PDAppearanceEntry? entry) =>
        entry is null ? "null" : entry.IsStream() ? "stream" : "subdictionary";

    private static string AppearanceKeys(PDAppearanceEntry? entry) =>
        entry is null || !entry.IsSubDictionary()
            ? ""
            : string.Join(",", entry.GetSubDictionary().Keys
                .Select(name => name.GetName()).OrderBy(name => name, StringComparer.Ordinal));

    private static void Inspect(
        SortedDictionary<string, string> trace,
        string template,
        PDDocument document)
    {
        trace["status"] = "ok";
        trace["template"] = template;
        trace["pages"] = document.GetNumberOfPages().ToString(CultureInfo.InvariantCulture);
        var resources = new List<COSDictionary>();
        for (var index = 0; index < document.GetNumberOfPages(); index++)
        {
            var page = document.GetPage(index);
            var prefix = "page/" + index.ToString(CultureInfo.InvariantCulture);
            var resource = page.GetResources().GetCOSObject();
            var alias = resources.FindIndex(prior => ReferenceEquals(prior, resource));
            if (alias < 0) alias = index;
            resources.Add(resource);
            trace[prefix + "/id"] = page.GetCOSObject().GetString(ProbeId);
            trace[prefix + "/media"] = Numbers(page.GetMediaBox());
            trace[prefix + "/crop"] = Numbers(page.GetCropBox());
            trace[prefix + "/rotation"] = page.GetRotation().ToString(CultureInfo.InvariantCulture);
            trace[prefix + "/content"] = Bytes(page.GetContents());
            trace[prefix + "/resource-id"] = resource.GetString(ResourceId);
            trace[prefix + "/resource-value"] = resource.GetString(ResourceValue);
            trace[prefix + "/resource-alias"] = alias.ToString(CultureInfo.InvariantCulture);
            trace[prefix + "/resource-indirect"] =
                (page.GetCOSObject().GetItem(COSName.Resources) is COSObject)
                    .ToString().ToLowerInvariant();
        }
        var fields = new List<PDField>();
        var form = document.GetDocumentCatalog().GetAcroForm();
        if (form is not null)
        {
            var iterator = form.GetFieldTree().Iterator();
            while (iterator.HasNext()) fields.Add(iterator.Next()!);
        }
        fields.Sort((left, right) => string.CompareOrdinal(
            left.GetFullyQualifiedName(), right.GetFullyQualifiedName()));
        trace["fields"] = fields.Count.ToString(CultureInfo.InvariantCulture);
        for (var index = 0; index < fields.Count; index++)
        {
            var field = fields[index];
            var prefix = "field/" + index.ToString(CultureInfo.InvariantCulture);
            trace[prefix + "/name"] = field.GetFullyQualifiedName();
            trace[prefix + "/type"] = field.GetType().Name;
            trace[prefix + "/value"] = field.GetValueAsString();
            trace[prefix + "/widgets"] = field.GetWidgets().Count.ToString(CultureInfo.InvariantCulture);
            for (var widgetIndex = 0; widgetIndex < field.GetWidgets().Count; widgetIndex++)
            {
                var widget = field.GetWidgets()[widgetIndex];
                var widgetPrefix = prefix + "/widget/" + widgetIndex.ToString(CultureInfo.InvariantCulture);
                var appearance = widget.GetAppearance();
                var normal = appearance?.GetNormalAppearance();
                trace[widgetPrefix + "/page"] = document.GetPages().IndexOf(widget.GetPage())
                    .ToString(CultureInfo.InvariantCulture);
                trace[widgetPrefix + "/appearance"] = AppearanceKind(normal);
                trace[widgetPrefix + "/appearance-keys"] = AppearanceKeys(normal);
                trace[widgetPrefix + "/state"] = widget.GetAppearanceState()?.GetName() ?? "null";
            }
        }
    }

    private static string Numbers(PDRectangle box) => string.Join(",", new[]
    {
        box.GetLowerLeftX(), box.GetLowerLeftY(), box.GetWidth(), box.GetHeight()
    }.Select(value => value.ToString("0.0", CultureInfo.InvariantCulture)));

    private static string Category(Exception error) => error switch
    {
        IOException => "io",
        InvalidOperationException => "state",
        IndexOutOfRangeException => "index",
        ArgumentOutOfRangeException => "index",
        ArgumentException => "argument",
        _ => error.GetType().FullName!
    };

    private static int Main(string[] args)
    {
        if (args.Length != 4 || (args[0] != "write" && args[0] != "cross"))
            throw new ArgumentException("usage: write|cross input exchange trace");
        var rows = Input(args[1]);
        Directory.CreateDirectory(args[2]);
        var artifact = Path.Combine(args[2],
            args[0] == "write" ? "dotnet-page-form.pdf" : "java-page-form.pdf");
        var trace = new SortedDictionary<string, string>(StringComparer.Ordinal)
        {
            ["reader"] = "dotnet"
        };
        try
        {
            if (args[0] == "write") Perform(rows, artifact);
            using var document = Loader.LoadPDF(new FileInfo(artifact));
            Inspect(trace, Model(rows)[2], document);
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            trace["template"] = Model(rows)[2];
            trace["status"] = "exception";
            trace["exception"] = Category(error);
            trace["operation"] = args[0];
        }
        File.WriteAllLines(args[3], trace.Select(entry => entry.Key + "\t" + entry.Value),
            new UTF8Encoding(false));
        return 0;
    }
}
