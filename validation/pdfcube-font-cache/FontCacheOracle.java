// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
import java.io.File;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.PDFTextStripper;

public final class FontCacheOracle {
    private static final List<String> observations = new ArrayList<>();
    private static void observe(String key, Object value) { observations.add(key + "\t" + value); }
    private static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
    private static String decode(String value) {
        return value.equals("-") ? null : new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        String[] settings = Files.readString(Path.of(args[1])).split("\t", -1);
        String[] names = {"pdfbox.fontcache", "user.home"};
        for (int i = 0; i < names.length; i++) {
            String value = decode(settings[i]);
            if (value == null) System.clearProperty(names[i]); else System.setProperty(names[i], value);
        }
        System.setProperty("java.io.tmpdir", root.resolve("temporary").toString());
        System.setProperty("pdfbox.fontcache.skipchecksums", "true");
        // Bypass only host font discovery. Exercise the original scanner/cache writer
        // with one fixed fixture, then install that real provider for public PDF use.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe"); singleton.setAccessible(true);
        Class<?> type = Class.forName("org.apache.pdfbox.pdmodel.font.FileSystemFontProvider");
        Object provider = unsafeClass.getMethod("allocateInstance", Class.class).invoke(singleton.get(null), type);
        field(provider, "fontInfoList", new ArrayList<>());
        field(provider, "cache", new FontCache());
        call(provider, "scanFonts", new Class<?>[]{List.class}, List.of(new File(args[2])));
        File selected = (File)call(provider, "getDiskCacheFile", new Class<?>[]{});
        Path path = selected.toPath().toAbsolutePath().normalize();
        boolean owned = path.startsWith(root);
        observe("selected", owned ? root.relativize(path) : path);
        // JDK 25 accepts an empty directory, but File("", child) targets the
        // filesystem root. Observe that drift without writing outside our fixture.
        if (owned) call(provider, "saveDiskCache", new Class<?>[]{});
        observe("cache-write", owned ? "executed" : "guarded-outside-scenario");
        observe("cache-present", owned && Files.isRegularFile(path));
        if (owned) {
            List<String> rows = Files.readAllLines(path);
            observe("cache-rows", rows.size());
            observe("cache-font", rows.get(0).split("\\|", -1)[0]);
            List<?> loaded = (List<?>)call(provider, "loadDiskCache", new Class<?>[]{List.class}, List.of(new File(args[2])));
            observe("cache-reloaded", loaded == null ? -1 : loaded.size());
        }
        Object mapper = FontMappers.instance();
        call(mapper, "setProvider", new Class<?>[]{FontProvider.class}, provider);
        PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        observe("substitute", font.getFontBoxFont().getName());
        Path output = root.resolve("font.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText(); content.setFont(font, 12); content.newLineAtOffset(40, 700);
                content.showText("Font cache fallback"); content.endText();
            }
            document.save(output.toFile());
        }
        observe("pdf-created", Files.size(output) > 100);
        try (PDDocument reopened = Loader.loadPDF(output.toFile())) {
            observe("pages", reopened.getNumberOfPages());
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");
            observe("text", Base64.getEncoder().encodeToString(stripper.getText(reopened).getBytes(StandardCharsets.UTF_8)));
        }
        try (var files = Files.walk(root)) {
            observe("side-effects", files.filter(Files::isRegularFile).map(root::relativize)
                .map(Path::toString).sorted().reduce((a,b) -> a + ";" + b).orElse(""));
        }
        Files.write(Path.of(args[3]), observations, StandardCharsets.UTF_8);
    }
}
