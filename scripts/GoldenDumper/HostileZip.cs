// Builds a synthetic hostile nupkg for C1-05. The zip is written by hand
// (stored entries, fixed CRC) because ZipArchive refuses or silently
// normalizes the hostile entry names we must pin:
//   - ../evil.txt          (parent traversal)
//   - /etc/evil.txt        (absolute path)
//   - a\b.txt              (backslash separator)
//   - link.txt             (unix symlink mode bits 0xA1FF)
//   - lib/net45/Good.dll   (benign, must never be the reason to fail)

namespace GoldenDumper;

public static class HostileZip
{
    private static readonly uint[] CrcTable = BuildCrcTable();

    private static uint[] BuildCrcTable()
    {
        var table = new uint[256];
        for (uint i = 0; i < 256; i++)
        {
            uint c = i;
            for (int k = 0; k < 8; k++)
            {
                c = (c & 1) != 0 ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            }
            table[i] = c;
        }
        return table;
    }

    private static uint Crc32(byte[] data)
    {
        uint crc = 0xFFFFFFFF;
        foreach (var b in data)
        {
            crc = CrcTable[(crc ^ b) & 0xFF] ^ (crc >> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }

    private static void WriteU16(Stream s, ushort v)
    {
        s.WriteByte((byte)(v & 0xFF));
        s.WriteByte((byte)(v >> 8));
    }

    private static void WriteU32(Stream s, uint v)
    {
        s.WriteByte((byte)(v & 0xFF));
        s.WriteByte((byte)((v >> 8) & 0xFF));
        s.WriteByte((byte)((v >> 16) & 0xFF));
        s.WriteByte((byte)((v >> 24) & 0xFF));
    }

    private static void WriteEntry(Stream s, string name, byte[] content, uint externalAttributes, List<(string, uint, uint, uint, uint)> directory)
    {
        var nameBytes = System.Text.Encoding.UTF8.GetBytes(name);
        uint crc = Crc32(content);
        uint offset = (uint)s.Position;

        // local file header
        WriteU32(s, 0x04034b50);
        WriteU16(s, 20);                 // version needed
        WriteU16(s, 0);                  // flags
        WriteU16(s, 0);                  // method: stored
        WriteU16(s, 0);                  // time
        WriteU16(s, 0);                  // date
        WriteU32(s, crc);
        WriteU32(s, (uint)content.Length);
        WriteU32(s, (uint)content.Length);
        WriteU16(s, (ushort)nameBytes.Length);
        WriteU16(s, 0);                  // extra len
        s.Write(nameBytes);
        s.Write(content);

        directory.Add((name, crc, (uint)content.Length, externalAttributes, offset));
    }

    // Writes a hostile nupkg carrying exactly one attack vector (plus a
    // benign DLL) so every rejection path is exercised independently.
    public static void Write(string outputPath, string vector)
    {
        using var s = File.Create(outputPath);
        var directory = new List<(string Name, uint Crc, uint Size, uint ExternalAttributes, uint Offset)>();

        switch (vector)
        {
            case "traversal":
                WriteEntry(s, "../evil.txt", System.Text.Encoding.UTF8.GetBytes("evil parent traversal"), 0, directory);
                break;
            case "absolute":
                WriteEntry(s, "/etc/evil.txt", System.Text.Encoding.UTF8.GetBytes("evil absolute"), 0, directory);
                break;
            case "backslash":
                WriteEntry(s, "a\\b.txt", System.Text.Encoding.UTF8.GetBytes("evil backslash"), 0, directory);
                break;
            case "symlink":
                WriteEntry(s, "lib/net45/link.dll", System.Text.Encoding.UTF8.GetBytes("target"), 0xA1FF0000u, directory); // symlink
                break;
            default:
                throw new ArgumentException($"unknown hostile vector: {vector}");
        }
        WriteEntry(s, "lib/net45/Good.dll", new byte[] { 0x4D, 0x5A, 0x90, 0x00 }, 0, directory);

        uint cdOffset = (uint)s.Position;
        foreach (var (name, crc, size, attrs, offset) in directory)
        {
            var nameBytes = System.Text.Encoding.UTF8.GetBytes(name);
            WriteU32(s, 0x02014b50);
            WriteU16(s, 20);                 // version made by
            WriteU16(s, 20);                 // version needed
            WriteU16(s, 0);                  // flags
            WriteU16(s, 0);                  // method
            WriteU16(s, 0);                  // time
            WriteU16(s, 0);                  // date
            WriteU32(s, crc);
            WriteU32(s, size);
            WriteU32(s, size);
            WriteU16(s, (ushort)nameBytes.Length);
            WriteU16(s, 0);                  // extra len
            WriteU16(s, 0);                  // comment len
            WriteU16(s, 0);                  // disk number
            WriteU16(s, 0);                  // internal attrs
            WriteU32(s, attrs);              // external attrs (unix mode high bits)
            WriteU32(s, offset);
            s.Write(nameBytes);
        }

        uint cdSize = (uint)s.Position - cdOffset;
        WriteU32(s, 0x06054b50);
        WriteU16(s, 0);                      // disk
        WriteU16(s, 0);                      // cd disk
        WriteU16(s, (ushort)directory.Count);
        WriteU16(s, (ushort)directory.Count);
        WriteU32(s, cdSize);
        WriteU32(s, cdOffset);
        WriteU16(s, 0);                      // comment len
    }
}
