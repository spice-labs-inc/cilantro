# GR_INTEGRATION — How to Wire the DLL Traversal Into Goat Rodeo

This guide describes, from the cilantro side, exactly how the Goat
Rodeo integrator wires the Phase 6 API surface into GR's walker once
cilantro is published to Maven Central. It modifies nothing in GR — it
is the contract hand-off document. Companion LLM copy:
GR_INTEGRATION_llm.md.

## The frozen API surface (cilantro 0.1.0+)

```
CanonicalJson.typeToJson(t: TypeDefinition): Try[String]            // class identity bytes
ModuleDefinition.resources: ArrayBuffer[Resource]                   // embedded/linked resources
EmbeddedResource.resourceData(): Try[Array[Byte]]                   // resource bytes
Image.securityDirectory: Option[DataDirectory]                      // Authenticode location
MetadataReader.readCertificateEntries(): ArrayBuffer[CertificateEntry]
MetadataReader.readWin32Resources(): ArrayBuffer[Win32Resource]
MetadataReader.readDebugEntryData(): ArrayBuffer[DebugEntryData]
MetadataReader.readEmbeddedPortablePdb(): Option[EmbeddedPdb]
```

All Option/Try, no null. The reader entry points are used through the
established pattern (`module.read(value, (_, reader) => reader.xxx)`),
the same pattern GR already uses via `AssemblyDefinition.readAssembly`.

## The one hook point

GR's container recursion lives in `ToProcess.process` — after an item
is written, `FileWalker.withinArchiveStream(artifact)` extracts a zip
container and feeds the children into `strategiesForArtifacts`. The
DLL traversal is a second container constructor beside it:
`FileWalker.withinDotnetAssemblyStream(artifact)` — mirroring
`asSaffronFilesystem` in shape (mount -> walk -> wrappers):

```
def withinDotnetAssemblyStream(artifact)(f: Vector[ArtifactWrapper] => T): Option[T]
```

The walker opens the assembly with the existing
`AssemblyDefinition.readAssembly` contract, then emits one
`ArtifactWrapper` per entry:

1. **Every class** (module.types + nestedTypes): bytes =
   `CanonicalJson.typeToJson(t).get`; name = the type's fullName.
2. **Every embedded resource**: bytes = `resourceData().get`; name =
   the resource name.
3. **Every certificate entry**: bytes = the raw blob; name =
   `certificate-<n>.pkcs7` (or similar).
4. **Every win32 resource leaf**: bytes = the blob; name =
   `<typeNameOrId>-<nameOrId>-<language>`.
5. **Every debug entry blob** and **every embedded source**: bytes =
   the blob / the source bytes; names = the entry type / the document
   URL.

Each wrapper carries a metadata map (attributes, base type, resource
flags, certificate revision/type) via the `ArtifactWrapper`
capabilities GR already uses.

## MIME names

- class entries: `cilantro/type` (new MIME, registered in GR's MIME
  map).
- resource bytes: the existing detection (the content is arbitrary —
  let the existing MIME pipeline classify it).
- certificate blobs: the existing certificate MIME — the existing
  `Certificates`/`CarvedCertificates` strategies pick them up with no
  changes.
- win32 leaves: `pe/resource` plus the type id in the metadata.
- embedded sources: text-family MIMEs (they are source files).

## Recursion and edges

The children become `containedBy` the assembly Item exactly as jar
entries do; the existing `hasBeenSeen` guard dedupes identical classes
across TFM variants for free (the canonical bytes are deterministic).

## GR-side test checklist (the integrator's plan)

1. Walker unit tests: each entry kind emits with the right bytes,
   name, and MIME.
2. A MIME matrix test: classes / resources / certificates / win32 /
   sources classify into the right strategies.
3. The full GR suite: the existing Dotnet tests plus an end-to-end
   graph test over a signed assembly with resources.
4. Zero warnings; no null; no throw in tests — the GR house rules.
