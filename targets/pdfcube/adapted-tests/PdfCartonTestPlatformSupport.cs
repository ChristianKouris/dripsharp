// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

namespace DripSharp.Runtime;

internal static class PdfCartonFontDiscovery
{
    internal static bool FileExists(JavaFile file) =>
        JavaCompat.FileExists(file);
}
