// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.*;
import org.apache.pdfbox.util.Matrix;

public final class FontRenderOracle {
    private static final List<String> trace = new ArrayList<>();
    private static void row(String key, Object value) { trace.add(key + "\t" + value); }
    private static String bits(float value) {
        return String.format(Locale.ROOT, "%08X", Float.floatToIntBits(value));
    }
    private static String units(String value) {
        return value.chars().mapToObj(c -> String.format(Locale.ROOT, "%04X", c))
                .collect(Collectors.joining(","));
    }
    private static String points(String value) {
        return value.codePoints().mapToObj(Integer::toString).collect(Collectors.joining(","));
    }
    private static float f(String value) { return Float.parseFloat(value); }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("mode input assets exchange output");
        Path input = Paths.get(args[1]), assets = Paths.get(args[2]);
        Path exchange = Paths.get(args[3]), output = Paths.get(args[4]);
        Files.createDirectories(exchange);
        Files.createDirectories(output);
        if (args[0].equals("write")) {
            write(input, assets, exchange.resolve("java.pdf"));
        } else if (args[0].equals("observe")) {
            observe(exchange.resolve("java.pdf"), output, "java", 1f, false);
            observe(exchange.resolve("dotnet.pdf"), output, "dotnet", 1f, false);
        } else if (args[0].equals("fixtures")) {
            for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
                String[] fields = line.split("\t", -1);
                observe(assets.resolve(fields[1]), output, fields[0], 0.25f, true);
            }
        } else throw new IllegalArgumentException("Unknown mode");
        Files.write(output.resolve("trace.tsv"), trace, StandardCharsets.UTF_8);
    }

    private static void write(Path input, Path assets, Path pdf) throws Exception {
        List<String> lines = Files.readAllLines(input, StandardCharsets.UTF_8);
        if (!lines.get(0).equals("font-render\t1")) throw new IllegalArgumentException("Input version");
        try (PDDocument doc = new PDDocument()) {
            for (String line : lines.subList(1, lines.size())) {
                String[] p = line.split("\t", -1);
                if (p.length != 17 || !p[0].equals("page")) throw new IllegalArgumentException("Page input");
                PDPage page = new PDPage(new PDRectangle(240, 160));
                page.setRotation(Integer.parseInt(p[6]));
                doc.addPage(page);
                PDType0Font font;
                try (InputStream stream = Files.newInputStream(assets.resolve(p[1]))) {
                    font = PDType0Font.load(doc, stream, false);
                }
                try (PDPageContentStream content = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.OVERWRITE, false, false)) {
                    content.saveGraphicsState();
                    content.addRect(f(p[13]), f(p[14]), f(p[15]), f(p[16]));
                    content.clip();
                    content.transform(new Matrix(f(p[7]), f(p[8]), f(p[9]), f(p[10]), f(p[11]), f(p[12])));
                    content.beginText();
                    content.setFont(font, f(p[3]));
                    content.setTextMatrix(Matrix.getTranslateInstance(f(p[4]), f(p[5])));
                    content.showText(new String(Base64.getDecoder().decode(p[2]), StandardCharsets.UTF_8));
                    content.endText();
                    content.restoreGraphicsState();
                }
            }
            doc.save(pdf.toFile(), CompressParameters.NO_COMPRESSION);
        }
    }

    private static final class Collector extends PDFTextStripper {
        private final List<String> positions = new ArrayList<>();
        private final List<String> glyphs = new ArrayList<>();
        Collector() throws java.io.IOException { super(); }
        @Override protected void showGlyph(Matrix m, PDFont font, int code,
                org.apache.pdfbox.util.Vector displacement) throws java.io.IOException {
            String unicode = font.toUnicode(code);
            glyphs.add(code + ":" + (unicode == null ? "null" : units(unicode)) + "@"
                    + bits(m.getScaleX()) + "," + bits(m.getShearY()) + ","
                    + bits(m.getShearX()) + "," + bits(m.getScaleY()) + ","
                    + bits(m.getTranslateX()) + "," + bits(m.getTranslateY()) + ","
                    + bits(displacement.getX()) + "," + bits(displacement.getY()));
            super.showGlyph(m, font, code, displacement);
        }
        @Override protected void processTextPosition(TextPosition p) {
            positions.add(units(p.getUnicode()) + "[" + Arrays.stream(p.getCharacterCodes())
                    .mapToObj(Integer::toString).collect(Collectors.joining(",")) + "]@"
                    + bits(p.getXDirAdj()) + "," + bits(p.getYDirAdj()) + ","
                    + bits(p.getWidthDirAdj()) + "," + bits(p.getHeightDir()) + ","
                    + bits(p.getDir()) + "," + bits(p.getFontSizeInPt()));
            super.processTextPosition(p);
        }
    }

    private static void observe(Path path, Path output, String id, float scale, boolean fixture) throws Exception {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            row(id + "/pages", doc.getNumberOfPages());
            // Existing fixtures are intentionally restricted to their first page.
            int count = fixture ? 1 : doc.getNumberOfPages();
            for (int index = 0; index < count; index++) {
                String key = id + "/p" + index;
                PDPage page = doc.getPage(index);
                PDRectangle box = page.getCropBox();
                row(key + "/structure", page.getRotation() + "," + bits(box.getLowerLeftX())
                        + "," + bits(box.getLowerLeftY()) + "," + bits(box.getWidth())
                        + "," + bits(box.getHeight()) + "," + page.getAnnotations().size());
                for (boolean sorted : new boolean[] {false, true}) {
                    Collector collector = new Collector();
                    collector.setStartPage(index + 1); collector.setEndPage(index + 1);
                    collector.setSortByPosition(sorted); collector.setLineSeparator("\n");
                    String text = collector.getText(doc);
                    row(key + "/text-" + sorted, units(text));
                    row(key + "/codepoints-" + sorted, points(text));
                    row(key + "/positions-" + sorted, String.join(";", collector.positions));
                    row(key + "/glyphs-" + sorted, String.join(";", collector.glyphs));
                }
                List<String> fontRows = new ArrayList<>();
                for (COSName name : page.getResources().getFontNames()) {
                    PDFont font = page.getResources().getFont(name);
                    fontRows.add(name.getName() + ":" + font.getSubType() + ":" + font.isEmbedded()
                            + ":" + font.isDamaged());
                    if (!fixture && !font.isEmbedded()) throw new IllegalStateException("Unembedded font");
                }
                Collections.sort(fontRows); row(key + "/fonts", String.join(";", fontRows));
                if (!fixture) {
                    try (InputStream stream = page.getContents();
                         java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int n;
                        while ((n = stream.read(buffer)) != -1) bytes.write(buffer, 0, n);
                        row(key + "/content", Base64.getEncoder().encodeToString(bytes.toByteArray()));
                    }
                }
                int width = (int)Math.floor(box.getWidth() * scale);
                int height = (int)Math.floor(box.getHeight() * scale);
                if (width < 1 || height < 1 || width > 512 || height > 512)
                    throw new IllegalArgumentException("Raster bounds");
                BufferedImage image = new PDFRenderer(doc).renderImage(index, scale, ImageType.ARGB);
                String imageId = id + "-p" + index;
                row(key + "/image", image.getWidth() + "," + image.getHeight());
                byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
                int offset = 0;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                    int pixel = image.getRGB(x, y);
                    rgba[offset++] = (byte)(pixel >> 16); rgba[offset++] = (byte)(pixel >> 8);
                    rgba[offset++] = (byte)pixel; rgba[offset++] = (byte)(pixel >> 24);
                }
                Files.write(output.resolve(imageId + ".rgba"), rgba);
                ImageIO.write(image, "png", output.resolve(imageId + ".png").toFile());
            }
        }
    }
}
