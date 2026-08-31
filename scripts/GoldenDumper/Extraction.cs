// Entry-safe zip extraction for the cilantro corpus.
//
// Why this exists:
//   The corpus contains third-party nupkgs. A hostile package is data, but
//   it must not escape the cache directory during extraction. This extracts
//   only lib/**/*.dll entries and rejects anything that could write outside
//   the destination (absolute paths, drive-relative paths, backslash paths,
//   ".." segments, symlink/hardlink entries, absurd sizes).
//
// Never-execute guarantee (see corpus plan doc 01): this code never loads
// or executes assembly code; it only reads bytes from the zip stream.

using System.IO.Compression;

namespace GoldenDumper;

public static class Extraction
{
    // Per-entry uncompressed size cap (bytes).
    public const long MaxEntrySize = 512L * 1024 * 1024;

    // Total extracted size cap (bytes).
    public const long MaxTotalSize = 2L * 1024 * 1024 * 1024;

    // Culture segment detector: satellite resource dirs look like "en",
    // "en-US", "zh-Hans" etc. directly under lib/<tfm>/.
    private static bool IsCultureSegment(string segment)
    {
        if (segment.Length < 2 || segment.Length > 10)
        {
            return false;
        }
        if (!char.IsAsciiLetterLower(segment[0]) || !char.IsAsciiLetterLower(segment[1]))
        {
            return false;
        }
        for (int i = 2; i < segment.Length; i++)
        {
            char c = segment[i];
            if (!(char.IsAsciiLetterLower(c) || char.IsAsciiLetterUpper(c) || c == '-'))
            {
                return false;
            }
        }
        return true;
    }

