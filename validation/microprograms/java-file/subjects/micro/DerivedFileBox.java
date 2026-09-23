// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;
import java.io.File;
public class DerivedFileBox extends InheritedFileBox {
    public DerivedFileBox(File file) { super(file); }
    @Override public File get(String path) { return super.get(path); }
    @Override protected File protectedEcho(File file) { return super.protectedEcho(file); }
}
