# GR_INTEGRATION — How to Wire the Streaming DLL Traversal Into Goat Rodeo

This guide describes, from the cilantro side, exactly how the Goat
Rodeo integrator wires the streaming .NET traversal into GR's walker
once cilantro is published to Maven Central. It modifies nothing in
GR — it is the contract hand-off document (plan
`2026_09_02_cilantro_streaming_assembly_walk`, ADR-0014). Companion
LLM copy: GR_INTEGRATION_llm.md.

## The handoff in one paragraph

A .NET assembly is an ordinary container: **probe** it cheaply
(`DotnetAssemblyProbe`), then **walk** it with
`AssemblyWalker.withinAssemblyStream`, which hands GR one complete
`Vector[AssemblyEntry]` — each entry a name, a kind, a MIME hint, and
a payload delivered through `processStream` — or `None`. Corrupt and
hostile assemblies yield `None` (no children), never a partial
vector, never an exception out of the walk. GR wraps each entry's
stream under its own emission policy exactly as it wraps archive
entries.

## The frozen API surface (cilantro 0.3.0+)

```
// Probe (ADR-0012) — the classifier; never parses payloads.
DotnetAssemblyProbe.isDotnetAssembly(InputStream | File | Path | String): Boolean
DotnetAssemblyProbe.dotnetHeader(...): Option[DotnetAssemblyHeader]

// Walk (ADR-0014, D-1/D-2/D-8) — the extraction contract.
AssemblyWalker.withinAssemblyStream[T](artifact: File | Path)
    (f: Vector[AssemblyEntry] => T)
    (spoolRoot: Option[Path]): Option[T]

sealed trait AssemblyEntry extends PayloadSource {
  def name: String               // pre-hardened (DotnetNameSanitizer)
  def kind: AssemblyEntryKind
  def mimeHint: Option[String]   // cilantro-owned (see table)
}
enum AssemblyEntryKind(val mimeHint: Option[String]) {
  Class, EmbeddedResource, AuthenticodeCertificate,
  Win32Resource, DebugBlob, EmbeddedSource
}
trait PayloadSource {
  def processStream[T](f: java.io.InputStream => T): T
}
// Per-kind carriers keep their metadata on the entry:
//   ClassEntry.type (TypeDefinition); AuthenticodeCertificateEntry
//   .certificateRevision/.certificateType; Win32ResourceEntry
//   .resourceTypeId/.resourceNameId/.resourceLanguage;
//   DebugBlobEntry.debugEntryTypeValue.

DotnetNameSanitizer.sanitize(name: String): String   // D-5

// Replaced payload accessors (D-3): CertificateEntry / Win32Resource /
// DebugEntryData are PayloadSources (stream views, never whole
// arrays). EmbeddedResource.resourceData(): Try[Array[Byte]] remains
// for convenience (spec 2.3).
// Embedded portable PDB (D-6/D-12/D-13):
MetadataReader.readEmbeddedPortablePdb(spoolDir: Option[Path]): Try[Option[PDBView]]
PDBView.sources: Vector[EmbeddedSourceFile]   // close() releases + deletes the scratch
EmbeddedSourceFile extends PayloadSource      // (name, stream)
// Standalone portable PDB (2026-09-04): PDB bytes on their own — a
// .pdb file next to an assembly. BSJB-gated; no DLL required; the
// file is mapped directly (no decompression, no spool, never
// modified or deleted by cilantro).
PortablePdbFile.open(file | path): Try[Option[PDBView]]   // None = not a portable PDB
PortablePdbFile.isPortablePdb(file | path | name): Boolean
```

## The walk contract (what GR can rely on)

1. **`None`** means: the artifact is not readable as a .NET assembly
   (no MZ, probe-false, model read failure), OR any part of the
   enumeration refused (hostile declarations, canonical-JSON failures
   over 1 MiB, nested-type cycles, hostile sections, PDB spool
   refusals). Corrupt assemblies yield **no children** — the ADR-0012
   posture. There is no partial vector and no truncation flag: a
   vector that comes back is complete with respect to the
   configuration.
2. **`Some(entries)`**: `f` was called exactly once, with the vector
   in the pinned order: classes (module table order, nested
   depth-first), embedded resources, certificates, win32 leaves,
   debug blobs, then embedded sources (only when `spoolRoot` is
   provided and the type-17 payload is a genuine MPDB). Entry names
   are hardened but **not guaranteed unique** — key children by
   vector position, never by name (duplicate names are pinned:
   AssemblyWalkerTests CP-2e).
