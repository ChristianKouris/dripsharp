// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0

// Focused destination compatibility for FontBox host font discovery.
#nullable enable

using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security;

namespace DripSharp.Runtime;

internal static class PdfCartonFontDiscovery
{
    internal static bool FileExists(JavaFile file)
    {
        if (!file.Queryable) return false;
        try
        {
            return File.Exists(file.Pathname) || Directory.Exists(file.Pathname);
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileIsDirectory(JavaFile file)
    {
        if (!file.Queryable) return false;
        try
        {
            return Directory.Exists(file.Pathname);
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileCanRead(JavaFile file)
    {
        if (!file.Queryable) return false;
        try
        {
            if (Directory.Exists(file.Pathname))
            {
                using var entries = Directory
                    .EnumerateFileSystemEntries(file.Pathname)
                    .GetEnumerator();
                _ = entries.MoveNext();
                return true;
            }

            if (!File.Exists(file.Pathname))
            {
                return false;
            }

            using var stream = File.Open(
                file.Pathname,
                FileMode.Open,
                FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete);
            return stream.CanRead;
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static bool FileIsHidden(JavaFile file)
    {
        if (!file.Queryable) return false;
        if (file.Name.StartsWith(".", StringComparison.Ordinal))
        {
            return true;
        }

        try
        {
            return FileExists(file) &&
                (File.GetAttributes(file.Pathname) & FileAttributes.Hidden) != 0;
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            return false;
        }
    }

    internal static JavaFile[]? FileListFiles(JavaFile directory) =>
        FileListFiles(directory, Directory.EnumerateFileSystemEntries);

    internal static Uri FileToUri(JavaFile file) =>
        JavaCompat.FileToUri(file);

    // Kept internal so package-only verification can exercise the inaccessible
    // directory path without publishing a filesystem abstraction.
    internal static JavaFile[]? FileListFiles(
        JavaFile directory,
        Func<string, IEnumerable<string>> enumerate)
    {
        if (!directory.Queryable) return null;
        try
        {
            if (!Directory.Exists(directory.Pathname))
            {
                return null;
            }

            return enumerate(directory.Pathname)
                .Select(path => new JavaFile(path))
                .ToArray();
        }
        catch (Exception error) when (IsInaccessible(error))
        {
            // java.io.File.listFiles() returns null when the directory cannot
            // be enumerated. FontFileFinder treats that as an unreadable path.
            return null;
        }
    }

    private static bool IsInaccessible(Exception error) =>
        error is UnauthorizedAccessException or IOException or SecurityException or ArgumentException or NotSupportedException;
}
