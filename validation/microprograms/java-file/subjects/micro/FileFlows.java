// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Values cross actual translated members before the native observer sees them. */
public final class FileFlows {
    public static String path(File file) { return file == null ? null : file.getPath(); }
    public static File nullable(String path) { return path == null ? null : new File(path); }
    public static String fromSource(FileSource source, String path) { return path(source.get(path)); }
    public static String fromEcho(FileBox box, String path) { return path(box.echo(nullable(path))); }
    public static String fromProtected(FileBox box, String path) { return path(box.viaProtected(nullable(path))); }
    public static String fromCallback(Function<File, File> callback, String path) {
        return path(callback.apply(nullable(path)));
    }
    public static String flow(String input, int route) {
        File file = nullable(input);
        FileBox box = new FileBox(file);
        switch (route) {
            case 0: return new File(input).getPath();
            case 1: return new File("anchor", input).getPath();
            case 2: return new File(new File("anchor"), input).getPath();
            case 3: return path(box.stored());
            case 4: return path(box.echo(file));
            case 5: return path(new DerivedFileBox(file).stored());
            case 6: return path(new InheritedFileBox(file).echo(file));
            case 7: return fromSource(box, input);
            case 8: return fromSource(new DerivedFileBox(file), input);
            case 9: return path(new DerivedFileBox(file).viaProtected(file));
            case 10: {
                File[] values = {file, null};
                File[] alias = box.rememberArray(values);
                alias[1] = alias[0];
                return path(values[1]);
            }
            case 11: {
                List<File> values = new ArrayList<>();
                values.add(null);
                List<File> alias = box.remember(values);
                alias.set(0, file);
                return path(values.get(0));
            }
            case 12: {
                FileSource callback = p -> nullable(p);
                return fromSource(callback, input);
            }
            case 13: {
                Function<File, File> callback = box::echo;
                return path(box.apply(callback, file));
            }
            case 14: {
                Function<FileBox, File> callback = FileBox::stored;
                return path(callback.apply(box));
            }
            case 15: return path(FileBox.generic(file, "extra"));
            case 16: return fromProtected(box, input);
            case 17: return fromCallback(f -> f, input);
            default: throw new IllegalArgumentException("Unknown route");
        }
    }
    public static int queries(String input) {
        FileBox box = new DerivedFileBox(nullable(input));
        File file = box.stored();
        if (file == null) return -1;
        return (file.exists() ? 1 : 0) + (file.isDirectory() ? 2 : 0)
            + (file.canRead() ? 4 : 0) + (file.canWrite() ? 8 : 0);
    }
}
