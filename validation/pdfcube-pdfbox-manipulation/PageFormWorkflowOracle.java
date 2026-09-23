// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.annotation.*;
import org.apache.pdfbox.pdmodel.interactive.form.*;

/** Pinned-PDFBox side of the bounded generated page/form workflow protocol. */
public final class PageFormWorkflowOracle {
  private static final COSName PROBE_ID = COSName.getPDFName("ProbeId");
  private static final COSName RESOURCE_ID = COSName.getPDFName("ResourceId");
  private static final COSName RESOURCE_VALUE = COSName.getPDFName("ResourceValue");

  private PageFormWorkflowOracle() {}

  static String decode(String value) {
    return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
  }

  static List<String[]> input(Path path) throws IOException {
    List<String[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
      rows.add(line.split("\\t", -1));
    }
    return rows;
  }

  static String[] model(List<String[]> rows) {
    return rows.stream().filter(row -> row[0].equals("model")).findFirst().get();
  }

  static List<String> selection(List<String[]> rows) {
    String[] selected = rows.stream().filter(row -> row[0].equals("select")).findFirst().get();
    return Arrays.asList(selected).subList(1, selected.length);
  }

  static PDDocument create(List<String[]> rows) throws Exception {
    PDDocument document = new PDDocument();
    Map<String, PDResources> resources = new LinkedHashMap<>();
    for (String[] row : rows) {
      if (!row[0].equals("resource")) continue;
      PDResources resource = new PDResources();
      resource.getCOSObject().setString(RESOURCE_ID, row[1]);
      resource.getCOSObject().setString(RESOURCE_VALUE, decode(row[2]));
      resources.put(row[1], resource);
    }
    Map<String, PDPage> pages = new LinkedHashMap<>();
    for (String[] row : rows) {
      if (!row[0].equals("page")) continue;
      PDPage page = new PDPage(new PDRectangle(
          Integer.parseInt(row[3]), Integer.parseInt(row[4])));
      page.getCOSObject().setString(PROBE_ID, row[1]);
      page.setResources(resources.get(row[2]));
      page.setRotation(Integer.parseInt(row[5]));
      document.addPage(page);
      org.apache.pdfbox.pdmodel.common.PDStream stream =
          new org.apache.pdfbox.pdmodel.common.PDStream(document);
      page.setContents(stream);
      try (OutputStream output = stream.createOutputStream()) {
        output.write(decode(row[6]).getBytes(StandardCharsets.US_ASCII));
      }
      pages.put(row[1], page);
    }

    PDAcroForm form = new PDAcroForm(document);
    form.setNeedAppearances(false);
    PDResources defaults = new PDResources();
    defaults.put(COSName.getPDFName("Helv"),
        new PDType1Font(Standard14Fonts.FontName.HELVETICA));
    form.setDefaultResources(defaults);
    form.setDefaultAppearance("/Helv 10 Tf 0 g");
    document.getDocumentCatalog().setAcroForm(form);
    form = document.getDocumentCatalog().getAcroForm();
    List<PDField> fields = new ArrayList<>();
    for (String[] row : rows) {
      if (!row[0].equals("field")) continue;
      PDPage page = pages.get(row[3]);
      if (row[2].equals("text")) {
        PDTextField field = new PDTextField(form);
        field.setPartialName(row[1]);
        addWidget(field, page, new PDRectangle(24, 40, 160, 24));
        fields.add(field);
        field.setValue(decode(row[4]));
      } else {
        PDCheckBox field = new PDCheckBox(form);
        field.setPartialName(row[1]);
        PDAnnotationWidget widget = field.getWidgets().get(0);
        widget.setRectangle(new PDRectangle(24, 80, 24, 24));
        widget.setPage(page);
        COSDictionary appearances = new COSDictionary();
        appearances.setItem(COSName.Off, appearanceStream(document));
        appearances.setItem(COSName.getPDFName("Yes"), appearanceStream(document));
        PDAppearanceDictionary dictionary = new PDAppearanceDictionary();
        dictionary.setNormalAppearance(new PDAppearanceEntry(appearances));
        widget.setAppearance(dictionary);
        widget.setAppearanceState("Off");
        page.getAnnotations().add(widget);
        fields.add(field);
        field.setValue(Boolean.parseBoolean(row[4]) ? "Yes" : "Off");
      }
    }
    form.setFields(fields);
    return document;
  }

