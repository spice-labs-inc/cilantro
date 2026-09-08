GR_INTEGRATION (LLM copy) — contract facts for machine readers

This is the machine-oriented copy of GR_INTEGRATION.md (same content, LLM framing). All claims are pinned by cilantro tests: the walk's byte-faithfulness by AssemblyWalkerTests CP-2a (raw type-17 MPDB, length == in-file size) and the CP-2d sweeps; the post-walk refusal matrix by CP-2e; the callback-owned PDB readers by PdbSpoolTests CP-5a..f and PortablePdbFileTests A1-A4; hints by CP-7 (StreamingContractTests).


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

// Walk (ADR-0014 + 2026_09_04 byte-faithful amendment B-1..B-11) —
// the extraction contract. Entries only deliver bytes: no
// decompression, no envelope checks, no payload logic of any kind.
AssemblyWalker.withinAssemblyStream[T](artifact: File | Path)
    (f: Vector[AssemblyEntry] => T): Option[T]

sealed trait AssemblyEntry extends PayloadSource {
  def name: String               // pre-hardened (DotnetNameSanitizer)
  def kind: AssemblyEntryKind    // routing (carriers keep their facts)
  def mimeHint: Option[String]   // cilantro-owned (see table)
  def length: Long               // exact bytes delivered (B-4)
}
enum AssemblyEntryKind(val mimeHint: Option[String]) {
  Class, EmbeddedResource, AuthenticodeCertificate,
  Win32Resource, DebugBlob
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
// PDB reading is 100% separate from AssemblyEntry and from the
// Assembly (B-8): the decompressed portable PDB is self-contained —
// no back-references require an assembly. The readers are
// callback-owned and envelope-detecting (BSJB root or MPDB envelope):
PortablePdbFile.withPdb[T](file, spoolDir: Option[Path])
    (f: Try[Option[PDBView]] => T): Try[Option[T]]
MetadataReader.withEmbeddedPdb[T](spoolDir: Option[Path])
    (f: Try[Option[PDBView]] => T): Try[Option[T]]
// f ALWAYS runs with the outcome (Success(Some(view)) | Success(None)
// = not a portable PDB/absent | Failure = hostile). The view is live
// only inside f; cleanup (map release, cilantro scratch deletion; the
// caller's spool DIRECTORY and file are never touched) is automatic
// whether f returns or throws; the outer result passes f's value back
// (Failure only if f threw). PDBView has no public close().
PDBView.sources: Vector[EmbeddedSourceFile]
EmbeddedSourceFile extends PayloadSource      // (name, stream)
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
   debug blobs. There are NO embedded-source entries: the type-17
   payload is ONE byte-faithful DebugBlob entry whose bytes are the
   raw MPDB envelope (see the hint table) — decompression never
   happens in the walk (B-9/B-10, pinned: AssemblyWalkerTests CP-2a).
   Entry names are hardened but **not guaranteed unique** — key
   children by vector position, never by name (duplicate names are
   pinned: AssemblyWalkerTests CP-2e).
3. **Entry streams live only inside `f`.** Read payloads inside the
   callback (or inside `processStream`), like every other GR walk.
   After `withinAssemblyStream` returns, retained entries refuse
   cleanly (IOException). `f`'s exceptions propagate untouched.
4. **Every `processStream` call** hands out a fresh, independent
   `InputStream` at the payload start; cleanup is guaranteed when it
   exits. GR's `newWrapper` runs inside the callback.
5. **`length`** is the exact byte count of the stream (B-4): for
   file-backed entries the bytes as they exist in the artifact; for
   Class the canonical-JSON bytes (the single documented exception).
   It is stable and readable without reading the stream; it refuses
   after the walk, exactly like `processStream` (pinned: CP-2e).
5. **MIME hints (cilantro-owned, D-4)** — map them onto GR's MIME
   registry at wrapper creation; they are opaque keys with a
   change-versioning rule (changed hint strings break GR stamping):

   | Kind | hint |
   |---|---|
   | Class | `cilantro/type` |
   | EmbeddedResource | `None` (content detection) |
   | AuthenticodeCertificate | `None` (GR stamps from the entry's revision/type + its Certificates policy; cilantro exposes raw blobs only — no PKCS#7) |
   | Win32Resource | `pe/resource` |
   | DebugBlob (codeview/pdbchecksum/others) | `pe/debug` |
   | DebugBlob type 17 (compressed portable-PDB envelope) | `pe/debug; format=mpdb` — a claim about the DECLARED debug type, never a content-validity promise; re-validate before decompressing |

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
9. **Hostile PDBs never affect the walk (B-10):** a type-17 payload
   that would refuse decompression still walks `Some` with its raw
   entries; the refusal surfaces only when GR invokes a PDB reader.
   GR filters that previously treated walk-`None` as PDB hostility
   must migrate to the reader outcome.

## Spool plumbing (PDB reading only)

The walk takes NO spool directory and creates no temp state
(behavioral pin: PdbSpoolTests CP-5a). Decompression happens only
inside the callback-owned PDB readers. GR passes the **current
scope's temp directory** — the one `FileWalker.withinTempDir`
exposes — and creates a **uniquely named subdirectory per call**:

```scala
val spool = Files.createTempDirectory(scopeDir, "cilantro")  // never a fixed name
PortablePdbFile.withPdb(mpdbFile, Some(spool)) { outcome =>
  outcome match {
    case Success(Some(view)) => wrapSources(view.sources)   // inside f only
    case Success(None)       => ...                          // not a portable PDB
    case Failure(e)          => ...                          // hostile refusal
  }
}
```

Cilantro writes its root scratch (one `cilantro-pdb-*.bin` at a time)
inside that directory only while `f` runs, deletes only the files it
created, and **never creates, renames, or deletes the directory** —
GR's scope-exit `Helpers.deleteDirectory` removes the tree. For an
embedded PDB, GR spills the type-17 DebugBlob entry's bytes (its own
copy) and calls `withPdb`; if GR already parsed the assembly,
`MetadataReader.withEmbeddedPdb(Some(spool))` avoids the spill.
Sizing: the eager root decompression writes up to the declared
uncompressed size (< 2^31, i.e. at most ~2 GiB per assembly;
compression ~1000:1), so GR's per-scope temp-dir sizing / artifact
intake caps are the disk bound — the same exposure kind as GR's
archive extraction. Spool maps are released before the call returns,
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
3. PDB reader test: spill the type-17 entry, `withPdb(file,
   Some(spool))`, wrap sources inside `f`; scope-exit delete removes
   the tree after the call returns (the walk itself creates nothing).
4. The full GR suite: existing Dotnet tests plus an end-to-end graph
   test over a signed assembly with resources, win32 leaves, and an
   embedded PDB. Zero warnings; no null; no throw in tests.
