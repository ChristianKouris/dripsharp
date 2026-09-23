// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;
import java.io.File;
public interface FileSource {
    File get(String path);
}