  static void addWidget(PDField field, PDPage page, PDRectangle rectangle) throws IOException {
    PDAnnotationWidget widget = field.getWidgets().get(0);
    widget.setRectangle(rectangle);
    widget.setPage(page);
    page.getAnnotations().add(widget);
  }

  static PDAppearanceStream appearanceStream(PDDocument document) throws IOException {
    PDAppearanceStream stream = new PDAppearanceStream(document);
    stream.setBBox(new PDRectangle(24, 24));
    stream.setResources(new PDResources());
    try (OutputStream output = stream.getCOSObject().createOutputStream()) {
      output.write("q Q\n".getBytes(StandardCharsets.US_ASCII));
    }
    return stream;
  }

  static int pageIndex(PDDocument document, String id) {
    for (int index = 0; index < document.getNumberOfPages(); index++) {
      if (id.equals(document.getPage(index).getCOSObject().getString(PROBE_ID))) return index;
    }
    throw new IllegalArgumentException("Missing selected page " + id);
  }

  static void perform(List<String[]> rows, Path output) throws Exception {
    String template = model(rows)[2];
    try (PDDocument source = create(rows)) {
      if (template.equals("split-and-merge")) {
        Splitter splitter = new Splitter();
        splitter.setSplitAtPage(1);
        List<PDDocument> parts = splitter.split(source);
        try (PDDocument destination = new PDDocument()) {
          PDFMergerUtility merger = new PDFMergerUtility();
          for (String id : selection(rows)) {
            merger.appendDocument(destination, parts.get(pageIndex(source, id)));
          }
          destination.save(output.toFile(), CompressParameters.NO_COMPRESSION);
        } finally {
          for (PDDocument part : parts) part.close();
        }
      } else if (template.equals("import-and-reorder")) {
        try (PDDocument destination = new PDDocument()) {
          for (String id : selection(rows)) {
            destination.importPage(source.getPage(pageIndex(source, id)));
          }
          destination.save(output.toFile(), CompressParameters.NO_COMPRESSION);
        }
      } else if (template.equals("set-form-value-save-reopen")) {
        PDAcroForm form = source.getDocumentCatalog().getAcroForm();
        for (String[] row : rows) {
          if (!row[0].equals("field")) continue;
          PDField field = form.getField(row[1]);
          field.setValue(row[2].equals("text") ? decode(row[5])
              : Boolean.parseBoolean(row[5]) ? "Yes" : "Off");
        }
        source.save(output.toFile(), CompressParameters.NO_COMPRESSION);
      } else {
        throw new IllegalArgumentException("Unknown template " + template);
      }
    }
  }

