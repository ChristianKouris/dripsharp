import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Collectors;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.preflight.ValidationResult;
import org.apache.pdfbox.preflight.ValidationResult.ValidationError;
import org.apache.pdfbox.preflight.parser.PreflightParser;
import org.apache.xmpbox.DateConverter;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.DublinCoreSchema;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.schema.XMPBasicSchema;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.xml.DomXmpParser;
import org.apache.xmpbox.xml.XmpParsingException;
import org.apache.xmpbox.xml.XmpSerializer;

public final class MetadataWorkflowOracle {
    private static final long SEED = 0x5eed2408L;
    private static final int GENERATED_CASES = 3;
    private static final List<String> CASES = Arrays.asList(
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
            "generated-002");

    private MetadataWorkflowOracle() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "Expected mode, workflow directory, qualified source PDF, and output trace.");
        }
        Locale.setDefault(Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        Path workflow = Paths.get(args[1]);
        if (args[0].equals("generate")) {
            generate(workflow.resolve("java"), Paths.get(args[2]), "java");
        } else if (args[0].equals("observe")) {
            observe(workflow, Paths.get(args[3]));
        } else {
            throw new IllegalArgumentException("Unknown mode: " + args[0]);
        }
    }

    private static void generate(Path output, Path source, String producer) throws Exception {
        Files.createDirectories(output);
        byte[] base = metadataBytes(source);
        String xml = utf8Packet(base);
        for (String id : CASES) {
            Path destination = output.resolve(id + ".pdf");
            if (id.equals("baseline-valid")) {
                Files.copy(source, destination,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                continue;
            }
            byte[] packet = packetFor(id, xml, producer);
            Files.deleteIfExists(destination);
            try (PDDocument document = Loader.loadPDF(source.toFile())) {
                document.getDocumentCatalog().setMetadata(
                        new PDMetadata(document, new ByteArrayInputStream(packet)));
                document.save(destination.toFile());
            }
        }
        Files.write(
                output.resolve("generation.txt"),
                ("generator=metadata-workflow-v1\nseed=" + Long.toUnsignedString(SEED)
                        + "\ncases=" + String.join(",", CASES) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] packetFor(String id, String base, String producer) throws Exception {
        if (id.equals("baseline-valid")) {
            return base.getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("constructed-properties")) {
            return constructedPacket(producer);
        }
        if (id.equals("utf16-encoding")) {
            String declared = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n" + base;
            byte[] encoded = declared.getBytes(StandardCharsets.UTF_16LE);
            byte[] result = new byte[encoded.length + 2];
            result[0] = (byte) 0xff;
            result[1] = (byte) 0xfe;
            System.arraycopy(encoded, 0, result, 2, encoded.length);
            return result;
        }
        if (id.equals("namespace-remap")) {
            return required(base, "xmlns:xmp=", "xmlns:pcx=")
                    .replace("<xmp:", "<pcx:")
                    .replace("</xmp:", "</pcx:")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("date-minimum")) {
            return required(base,
                    "2014-11-18T11:02:14+01:00",
                    "0001-01-01T00:00:00Z").getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("number-maximum")) {
            return required(base, "<pdfaid:part>1</pdfaid:part>",
                    "<pdfaid:part>2147483647</pdfaid:part>")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("missing-optional")) {
            return base.replaceAll("(?s)\\s*<xmp:CreatorTool>.*?</xmp:CreatorTool>", "")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("missing-required")) {
            return base.replaceAll("(?s)\\s*<pdfaid:conformance>.*?</pdfaid:conformance>", "")
                    .getBytes(StandardCharsets.UTF_8);
        }
        if (id.equals("invalid-control")) {
            byte[] value = required(base, "Maruan Sahyoun", "Maruan\u0001Sahyoun")
                    .getBytes(StandardCharsets.UTF_8);
            return value;
        }
        int index = Integer.parseInt(id.substring("generated-".length()));
        long state = SEED;
        for (int current = 0; current <= index; current++) {
            state = (state * 1664525L + 1013904223L) & 0xffffffffL;
        }
        String text = "generated-" + index + "-" + Long.toHexString(state)
                + "-" + producer + "-caf\u00e9";
        return required(base, "Maruan Sahyoun", text).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] constructedPacket(String producer) throws Exception {
        XMPMetadata metadata = XMPMetadata.createXMPMetadata(
                "\uFEFF", "metadata-workflow", "4096", "UTF-8");
        metadata.setEndXPacket("w");
        DublinCoreSchema dc = metadata.createAndAddDublinCoreSchema();
        dc.setFormat("application/pdf");
        dc.setTitle("x-default", "Constructed " + producer + " metadata");
        dc.addCreator("Workflow " + producer);
        XMPBasicSchema basic = metadata.createAndAddXMPBasicSchema();
        basic.setModifyDate(DateConverter.toCalendar("2024-02-29T23:59:59-07:00"));
        basic.setCreatorTool("MetadataWorkflow/v1");
        PDFAIdentificationSchema pdfaid = metadata.createAndAddPDFAIdentificationSchema();
        pdfaid.setPart(1);
        pdfaid.setConformance("B");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new XmpSerializer().serialize(metadata, output, true);
        return output.toByteArray();
    }

    private static void observe(Path workflow, Path output) throws Exception {
        List<String> rows = new ArrayList<>();
        for (String producer : Arrays.asList("java", "dotnet")) {
            for (String id : CASES) {
                Path pdf = workflow.resolve(producer).resolve(id + ".pdf");
                rows.add("metadata-workflow\t" + producer + "/" + id + "\t"
                        + observation(pdf));
            }
        }
        Files.write(output, rows, StandardCharsets.UTF_8);
    }

    private static String observation(Path pdf) {
        String parse = "missing";
        String title = "null";
        String creator = "null";
        String modifyDate = "null";
        String part = "null";
        String conformance = "null";
        String extension = "null";
        String stage = "reopen";
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            stage = "metadata";
            PDMetadata stream = document.getDocumentCatalog().getMetadata();
            if (stream != null) {
                try (InputStream input = stream.exportXMPMetadata()) {
                    XMPMetadata metadata = new DomXmpParser().parse(input);
                    parse = "ok";
                    DublinCoreSchema dc = metadata.getDublinCoreSchema();
                    XMPBasicSchema basic = metadata.getXMPBasicSchema();
                    PDFAIdentificationSchema pdfaid = metadata.getPDFAIdentificationSchema();
                    XMPSchema custom = metadata.getSchema("urn:dripsharp:metadata-workflow/");
                    title = dc == null ? "null" : value(dc.getTitle("x-default"));
                    creator = dc == null || dc.getCreators() == null
                            ? "null" : String.join(",", dc.getCreators());
                    modifyDate = basic == null || basic.getModifyDateProperty() == null
                            ? "null" : basic.getModifyDateProperty().getStringValue();
                    part = pdfaid == null ? "null" : value(pdfaid.getPart());
                    conformance = pdfaid == null ? "null" : value(pdfaid.getConformance());
                    extension = custom == null ? "null"
                            : value(custom.getUnqualifiedTextPropertyValue("sample"));
                } catch (XmpParsingException exception) {
                    parse = "xmp:" + exception.getErrorType().name();
                } catch (RuntimeException exception) {
                    parse = "runtime:" + exception.getClass().getSimpleName();
                }
            }
            stage = "validate";
        } catch (Exception exception) {
            return join("stage=" + stage, "failure=" + exception.getClass().getSimpleName());
        }

        try {
            ValidationResult result = PreflightParser.validate(pdf.toFile());
            List<String> codes = result.getErrorsList().stream()
                    .map(ValidationError::getErrorCode).sorted().collect(Collectors.toList());
            List<String> pages = result.getErrorsList().stream()
                    .map(error -> value(error.getPageNumber())).sorted().collect(Collectors.toList());
            return join(
                    "stage=complete", "parse=" + parse, "title=" + title,
                    "creator=" + creator, "modify-date=" + modifyDate,
                    "part=" + part, "conformance=" + conformance,
                    "extension=" + extension, "valid=" + result.isValid(),
                    "error-count=" + result.getErrorsList().size(),
                    "codes=" + String.join(",", codes),
                    "pages=" + String.join(",", pages));
        } catch (Exception exception) {
            return join("stage=" + stage, "parse=" + parse,
                    "failure=" + exception.getClass().getSimpleName());
        }
    }

    private static byte[] metadataBytes(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile());
             InputStream input = document.getDocumentCatalog().getMetadata().exportXMPMetadata()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String utf8Packet(byte[] bytes) {
        int length = bytes.length;
        while (length > 0 && bytes[length - 1] == 0) {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    private static String required(String value, String before, String after) {
        if (!value.contains(before)) {
            throw new IllegalStateException("Qualified metadata fixture misses: " + before);
        }
        return value.replace(before, after);
    }

    private static String value(Object value) {
        return value == null ? "null" : value.toString();
    }

    private static String join(String... values) {
        List<String> rendered = new ArrayList<>();
        Collections.addAll(rendered, values);
        return rendered.stream().map(MetadataWorkflowOracle::escape)
                .collect(Collectors.joining("|"));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("|", "\\|")
                .replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}
