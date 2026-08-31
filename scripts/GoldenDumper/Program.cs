// GoldenDumper command-line entry point.
//
// Commands:
//   fetch  --seed packages.json --out corpus            download + verify + extract
//   dump   --manifest corpus/manifest.json --out DIR    regenerate golden dumps (cache, never committed)
//   extract --nupkg file.nupkg --out dir                 entry-safe extraction (C1-05 harness)
//   ilasm-compile --input x.il --output out/x.dll        wrap mono ilasm (deterministic)
//
// Ground-truth discipline (ADR-0006): corpus/manifest.json, corpus/fixtures/
// and corpus/golden/fixtures/ are COMMITTED. fetch never rewrites
// manifest.json (it diffs the would-be manifest against the committed one
// and fails loudly on mismatch), never compiles fixtures, and never writes
// goldens. dump writes only to its --out directory.
//
// Never-execute guarantee: no command loads or executes assembly code.

using System.Text.Json;

namespace GoldenDumper;

public static class Program
{
    public static int Main(string[] args)
    {
        if (args.Length == 0)
        {
            Console.Error.WriteLine("usage: GoldenDumper <fetch|dump|extract|verify|ilasm-compile|make-hostile|make-benign> [options]");
            return 2;
        }

        try
        {
            return args[0] switch
            {
                "fetch" => FetchCommand(args[1..]),
                "dump" => DumpCommand(args[1..]),
                "extract" => ExtractCommand(args[1..]),
                "ilasm-compile" => IlasmCompileCommand(args[1..]),
                "verify" => VerifyCommand(args[1..]),
                "make-hostile" => MakeHostileCommand(args[1..]),
                "probe" => ProbeCommand(args[1..]),
                "resources" => ResourcesCommand(args[1..]),
                "debug" => DebugCommand(args[1..]),
                "make-benign" => MakeBenignCommand(args[1..]),
                _ => Unknown(args[0]),
            };
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"error: {ex.Message}");
            return 1;
        }
    }

    private static int Unknown(string command)
    {
        Console.Error.WriteLine($"unknown command: {command}");
        return 2;
    }

    private static string Opt(string[] args, string name, string fallback = null)
    {
        for (int i = 0; i + 1 < args.Length; i++)
        {
            if (args[i] == name)
            {
                return args[i + 1];
            }
        }
        return fallback;
    }

    private static int FetchCommand(string[] args)
    {
        var seedPath = Opt(args, "--seed", "corpus/packages.json");
        var outDir = Opt(args, "--out", "corpus");
        var seedJson = JsonDocument.Parse(File.ReadAllText(seedPath));
        var root = seedJson.RootElement;
        var corruptSpecs = new List<(string Source, string Name, long Length)>();
        if (root.TryGetProperty("corruptFixtures", out var corruptEl))
        {
            foreach (var item in corruptEl.EnumerateArray())
            {
                corruptSpecs.Add((
                    item.GetProperty("source").GetString(),
                    item.GetProperty("name").GetString(),
                    item.GetProperty("lengthBytes").GetInt64()));
            }
        }

        var manifestPackages = new List<Dictionary<string, object>>();

        // Refresh discipline: when a manifest from a previous fetch exists,
        // every (id, version) that was recorded must hash identically or the
        // refresh fails (integrity control, per plan doc 01).
        var priorNupkgHashes = new Dictionary<string, string>();
        var priorManifestPath = Path.Combine(outDir, "manifest.json");
        if (File.Exists(priorManifestPath))
        {
            var prior = JsonDocument.Parse(File.ReadAllText(priorManifestPath));
            foreach (var pkg in prior.RootElement.GetProperty("packages").EnumerateArray())
            {
                var sha = pkg.TryGetProperty("nupkgSha256", out var el) ? el.GetString() : null;
                if (!string.IsNullOrEmpty(sha))
                {
                    priorNupkgHashes[$"{pkg.GetProperty("id").GetString()}@{pkg.GetProperty("version").GetString()}"] = sha;
                }
            }
        }

        using var http = new HttpClient();
        http.Timeout = TimeSpan.FromMinutes(10);

        var nupkgDir = Path.Combine(outDir, "nupkg");
        var binDir = Path.Combine(outDir, "bin");
        Directory.CreateDirectory(nupkgDir);
        Directory.CreateDirectory(binDir);

        foreach (var pkg in root.GetProperty("packages").EnumerateArray())
        {
            var id = pkg.GetProperty("id").GetString();
            var version = pkg.GetProperty("version").GetString();
            var tags = pkg.GetProperty("tags").EnumerateArray().Select(t => t.GetString()).ToList();
            var mixedMode = tags.Contains("mixedMode");
            var url = $"https://api.nuget.org/v3-flatcontainer/{id.ToLowerInvariant()}/{version.ToLowerInvariant()}/{id.ToLowerInvariant()}.{version.ToLowerInvariant()}.nupkg";

            var nupkgPath = Path.Combine(nupkgDir, $"{id}.{version}.nupkg");
            Console.WriteLine($"downloading {url}");
            using (var response = http.GetAsync(url).Result)
            {
                if (!response.IsSuccessStatusCode)
                {
                    throw new InvalidOperationException($"download failed for {id} {version}: HTTP {(int)response.StatusCode}");
                }
                // Atomic write: download to a temp name, then rename, so a
                // reader can never observe a torn nupkg.
                var tmp = nupkgPath + ".tmp";
                using (var stream = response.Content.ReadAsStream())
                using (var file = File.Create(tmp))
                {
                    stream.CopyTo(file);
                }
                File.Move(tmp, nupkgPath, overwrite: true);
            }
            var nupkgSha = Convert.ToHexString(Extraction.Sha256Of(nupkgPath)).ToLowerInvariant();
            if (priorNupkgHashes.TryGetValue($"{id}@{version}", out var priorSha) && priorSha != nupkgSha)
            {
                throw new InvalidOperationException(
                    $"nupkg sha256 mismatch for {id} {version}: downloaded {nupkgSha}, manifest records {priorSha} — refusing to refresh");
            }

            var pkgBin = Path.Combine(binDir, id, version);
            Extraction.Extract(nupkgPath, pkgBin);

            var assemblies = new List<Dictionary<string, object>>();
            foreach (var dll in Directory.EnumerateFiles(pkgBin, "*.dll", SearchOption.AllDirectories))
            {
                var relative = Path.GetRelativePath(pkgBin, dll).Replace('\\', '/');
                var sha = Convert.ToHexString(Extraction.Sha256Of(dll)).ToLowerInvariant();
                var size = new FileInfo(dll).Length;
                var tfm = relative.Contains('/') ? relative.Split('/')[0] : "lib";
                assemblies.Add(new Dictionary<string, object>
                {
                    ["path"] = $"bin/{id}/{version}/{relative}",
                    ["tfm"] = tfm,
                    ["sha256"] = sha,
                    ["sizeBytes"] = size,
                });
            }

            manifestPackages.Add(new Dictionary<string, object>
            {
                ["id"] = id,
                ["version"] = version,
                ["nupkgUrl"] = url,
                ["nupkgSha256"] = nupkgSha,
                ["tags"] = tags,
                ["mixedMode"] = mixedMode,
                ["assemblies"] = assemblies,
            });
        }

        // Corrupt/truncated fixtures: generated deterministically from the
        // corpus DLLs just downloaded (pinned sha256s in the manifest).
        if (corruptSpecs.Count > 0)
        {
            var corruptBin = Path.Combine(binDir, "corrupt");
            Directory.CreateDirectory(corruptBin);
            var corruptAssemblies = new List<Dictionary<string, object>>();
            foreach (var (source, name, length) in corruptSpecs)
            {
                var sourcePath = Path.Combine(binDir, source.Replace('/', Path.DirectorySeparatorChar));
                if (!File.Exists(sourcePath))
                {
                    throw new InvalidOperationException($"corrupt fixture source missing: {source}");
                }
                var bytes = File.ReadAllBytes(sourcePath);
                if (length > bytes.Length)
                {
                    throw new InvalidOperationException($"corrupt fixture length {length} exceeds source size {bytes.Length}");
                }
                var truncated = bytes.AsSpan(0, (int)length).ToArray();
                if (truncated.Length > 0)
                {
                    truncated[truncated.Length - 1] ^= 0x5A; // flip a byte for the corrupt signature
                }
                var dest = Path.Combine(corruptBin, name);
                File.WriteAllBytes(dest, truncated);
                var sha = Convert.ToHexString(Extraction.Sha256Of(dest)).ToLowerInvariant();
                corruptAssemblies.Add(new Dictionary<string, object>
                {
                    ["path"] = $"bin/corrupt/{name}",
                    ["tfm"] = "corrupt",
                    ["sha256"] = sha,
                    ["sizeBytes"] = truncated.Length,
                });
            }
            manifestPackages.Add(new Dictionary<string, object>
            {
                ["id"] = "cilantro.corrupt-fixtures",
                ["version"] = "1",
                ["nupkgUrl"] = "",
                ["nupkgSha256"] = "",
                ["tags"] = new List<string> { "corrupt" },
                ["mixedMode"] = false,
                ["assemblies"] = corruptAssemblies,
            });
        }

        // Ground-truth discipline (ADR-0006): manifest.json is COMMITTED.
        // fetch must never re-bless it: write the would-be manifest to a
        // temp location and require byte-identity with the committed one.
        // The only exception is a scratch outDir with no committed manifest.
        var manifest = new Dictionary<string, object>
        {
            ["schemaVersion"] = 1,
            ["generator"] = "scripts/GoldenDumper fetch",
            ["packages"] = manifestPackages,
        };
        var wouldBe = JsonSerializer.Serialize(manifest, new JsonSerializerOptions { WriteIndented = true });
        var manifestPath = Path.Combine(outDir, "manifest.json");
        if (File.Exists(manifestPath))
        {
            var committed = File.ReadAllText(manifestPath);
            if (!committed.Equals(wouldBe, StringComparison.Ordinal))
            {
                var tmp = Path.Combine(Path.GetTempPath(), "cilantro-would-be-manifest.json");
                File.WriteAllText(tmp, wouldBe);
                throw new InvalidOperationException(
                    $"refusing to rewrite committed manifest.json: the would-be manifest differs. " +
                    $"Compare {tmp} against {manifestPath} and reconcile packages.json deliberately. " +
                    "A fetch may not re-bless the integrity anchor (ADR-0006).");
            }
            Console.WriteLine($"manifest.json already committed and consistent (not rewritten)");
        }
        else
        {
            File.WriteAllText(manifestPath, wouldBe);
            Console.WriteLine($"wrote {manifestPath} (no committed manifest existed)");
        }
        return 0;
    }

    // Runs a GoldenDumper subcommand in-process and captures stdout.
    // (Kept for the fixture-regeneration workflow; fetch no longer uses it.)
    private static string CaptureCommand(params string[] args)
    {
        var originalOut = Console.Out;
        using var buffer = new System.IO.StringWriter();
        Console.SetOut(buffer);
        try
        {
            var exit = Main(args);
            if (exit != 0)
            {
                throw new InvalidOperationException($"subcommand failed ({exit}): {buffer}");
            }
        }
        finally
        {
            Console.SetOut(originalOut);
        }
        return buffer.ToString();
    }

    private static void BuildFixtureProject(string project, string builtName, string outputPath)
    {
        var psi = new System.Diagnostics.ProcessStartInfo
        {
            FileName = "dotnet",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("build");
        psi.ArgumentList.Add(project);
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add("Release");
        psi.ArgumentList.Add("-v");
        psi.ArgumentList.Add("quiet");
        using var process = System.Diagnostics.Process.Start(psi)!;
        var stdout = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"fixture build failed for {project}: {stdout}{stderr}");
        }
        var built = Path.Combine(Path.GetDirectoryName(project)!, "bin", "Release", "net8.0", builtName);
        Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
        File.Copy(built, outputPath, overwrite: true);
        Console.WriteLine($"{project} -> {outputPath}");
    }

    private static void BuildX64Fixture(string outputPath)
    {
        var psi = new System.Diagnostics.ProcessStartInfo
        {
            FileName = "dotnet",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("build");
        psi.ArgumentList.Add("scripts/fixtures/x64/X64Fixture.csproj");
        psi.ArgumentList.Add("-c");
        psi.ArgumentList.Add("Release");
        psi.ArgumentList.Add("-v");
        psi.ArgumentList.Add("quiet");
        using var process = System.Diagnostics.Process.Start(psi)!;
        var stdout = process.StandardOutput.ReadToEnd();
        var stderr = process.StandardError.ReadToEnd();
        process.WaitForExit();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"x64 fixture build failed: {stdout}{stderr}");
        }
        var built = Path.Combine("scripts", "fixtures", "x64", "bin", "Release", "net8.0", "X64Fixture.dll");
        Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
        File.Copy(built, outputPath, overwrite: true);
        Console.WriteLine($"x64 fixture -> {outputPath}");
    }

    private static int DumpCommand(string[] args)
    {
        var manifestPath = Opt(args, "--manifest", "corpus/manifest.json");
        var outDir = Opt(args, "--out", "corpus/golden");
        // Fixtures are COMMITTED ground truth (ADR-0006). By default dump
        // regenerates their goldens into outDir; pass --fixtures-dir to an
        // absent directory to dump assemblies only (the on-demand cache
        // regeneration path must never write corpus/golden/fixtures).
        var fixturesDirOpt = Opt(args, "--fixtures-dir", null);
        var doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
        int dumped = 0;
        foreach (var pkg in doc.RootElement.GetProperty("packages").EnumerateArray())
        {
            var mixedMode = pkg.TryGetProperty("mixedMode", out var mm) && mm.GetBoolean();
            var corrupt = pkg.TryGetProperty("tags", out var tagsEl)
                && tagsEl.EnumerateArray().Any(t => t.GetString() == "corrupt");
            if (corrupt)
            {
                Console.WriteLine($"skipping corrupt fixture package {pkg.GetProperty("id").GetString()} (no goldens by design)");
                continue;
            }
            foreach (var asm in pkg.GetProperty("assemblies").EnumerateArray())
            {
                var rel = asm.GetProperty("path").GetString();
                var asmPath = Path.Combine(Path.GetDirectoryName(manifestPath)!, rel.Replace('/', Path.DirectorySeparatorChar));
                var tier1 = Path.Combine(outDir, rel + ".tier1.json");
                var tier2 = Path.Combine(outDir, rel + ".tier2.json");
                Console.WriteLine($"dumping {rel}");
                GoldenWriter.Dump(new GoldenWriter.DumpOptions
                {
                    AssemblyPath = asmPath,
                    Tier1Path = tier1,
                    Tier2Path = tier2,
                    SkipBodies = mixedMode,
                    AssemblyLabel = rel,
                });
                dumped++;
            }
        }
        Console.WriteLine($"dumped {dumped} assemblies");

        // The hand-authored ilasm fixture is part of the oracle trust chain
        // (C1-03): dump it like any other assembly.
        var fixturesDir = fixturesDirOpt != null
            ? fixturesDirOpt
            : Path.Combine(Path.GetDirectoryName(manifestPath)!, "fixtures");
        if (Directory.Exists(fixturesDir))
        {
            foreach (var dll in Directory.EnumerateFiles(fixturesDir, "*.dll"))
            {
                var name = Path.GetFileNameWithoutExtension(dll);
                Console.WriteLine($"dumping fixture {name}");
                GoldenWriter.Dump(new GoldenWriter.DumpOptions
                {
                    AssemblyPath = dll,
                    Tier1Path = Path.Combine(outDir, "fixtures", name + ".tier1.json"),
                    Tier2Path = Path.Combine(outDir, "fixtures", name + ".tier2.json"),
                    SkipBodies = false,
                    AssemblyLabel = $"fixtures/{name}.dll",
                });
            }
        }
        return 0;
    }

    // resources: dump the module's manifest resources (name, type, size,
    // sha256) as JSON on stdout. The C5-02 oracle for embedded resources;
    // never executes assembly code.
    private static int ResourcesCommand(string[] args)
    {
        var file = Opt(args, "--file", null) ?? throw new ArgumentException("--file required");
        var readerParams = new Mono.Cecil.ReaderParameters
        {
            ReadSymbols = false,
            InMemory = false,
            AssemblyResolver = new GoldenWriter.NullResolver(),
        };
        using var assembly = Mono.Cecil.AssemblyDefinition.ReadAssembly(file, readerParams);
        using var stream = new System.IO.MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WritePropertyName("resources");
            writer.WriteStartArray();
            foreach (var resource in assembly.MainModule.Resources)
            {
                writer.WriteStartObject();
                writer.WriteString("name", resource.Name);
                writer.WriteString("resourceType", resource.ResourceType.ToString());
                var visibility = ((int)resource.Attributes & (int)Mono.Cecil.ManifestResourceAttributes.VisibilityMask) == (int)Mono.Cecil.ManifestResourceAttributes.Public ? "Public" : "Private";
                writer.WriteString("visibility", visibility);
                if (resource is Mono.Cecil.EmbeddedResource embedded)
                {
                    var data = embedded.GetResourceData();
                    writer.WriteNumber("size", data.Length);
                    writer.WriteString("sha256", Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(data)).ToLowerInvariant());
                }
                writer.WriteEndObject();
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
        }
        Console.WriteLine(System.Text.Encoding.UTF8.GetString(stream.ToArray()));
        return 0;
    }

    // debug: dump the debug-directory entries (type, size, sha256) and, for
    // the embedded portable PDB, the embedded source documents (name,
    // size, sha256) as JSON on stdout. The C5-05 oracle; never executes
    // assembly code.
    private static int DebugCommand(string[] args)
    {
        var file = Opt(args, "--file", null) ?? throw new ArgumentException("--file required");
        var readerParams = new Mono.Cecil.ReaderParameters
        {
            ReadSymbols = false,
            InMemory = false,
            AssemblyResolver = new GoldenWriter.NullResolver(),
        };
        using var assembly = Mono.Cecil.AssemblyDefinition.ReadAssembly(file, readerParams);
        var module = assembly.MainModule;
        using var stream = new System.IO.MemoryStream();
        using (var writer = new Utf8JsonWriter(stream))
        {
            writer.WriteStartObject();
            writer.WritePropertyName("entries");
            writer.WriteStartArray();
            try
            {
                var debugHeader = module.GetDebugHeader();
                foreach (var entry in debugHeader.Entries)
                {
                    writer.WriteStartObject();
                    writer.WriteNumber("type", (int)entry.Directory.Type);
                    writer.WriteNumber("size", entry.Data?.Length ?? 0);
                    if (entry.Data != null)
                    {
                        writer.WriteString("sha256", Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(entry.Data)).ToLowerInvariant());
                    }
                    writer.WriteEndObject();
                }
            }
            catch (Exception)
            {
                // No debug directory: entries stay empty.
            }
            writer.WriteEndArray();
            writer.WritePropertyName("sources");
            writer.WriteStartArray();
            try
            {
                // The default provider reads the embedded portable PDB;
                // every method's sequence points reference the documents.
                module.ReadSymbols();
                var seen = new System.Collections.Generic.HashSet<string>();
                void Walk(Mono.Cecil.TypeDefinition type)
                {
                    foreach (var method in type.Methods)
                    {
                        var info = method.DebugInformation;
                        if (info == null || !info.HasSequencePoints) continue;
                        foreach (var sp in info.SequencePoints)
                        {
                            var document = sp.Document;
                            if (document == null || document.EmbeddedSource == null) continue;
                            if (!seen.Add(document.Url)) continue;
                            writer.WriteStartObject();
                            writer.WriteString("name", document.Url);
                            writer.WriteNumber("size", document.EmbeddedSource.Length);
                            writer.WriteString("sha256", Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(document.EmbeddedSource)).ToLowerInvariant());
                            writer.WriteEndObject();
                        }
                    }
                    foreach (var nested in type.NestedTypes) Walk(nested);
                }
                foreach (var type in module.Types) Walk(type);
            }
            catch (Exception)
            {
                // No embedded PDB: sources stay empty.
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
        }
        Console.WriteLine(System.Text.Encoding.UTF8.GetString(stream.ToArray()));
        return 0;
    }

    private static int MakeHostileCommand(string[] args)
    {
        var output = Opt(args, "--output", null) ?? throw new ArgumentException("--output required");
        var vector = Opt(args, "--vector", null) ?? throw new ArgumentException("--vector required");
        HostileZip.Write(output, vector);
        Console.WriteLine($"wrote hostile nupkg {output} (vector={vector})");
        return 0;
    }

    // probe: load one assembly with the same reader parameters the golden
    // dumper uses (NullResolver, metadata-only) and report the verdict on
    // stdout: "ok <file>" or "fail <file>". Never executes assembly code;
    // used by C4-07 to compare mutation verdicts against the oracle.
    private static int ProbeCommand(string[] args)
    {
        var file = Opt(args, "--file", null) ?? throw new ArgumentException("--file required");
        try
        {
            var readerParams = new Mono.Cecil.ReaderParameters
            {
                ReadSymbols = false,
                InMemory = false,
                AssemblyResolver = new GoldenWriter.NullResolver(),
            };
            using var assembly = Mono.Cecil.AssemblyDefinition.ReadAssembly(file, readerParams);
            // Touch the model the way the dumper does: types, members,
            // and (cheaply) method bodies via RVA presence.
            var module = assembly.MainModule;
            foreach (var type in module.Types)
            {
                foreach (var field in type.Fields) { }
                foreach (var method in type.Methods)
                {
                    if (method.HasBody) { _ = method.Body.Instructions; }
                }
            }
            Console.WriteLine($"ok {file}");
            return 0;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"fail {file} ({ex.GetType().Name})");
            return 0;
        }
    }

    // verify: checks the corpus cache against the manifest and reports
    // every missing or mismatched file. Exit 0 = cache complete;
    // exit 1 = one or more problems (the fix step is a full fetch).
    private static int VerifyCommand(string[] args)
    {
        var manifestPath = Opt(args, "--manifest", "corpus/manifest.json");
        var outDir = Opt(args, "--out", "corpus");
        var doc = JsonDocument.Parse(File.ReadAllText(manifestPath));
        var problems = new List<string>();

        void CheckFile(string path, string expectedSha)
        {
            if (!File.Exists(path))
            {
                problems.Add($"missing: {path}");
                return;
            }
            if (!string.IsNullOrEmpty(expectedSha))
            {
                var actual = Convert.ToHexString(Extraction.Sha256Of(path)).ToLowerInvariant();
                if (actual != expectedSha)
                {
                    problems.Add($"sha256 mismatch: {path} (expected {expectedSha}, got {actual})");
                }
            }
        }

        foreach (var pkg in doc.RootElement.GetProperty("packages").EnumerateArray())
        {
            var mixedMode = pkg.TryGetProperty("mixedMode", out var mm) && mm.GetBoolean();
            var corrupt = pkg.TryGetProperty("tags", out var tagsEl)
                && tagsEl.EnumerateArray().Any(t => t.GetString() == "corrupt");
            var nupkgSha = pkg.TryGetProperty("nupkgSha256", out var nupkgEl) ? nupkgEl.GetString() : "";
            if (!string.IsNullOrEmpty(nupkgSha))
            {
                var nupkgPath = Path.Combine(outDir, "nupkg", $"{pkg.GetProperty("id").GetString()}.{pkg.GetProperty("version").GetString()}.nupkg");
                if (File.Exists(nupkgPath))
                {
                    CheckFile(nupkgPath, nupkgSha);
                }
            }
            foreach (var asm in pkg.GetProperty("assemblies").EnumerateArray())
            {
                var rel = asm.GetProperty("path").GetString();
                CheckFile(Path.Combine(outDir, rel), asm.GetProperty("sha256").GetString());
                if (!corrupt)
                {
                    var goldenDir = Path.Combine(outDir, "golden");
                    CheckFile(Path.Combine(goldenDir, rel + ".tier1.json"), "");
                    if (!mixedMode)
                    {
                        CheckFile(Path.Combine(goldenDir, rel + ".tier2.json"), "");
                    }
                }
            }
        }

        // The ilasm oracle fixture and its goldens are part of the trust
        // chain (C1-03); they must be present for the test suite.
        CheckFile(Path.Combine(outDir, "fixtures", "ilasm_fixture.dll"), "");
        CheckFile(Path.Combine(outDir, "golden", "fixtures", "ilasm_fixture.tier1.json"), "");
        CheckFile(Path.Combine(outDir, "golden", "fixtures", "ilasm_fixture.tier2.json"), "");

        if (problems.Count > 0)
        {
            Console.Error.WriteLine($"corpus cache incomplete ({problems.Count} problem(s)):");
            foreach (var problem in problems)
            {
                Console.Error.WriteLine($"  {problem}");
            }
            return 1;
        }
        Console.WriteLine("corpus cache verified");
        return 0;
    }

    private static int MakeBenignCommand(string[] args)
    {
        var output = Opt(args, "--output", null) ?? throw new ArgumentException("--output required");
        using (var archive = System.IO.Compression.ZipFile.Open(output, System.IO.Compression.ZipArchiveMode.Create))
        {
            void Add(string name, byte[] content)
            {
                var entry = archive.CreateEntry(name);
                using var stream = entry.Open();
                stream.Write(content);
            }
            Add("lib/net45/Good.dll", new byte[] { 0x4D, 0x5A, 0x90, 0x00 });
            Add("lib/net20/Good.dll", new byte[] { 0x4D, 0x5A, 0x90, 0x00 });
            Add("Good.nuspec", System.Text.Encoding.UTF8.GetBytes("<package />"));
        }
        Console.WriteLine($"wrote benign nupkg {output}");
        return 0;
    }

    private static int ExtractCommand(string[] args)
    {
        var nupkgPath = Opt(args, "--nupkg", null) ?? throw new ArgumentException("--nupkg required");
        var outDir = Opt(args, "--out", null) ?? throw new ArgumentException("--out required");
        Extraction.Extract(nupkgPath, outDir);
        return 0;
    }

    private static int IlasmCompileCommand(string[] args)
    {
        var input = Opt(args, "--input", null) ?? throw new ArgumentException("--input required");
        var output = Opt(args, "--output", null) ?? throw new ArgumentException("--output required");
        Directory.CreateDirectory(Path.GetDirectoryName(output)!);
        var psi = new System.Diagnostics.ProcessStartInfo
        {
            FileName = "ilasm",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        psi.ArgumentList.Add("-dll");
        psi.ArgumentList.Add(input);
        psi.ArgumentList.Add($"-output={output}");
        using var process = System.Diagnostics.Process.Start(psi)!;
        process.WaitForExit();
        if (process.ExitCode != 0)
        {
            throw new InvalidOperationException($"ilasm failed: {process.StandardError.ReadToEnd()}");
        }
        Console.WriteLine($"ilasm -> {output}");
        return 0;
    }
}