  static String bytes(InputStream input) throws IOException {
    try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
      StringBuilder rendered = new StringBuilder();
      for (byte value : output.toByteArray()) {
        rendered.append(String.format(Locale.ROOT, "%02x", value & 255));
      }
      return rendered.toString();
    }
  }

  static String appearanceKind(PDAppearanceEntry entry) {
    if (entry == null) return "null";
    return entry.isStream() ? "stream" : "subdictionary";
  }

  static String appearanceKeys(PDAppearanceEntry entry) {
    if (entry == null || !entry.isSubDictionary()) return "";
    return entry.getSubDictionary().keySet().stream()
        .map(COSName::getName).sorted().collect(Collectors.joining(","));
  }

  static void inspect(Map<String, String> trace, String template, PDDocument document)
      throws Exception {
    trace.put("status", "ok");
    trace.put("template", template);
    trace.put("pages", Integer.toString(document.getNumberOfPages()));
    List<COSDictionary> resources = new ArrayList<>();
    for (int index = 0; index < document.getNumberOfPages(); index++) {
      PDPage page = document.getPage(index);
      String prefix = "page/" + index;
      COSDictionary resource = page.getResources().getCOSObject();
      int alias = -1;
      for (int prior = 0; prior < resources.size(); prior++) {
        if (resources.get(prior) == resource) { alias = prior; break; }
      }
      if (alias < 0) alias = index;
      resources.add(resource);
      trace.put(prefix + "/id", page.getCOSObject().getString(PROBE_ID));
      trace.put(prefix + "/media", numbers(page.getMediaBox()));
      trace.put(prefix + "/crop", numbers(page.getCropBox()));
      trace.put(prefix + "/rotation", Integer.toString(page.getRotation()));
      trace.put(prefix + "/content", bytes(page.getContents()));
      trace.put(prefix + "/resource-id", resource.getString(RESOURCE_ID));
      trace.put(prefix + "/resource-value", resource.getString(RESOURCE_VALUE));
      trace.put(prefix + "/resource-alias", Integer.toString(alias));
      trace.put(prefix + "/resource-indirect",
          Boolean.toString(page.getCOSObject().getItem(COSName.RESOURCES) instanceof COSObject));
    }
    PDAcroForm form = document.getDocumentCatalog().getAcroForm();
    List<PDField> fields = new ArrayList<>();
    if (form != null) for (PDField field : form.getFieldTree()) fields.add(field);
    fields.sort(Comparator.comparing(PDField::getFullyQualifiedName));
    trace.put("fields", Integer.toString(fields.size()));
    for (int index = 0; index < fields.size(); index++) {
      PDField field = fields.get(index);
      String prefix = "field/" + index;
      trace.put(prefix + "/name", field.getFullyQualifiedName());
      trace.put(prefix + "/type", field.getClass().getSimpleName());
      trace.put(prefix + "/value", field.getValueAsString());
      trace.put(prefix + "/widgets", Integer.toString(field.getWidgets().size()));
      for (int widgetIndex = 0; widgetIndex < field.getWidgets().size(); widgetIndex++) {
        PDAnnotationWidget widget = field.getWidgets().get(widgetIndex);
        String widgetPrefix = prefix + "/widget/" + widgetIndex;
        PDAppearanceDictionary appearance = widget.getAppearance();
        PDAppearanceEntry normal = appearance == null ? null : appearance.getNormalAppearance();
        trace.put(widgetPrefix + "/page",
            Integer.toString(document.getPages().indexOf(widget.getPage())));
        trace.put(widgetPrefix + "/appearance", appearanceKind(normal));
        trace.put(widgetPrefix + "/appearance-keys", appearanceKeys(normal));
        trace.put(widgetPrefix + "/state", widget.getAppearanceState() == null
            ? "null" : widget.getAppearanceState().getName());
      }
    }
  }

  static String numbers(PDRectangle box) {
    return String.format(Locale.ROOT, "%.1f,%.1f,%.1f,%.1f",
        box.getLowerLeftX(), box.getLowerLeftY(), box.getWidth(), box.getHeight());
  }

  static String category(Throwable error) {
    if (error instanceof IOException) return "io";
    if (error instanceof IllegalStateException) return "state";
    if (error instanceof IndexOutOfBoundsException) return "index";
    if (error instanceof IllegalArgumentException) return "argument";
    return error.getClass().getName();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 4 || !(args[0].equals("write") || args[0].equals("cross"))) {
      throw new IllegalArgumentException("usage: write|cross input exchange trace");
    }
    List<String[]> rows = input(Paths.get(args[1]));
    Path exchange = Paths.get(args[2]);
    Files.createDirectories(exchange);
    Path artifact = exchange.resolve(args[0].equals("write")
        ? "java-page-form.pdf" : "dotnet-page-form.pdf");
    Map<String, String> trace = new TreeMap<>();
    trace.put("reader", "java");
    try {
      if (args[0].equals("write")) perform(rows, artifact);
      try (PDDocument document = Loader.loadPDF(artifact.toFile())) {
        inspect(trace, model(rows)[2], document);
      }
    } catch (Throwable error) {
      error.printStackTrace(System.err);
      trace.put("template", model(rows)[2]);
      trace.put("status", "exception");
      trace.put("exception", category(error));
      trace.put("operation", args[0]);
    }
    List<String> output = trace.entrySet().stream()
        .map(entry -> entry.getKey() + "\t" + entry.getValue()).collect(Collectors.toList());
    Files.write(Paths.get(args[3]), output, StandardCharsets.UTF_8);
  }
}