3. **Entry streams live only inside `f`.** Read payloads inside the
   callback (or inside `processStream`), like every other GR walk.
   After `withinAssemblyStream` returns, retained entries refuse
   cleanly (IOException). `f`'s exceptions propagate untouched.
4. **Every `processStream` call** hands out a fresh, independent
   `InputStream` at the payload start; cleanup is guaranteed when it
   exits. GR's `newWrapper` runs inside the callback.
5. **MIME hints (cilantro-owned, D-4)** — map them onto GR's MIME
   registry at wrapper creation; they are opaque keys with a
   change-versioning rule (changed hint strings break GR stamping):

   | Kind | hint |
   |---|---|
   | Class | `cilantro/type` |
   | EmbeddedResource | `None` (content detection) |
   | AuthenticodeCertificate | `None` (GR stamps from the entry's revision/type + its Certificates policy; cilantro exposes raw blobs only — no PKCS#7) |
   | Win32Resource | `pe/resource` |
   | DebugBlob | `pe/debug` |
   | EmbeddedSource | `None` (content detection) |

6. **Payloads are zero-copy mmap slices.** There are no size-limit
   constants on payload exposure: the only payload rule is that a
   declared length fits the file (extent); refusals are clean. Object
   guards: 1,000,000 per certificate/debug/win32-directory entries,
   1,000,000 win32 total leaves, 1,000,000 PDB sources, class
   canonical JSON ≤ 1 MiB, PDB root declared size < 2^31.
7. **Embedded sources** are lazy (D-13): a deflate-format source is
   inflated on demand inside `processStream` — GR's per-child caps
   bound the reads, and a consumer that stops reading stops the work.
   A source-level inflate failure surfaces as an `IOException` from
   the stream inside `f` (a child-level failure), NOT a walk `None`.
8. **The artifact must be immutable for the walk's duration** (a
   concurrent truncation under a live map can crash the JVM). GR
   should walk wrapper-owned copies for attacker-mutable paths.

## Spool plumbing (embedded PDBs)

GR passes the **current scope's temp directory** — the one
`FileWalker.withinTempDir` exposes — and creates a **uniquely named
subdirectory per call**:

```scala
val spool = Files.createTempDirectory(scopeDir, "cilantro")  // never a fixed name
withinAssemblyStream(artifact)(wrap)(Some(spool))
```

Cilantro writes its root scratch (one `cilantro-pdb-*.bin` at a time)
inside that directory, deletes only the files it created (at walk
end / on refusal), and **never creates, renames, or deletes the
directory** — GR's scope-exit `Helpers.deleteDirectory` removes the
tree. Sizing: the eager root decompression writes up to the declared
uncompressed size (< 2^31, i.e. at most ~2 GiB per assembly;
compression ~1000:1), so GR's per-scope temp-dir sizing / artifact
intake caps are the disk bound — the same exposure kind as GR's
archive extraction. Spool maps are released before the walk returns,
so the scope-exit delete succeeds (Windows note: delete after
cilantro returns).

## GR-side deletions (execute in the goatrodeo repository)

- `PeHeaderProbe.hasDotnetMetadata` → replaced by
  `DotnetAssemblyProbe` (the probe was delivered in cilantro 0.2.1+;
  GR's `DotnetDetector` should call it first).
- `DotnetNameSanitizer` → deleted; cilantro hardens at the source.
  GR keeps only its artifact-level name policy.
- The whole-blob walk → replaced by `withinAssemblyStream`.

## Recursion and edges

Children become `containedBy` the assembly Item exactly as archive
entries do; content dedupe (`hasBeenSeen`) keeps working because the
class canonical bytes are deterministic. An embedded-resource DLL is
itself an entry; GR recurses by extracting it to a wrapper-owned file
and probing/walking that file (cilantro never reads nested
containers). A corrupt nested assembly yields `None` from its own
walk — GR's child handling applies.

## GR-side test checklist (the integrator's plan)

1. Probe-first routing: `DotnetAssemblyProbe` gates the .NET
   strategy; non-.NET artifacts never reach the walk.
2. Walker unit tests: `withinAssemblyStream` per kind — bytes via
   `processStream`, hardened names, hints; corrupt assemblies yield
   `None` with no children.
3. Spool plumbing test: `Files.createTempDirectory(scopeDir,
   "cilantro")` passed as `spoolRoot`; scope-exit delete removes the
   tree after the walk returns.
4. The full GR suite: existing Dotnet tests plus an end-to-end graph
   test over a signed assembly with resources, win32 leaves, and an
   embedded PDB. Zero warnings; no null; no throw in tests.
