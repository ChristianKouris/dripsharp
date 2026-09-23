// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.text.PDFTextStripper;

/** Independent observer for the recorded, bounded lifecycle workflow protocol. */
public final class WorkflowOracle {
  static COSName key(String s) { return COSName.getPDFName(s); }
  static String decode(String s) { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
  static String hex(String s) {
    if (s == null) return "null";
    StringBuilder b = new StringBuilder("u:");
    for (char c : s.toCharArray()) b.append(String.format(Locale.ROOT, "%04x", (int)c));
    return b.toString();
  }
  static void row(List<String> rows, String id, Object value) { rows.add(id + "\t" + value); }
  static String bytes(InputStream input) throws Exception {
    try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      StringBuilder result = new StringBuilder();
      for (byte b : out.toByteArray()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
      return result.toString();
    }
  }
  static void tree(List<String> rows, String id, COSDictionary node, COSDictionary parent,
                   Set<COSDictionary> seen) {
    if (!seen.add(node)) throw new IllegalStateException("page tree cycle or duplicate");
    row(rows, id + "/parent", node.getDictionaryObject(COSName.PARENT) == parent);
    String type = node.getNameAsString(COSName.TYPE);
    row(rows, id + "/type", type);
    if ("Pages".equals(type)) {
      COSArray kids = (COSArray)node.getDictionaryObject(COSName.KIDS);
      row(rows, id + "/count", node.getInt(COSName.COUNT));
      row(rows, id + "/kids", kids.size());
      for (int i = 0; i < kids.size(); i++) tree(rows, id + "/" + i,
          (COSDictionary)kids.getObject(i), node, seen);
    } else row(rows, id + "/page", hex(node.getString(key("ProbeId"))));
  }
  static void inspect(List<String> rows, String id, PDDocument d) throws Exception {
    row(rows, id + "/title", hex(d.getDocumentInformation().getTitle()));
    row(rows, id + "/subject", hex(d.getDocumentInformation().getSubject()));
    row(rows, id + "/pages", d.getNumberOfPages());
    tree(rows, id + "/tree", d.getPages().getCOSObject(), null,
         Collections.newSetFromMap(new IdentityHashMap<>()));
    List<COSDictionary> resources = new ArrayList<>();
    for (int i = 0; i < d.getNumberOfPages(); i++) {
      PDPage p = d.getPage(i);
      String prefix = id + "/page/" + i;
      COSDictionary r = p.getResources().getCOSObject();
      int alias = -1;
      for (int j = 0; j < resources.size(); j++) if (resources.get(j) == r) { alias = j; break; }
      if (alias < 0) alias = i;
      resources.add(r);
      row(rows, prefix + "/id", hex(p.getCOSObject().getString(key("ProbeId"))));
      row(rows, prefix + "/size", (int)p.getMediaBox().getWidth() + "," + (int)p.getMediaBox().getHeight());
      row(rows, prefix + "/rotation", p.getRotation());
      row(rows, prefix + "/resource-alias", alias);
      row(rows, prefix + "/resource-indirect", p.getCOSObject().getItem(COSName.RESOURCES) instanceof COSObject);
      row(rows, prefix + "/resource-id", hex(r.getString(key("ProbeId"))));
      row(rows, prefix + "/resource-value", hex(r.getString(key("Value"))));
      row(rows, prefix + "/font", p.getResources().getFont(key("F1")).getName());
      row(rows, prefix + "/content", bytes(p.getContents()));
      int streams = 0;
      Iterator<org.apache.pdfbox.pdmodel.common.PDStream> it = p.getContentStreams();
      while (it.hasNext()) {
        org.apache.pdfbox.pdmodel.common.PDStream s = it.next();
        row(rows, prefix + "/stream/" + streams + "/filter", hex(s.getCOSObject().getNameAsString(COSName.FILTER)));
        row(rows, prefix + "/stream/" + streams + "/bytes", bytes(s.createInputStream()));
        streams++;
      }
      row(rows, prefix + "/streams", streams);
    }
    row(rows, id + "/text", hex(new PDFTextStripper().getText(d)));
  }
  static PDDocument create(List<String[]> input) throws Exception {
    PDDocument d = new PDDocument();
    Map<String, PDResources> resources = new HashMap<>();
    for (String[] r : input) if (r[0].equals("resource")) {
      PDResources p = new PDResources();
      p.getCOSObject().setString(key("ProbeId"), r[1]);
      p.getCOSObject().setString(key("Value"), decode(r[2]));
      p.put(key("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
      resources.put(r[1], p);
    }
    for (String[] r : input) if (r[0].equals("page")) {
      PDPage p = new PDPage(new PDRectangle(Integer.parseInt(r[3]), Integer.parseInt(r[4])));
      p.getCOSObject().setString(key("ProbeId"), r[1]);
      p.setResources(resources.get(r[2]));
      d.addPage(p);
      try (PDPageContentStream s = new PDPageContentStream(d, p,
          PDPageContentStream.AppendMode.OVERWRITE, Boolean.parseBoolean(r[6]))) {
        s.appendRawCommands(decode(r[5]));
      }
    }
    String[] model = input.get(0);
    d.getDocumentInformation().setTitle(decode(model[2]));
    d.getDocumentInformation().setSubject("");
    if (Boolean.parseBoolean(model[3])) {
      COSDictionary root = d.getPages().getCOSObject();
      COSArray kids = (COSArray)root.getDictionaryObject(COSName.KIDS);
      COSDictionary branch = new COSDictionary();
      branch.setItem(COSName.TYPE, COSName.PAGES);
      branch.setItem(COSName.PARENT, root);
      branch.setItem(COSName.KIDS, kids);
      branch.setInt(COSName.COUNT, d.getNumberOfPages());
      for (int i = 0; i < kids.size(); i++) ((COSDictionary)kids.getObject(i)).setItem(COSName.PARENT, branch);
      COSArray branches = new COSArray(); branches.add(branch); root.setItem(COSName.KIDS, branches);
    }
    return d;
  }
  static PDPage page(PDDocument d, String id) {
    for (PDPage p : d.getPages()) if (id.equals(p.getCOSObject().getString(key("ProbeId")))) return p;
    throw new IllegalArgumentException("missing page " + id);
  }
  static String category(Exception e) {
    if (e instanceof IOException) return "io";
    if (e instanceof IllegalStateException) return "state";
    if (e instanceof IndexOutOfBoundsException) return "index";
    if (e instanceof IllegalArgumentException) return "argument";
    return e.getClass().getName();
  }
  public static void run(String[] args) throws Exception {
    // mode, input, exchange, output; cross mode observes every artifact of the other writer.
    List<String[]> input = new ArrayList<>();
    for (String line : Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)) input.add(line.split("\t", -1));
    Path dir = Paths.get(args[2]); Files.createDirectories(dir);
    List<String> rows = new ArrayList<>();
    if (args[0].equals("cross")) {
      for (String[] r : input) if (r[0].equals("op") && (r[2].equals("full") || r[2].equals("incremental"))) {
        try (PDDocument d = Loader.loadPDF(dir.resolve("dotnet-" + r[1] + ".pdf").toFile())) { inspect(rows, r[1], d); }
        catch (Exception e) { row(rows, r[1] + "/operation", "cross-load"); row(rows, r[1] + "/exception", category(e)); }
      }
    } else {
      PDDocument d = create(input);
      Path latest = null;
      try {
        for (String[] r : input) if (r[0].equals("op")) {
          String id = r[1], op = r[2];
          try {
          switch (op) {
            case "title": d.getDocumentInformation().setTitle(decode(r[3])); d.getDocumentInformation().getCOSObject().setNeedToBeUpdated(true); break;
            case "shared":
              for (PDPage p : d.getPages()) {
                COSDictionary res = p.getResources().getCOSObject();
                if (r[3].equals(res.getString(key("ProbeId")))) { res.setString(key("Value"), decode(r[4])); res.setNeedToBeUpdated(true); }
              }
              break;
            case "rotate": page(d, r[3]).setRotation(Integer.parseInt(r[4])); page(d, r[3]).getCOSObject().setNeedToBeUpdated(true); break;
            case "close": d.close(); row(rows, id + "/closed", d.getDocument().isClosed()); d = null; break;
            case "load": d = Loader.loadPDF(latest.toFile()); break;
            case "invalid-incremental":
              try (ByteArrayOutputStream b = new ByteArrayOutputStream()) {
                try { d.saveIncremental(b); row(rows, id + "/exception", "none"); }
                catch (IllegalStateException e) { row(rows, id + "/exception", "state"); }
                row(rows, id + "/written", b.size());
              }
              break;
            case "full": case "incremental":
              Path output = dir.resolve("java-" + id + ".pdf");
              if (op.equals("full")) d.save(output.toFile(), input.get(0)[1].equals("none") ? CompressParameters.NO_COMPRESSION : CompressParameters.DEFAULT_COMPRESSION);
              else {
                byte[] before = Files.readAllBytes(latest);
                try (OutputStream stream = Files.newOutputStream(output)) { d.saveIncremental(stream); }
                byte[] after = Files.readAllBytes(output);
                row(rows, id + "/prefix-preserved", after.length >= before.length && Arrays.equals(before, Arrays.copyOf(after, before.length)));
              }
              latest = output;
              try (PDDocument reopened = Loader.loadPDF(output.toFile())) { inspect(rows, id, reopened); }
              break;
            default: throw new IllegalArgumentException(op);
          }
          } catch (Exception e) {
            row(rows, id + "/operation", op); row(rows, id + "/exception", category(e)); break;
          }
        }
      } finally { if (d != null) d.close(); }
    }
    Files.write(Paths.get(args[3]), rows, StandardCharsets.UTF_8);
  }
}
