# GR_INTEGRATION (LLM copy)

Machine-oriented integration facts. Human narrative: GR_INTEGRATION.md.

## Facts the integrator needs

- GR recursion hook: `ToProcess.process` (archive walk at
  `FileWalker.withinArchiveStream`); the DLL walker is a second
  constructor — mirror `FileWalker.asSaffronFilesystem` (detector ->
  mount -> walk -> `ArtifactWrapper.newWrapper(name, size, stream,
  tempPath)`).
- The cilantro accessors (all Option/Try):
  `CanonicalJson.typeToJson`, `ModuleDefinition.resources`,
  `EmbeddedResource.resourceData`, `Image.securityDirectory`,
  `MetadataReader.readCertificateEntries`,
  `readWin32Resources`, `readDebugEntryData`,
  `readEmbeddedPortablePdb`. The reader entry points run through
  `module.read(value, (_, reader) => reader.xxx)`.
- Entry kinds -> bytes -> MIME:
  class = `typeToJson` bytes / `cilantro/type`;
  embedded resource = `resourceData` / existing content MIMEs;
  certificate = `CertificateEntry.blob` / the existing cert MIME;
  win32 leaf = `Win32Resource.blob` / `pe/resource` + type-id metadata;
  debug blob = `DebugEntryData.blob` / `pe/debug` + the type;
  embedded source = `EmbeddedSourceFile.bytes` / text-family MIMEs.
- Edges: children `containedBy` the assembly Item; the canonical
  bytes make the dedupe (identical classes across TFMs) automatic.
- Determinism: the canonical bytes are golden-pinned (C4-01 + the
  C5-01 slice tests), so the GitOIDs are stable.

## GR-side checklist

- Walker unit tests per entry kind (bytes, name, MIME).
- MIME matrix test (classes/resources/certs/win32/sources route to the
  right strategies).
- End-to-end graph test over a signed resource-bearing assembly.
- GR house rules: zero warnings, no null, no throw in tests.
