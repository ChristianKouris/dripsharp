// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;
import java.io.File;
import java.util.List;
import java.util.function.Function;

public class FileBox implements FileSource {
    private File stored;
    private List<File> files;
    private File[] array;
    public FileBox(File file) { stored = file; }
    public File get(String path) { return new File(path); }
    public File stored() { return stored; }
    public File echo(File file) { stored = file; return stored; }
    protected File protectedEcho(File file) { return file; }
    public File viaProtected(File file) { return protectedEcho(file); }
    public List<File> remember(List<File> values) { files = values; return files; }
    public void replaceList(String path) { files.set(0, new File(path)); }
    public String readList() { return files.get(0).getPath(); }
    public File[] rememberArray(File[] values) { array = values; return array; }
    public void replaceArray(String path) { array[0] = new File(path); }
    public String readArray() { return array[0].getPath(); }
    public File apply(Function<File, File> callback, File file) { return callback.apply(file); }
    public static <T> File generic(File file, T extra) { return file; }
}