    // Native helper DLLs never produce useful parity data.
    private static readonly HashSet<string> NativeDllNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "SQLite.Interop.dll",
        "e_sqlite3.dll",
        "libSkiaSharp.dll",
        "libHarfBuzzSharp.dll",
        "libgdiplus.dll",
    };

    public sealed record ExtractionIssue(string EntryName, string Reason);

    public sealed record ExtractionResult(List<ExtractedFile> Files, List<ExtractionIssue> Issues);

    public sealed record ExtractedFile(string ZipEntryName, string RelativePath, long SizeBytes, byte[] Sha256);

    // Decides whether a zip entry name is a valid lib DLL we want to keep.
    // Returns null when the entry should be skipped for a benign reason
    // (not under lib/, satellite, native helper), or throws when the entry
    // is hostile.
    public static string ClassifyEntry(string entryName)
    {
        // Reject outright: backslash separators anywhere.
        if (entryName.Contains('\\'))
        {
            throw new InvalidDataException($"hostile entry (backslash path): {entryName}");
        }
        // Reject absolute / drive-relative paths.
        if (entryName.StartsWith('/') || (entryName.Length >= 2 && char.IsAsciiLetter(entryName[0]) && entryName[1] == ':'))
        {
            throw new InvalidDataException($"hostile entry (absolute path): {entryName}");
        }
        // Reject any ".." path segment.
        foreach (var segment in entryName.Split('/'))
        {
            if (segment == "..")
            {
                throw new InvalidDataException($"hostile entry (parent traversal): {entryName}");
            }
        }

        var segments = entryName.Split('/');
        // Only lib/<tfm-or-lib>/file.dll (or lib/file.dll).
        if (segments.Length < 2 || !segments[0].Equals("lib", StringComparison.OrdinalIgnoreCase))
        {
            return null; // benign skip: ref/, runtimes/, tools/, content/, ...
        }
        if (!entryName.EndsWith(".dll", StringComparison.OrdinalIgnoreCase))
        {
            return null; // benign skip: xml docs, pdb handled separately
        }

        // Satellite culture dirs: lib/<tfm>/<culture>/*.resources.dll
        if (segments.Length >= 4 && IsCultureSegment(segments[segments.Length - 2]))
        {
            return null;
        }
        if (NativeDllNames.Contains(segments[^1]))
        {
            return null;
        }

        // Relative target: <tfm>/<file> (drop the lib/ prefix).
        return string.Join('/', segments, 1, segments.Length - 1);
    }

    public static void Extract(string nupkgPath, string outDir)
    {
        var files = new List<ExtractedFile>();
        var issues = new List<ExtractionIssue>();
        long total = 0;

        Directory.CreateDirectory(outDir);
        using var archive = ZipFile.OpenRead(nupkgPath);

        foreach (var entry in archive.Entries)
        {
            string relative;
            try
            {
                relative = ClassifyEntry(entry.FullName);
            }
            catch (InvalidDataException ex)
            {
                throw new InvalidDataException(
                    $"refusing to extract {Path.GetFileName(nupkgPath)}: {ex.Message}");
            }
            if (relative == null)
            {
                continue;
            }

            // Symlink / device / fifo entries: Unix zip writers store the
            // file mode in the HIGH 16 bits of the external attributes.
            // Type bits: 0x8000 regular (benign), 0x4000 directory,
            // 0xA000 symlink, 0x6000 block device, 0x2000 char device,
            // 0x1000 fifo. Hardlinks have no distinct mode; they appear as
            // regular files with link counts, which the plan treats as data.
            uint mode = (((uint)entry.ExternalAttributes) >> 16) & 0xF000;
            if (mode == 0xA000 || mode == 0x6000 || mode == 0x2000 || mode == 0x1000)
            {
                throw new InvalidDataException(
                    $"refusing to extract {Path.GetFileName(nupkgPath)}: hostile entry (link/device): {entry.FullName}");
            }

            if (entry.Length > MaxEntrySize)
            {
                throw new InvalidDataException(
                    $"refusing to extract {Path.GetFileName(nupkgPath)}: entry too large ({entry.Length} > {MaxEntrySize}): {entry.FullName}");
            }
            total += entry.Length;
            if (total > MaxTotalSize)
            {
                throw new InvalidDataException(
                    $"refusing to extract {Path.GetFileName(nupkgPath)}: total size exceeds {MaxTotalSize} bytes");
            }

            var dest = Path.Combine(outDir, relative.Replace('/', Path.DirectorySeparatorChar));
            var destDir = Path.GetDirectoryName(dest);
            // Refuse to write through a pre-existing symlink anywhere in the
            // destination chain: File.Create follows symlinks and would
            // truncate/overwrite the link target (e.g. a git-tracked symlink
            // planted in the cache tree).
            for (var current = destDir; current != null && current.Length >= outDir.Length; current = Path.GetDirectoryName(current))
            {
                if (Directory.Exists(current) && new DirectoryInfo(current).LinkTarget != null)
                {
                    throw new InvalidDataException(
                        $"refusing to extract {Path.GetFileName(nupkgPath)}: destination directory is a symlink: {current}");
                }
            }
            if (File.Exists(dest) && new FileInfo(dest).LinkTarget != null)
            {
                throw new InvalidDataException(
                    $"refusing to extract {Path.GetFileName(nupkgPath)}: destination is a symlink: {dest}");
            }
            Directory.CreateDirectory(destDir!);
            using (var src = entry.Open())
            using (var dst = File.Create(dest))
            {
                src.CopyTo(dst);
            }
            files.Add(new ExtractedFile(
                entry.FullName,
                relative,
                entry.Length,
                Sha256Of(dest)));
        }

        Console.WriteLine($"extracted {files.Count} DLL(s) from {Path.GetFileName(nupkgPath)}");
        foreach (var f in files)
        {
            Console.WriteLine($"  {f.RelativePath} ({f.SizeBytes} bytes, sha256={Convert.ToHexString(f.Sha256).ToLowerInvariant()})");
        }
    }

    public static byte[] Sha256Of(string path)
    {
        using var sha = System.Security.Cryptography.SHA256.Create();
        using var stream = File.OpenRead(path);
        return sha.ComputeHash(stream);
    }
}
