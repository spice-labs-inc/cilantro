// AssemblyWalker — the container walk (plan 2026_09_02, phase C;
// ADR-0014, D-1/D-2/D-4/D-5/D-8/D-11).
//
// withinAssemblyStream[T](artifact)(f)(spoolRoot) hands Goat Rodeo
// one complete Vector[AssemblyEntry] or None:
//
//   - None: the artifact is not readable as a .NET assembly (no MZ,
//     probe-false, open/model-read failure) OR any part of the
//     enumeration refused (a hostile declaration, a canonical-JSON
//     failure or > 1 MiB class JSON, a nested-type cycle, a hostile
//     certificate/win32/resource section). The vector is
//     all-or-nothing: f is never called with a partial vector.
//   - f is called exactly once with the full vector in the pinned
//     deterministic order: classes (module types in table order,
//     nested depth-first, pre-order), embedded resources, then
//     certificate entries, win32 leaves, debug blobs (embedded
//     sources arrive in phase D when spoolRoot is provided).
//   - The walk opens the artifact with the existing full model read
//     (readAssembly; symbol reading off), so the golden-pinned
//     reader semantics apply unchanged. The probe remains GR's
//     cheap classifier; the walk governs extraction.
//   - f's exceptions propagate untouched (they are the caller's).
//     Entry streams are valid only inside the f call: after the walk
//     returns, retained entries refuse cleanly (IOException).
//
// The walk never loops: class enumeration keeps an ancestor RID set
// and a nested-type cycle is a clean refusal (DataFormatException),
// matching the win32 visited-set precedent. Duplicates are not
// deduplicated: attribute-top-level membership and NestedClass-table
// children can overlap legitimately (a type may appear twice) — the
// enumeration is the contract, pinned by CP-2b. NestedClass-table
// entries pointing at missing TypeDef rows are skipped by the model
// (Option semantics), documented.

package io.spicelabs.cilantro

import java.io.File
import java.nio.file.Path
import java.util.zip.DataFormatException
import scala.collection.mutable
import scala.util.{Failure, Success, Try}

object AssemblyWalker {

    // D-11: the walk materializes each class's canonical JSON only up
    // to this ceiling; a class whose canonical bytes exceed it is a
    // clean refusal (hostile-scale derived text).
    private val maxClassCanonicalBytes: Int = 1024 * 1024

    def withinAssemblyStream[T](artifact: File)(f: Vector[AssemblyEntry] => T)(spoolRoot: Option[Path]): Option[T] =
        withinAssemblyStream(artifact.toPath)(f)(spoolRoot)

    def withinAssemblyStream[T](artifact: Path)(f: Vector[AssemblyEntry] => T)(spoolRoot: Option[Path]): Option[T] = {
        // spoolRoot validation happens here so a bad spool directory
        // refuses before any work: it must be an existing directory
        // (CP-5a). When provided, the type-17 embedded PDB is spooled
        // into it and its sources become EmbeddedSource entries (the
        // spooled view is closed after f; the DIRECTORY is the
        // caller's — never touched by cilantro).
        if (spoolRoot.exists(p => !java.nio.file.Files.isDirectory(p))) {
            return None
        }
        AssemblyDefinition.readAssembly(artifact.toString) match {
            case Failure(_) => None
            case Success(assembly) =>
                assembly.mainModule match {
                    case None =>
                        assembly.close()
                        None
                    case Some(module) =>
                        val session = new AssemblyWalkSession()
                        val enumerated = Try {
                            module.reader match {
                                case None => throw DataFormatException()
                                case Some(reader) => AssemblyEnumerator.enumerate(module, reader, session, spoolRoot)
                            }
                        }
                        enumerated match {
                            case Failure(_) =>
                                session.close()
                                assembly.close()
                                None
                            case Success((entries, views)) =>
                                try {
                                    val result = f(entries)
                                    Some(result)
                                } finally {
                                    session.close()
                                    views.foreach(_.close())
                                    assembly.close()
                                }
                        }
                }
        }
    }

    private object AssemblyEnumerator {

        private final case class Frame(td: TypeDefinition, children: Vector[TypeDefinition], var next: Int)

        private def rid(t: TypeDefinition): Int =
            t.token.map(_.RID).getOrElse(System.identityHashCode(t))

        def enumerate(
            module: ModuleDefinition,
            reader: MetadataReader,
            session: AssemblyWalkSession,
            spoolRoot: Option[Path]
        ): (Vector[AssemblyEntry], Vector[PDBView]) = {
            val builder = Vector.newBuilder[AssemblyEntry]
            val views = Vector.newBuilder[PDBView]
            enumerateClasses(module, session, builder)
            enumerateResources(module, reader, session, builder)
            enumerateCertificates(reader, session, builder)
            enumerateWin32(reader, session, builder)
            enumerateDebugBlobs(reader, session, builder)
            enumerateEmbeddedSources(reader, session, spoolRoot, builder, views)
            (builder.result(), views.result())
        }

        // Classes: module.types (attribute-top-level, table order),
        // each followed by its nested types depth-first (pre-order),
        // iteratively with an ancestor RID set — a cycle is a clean
        // refusal, never an infinite loop (CP-2c/CP-2d).
        private def enumerateClasses(
            module: ModuleDefinition,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            module.types.foreach(t => enumerateClassTree(t, session, builder))
        }

        private def enumerateClassTree(
            root: TypeDefinition,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            val ancestors = mutable.HashSet[Int]()
            val frames = mutable.ArrayBuffer[Frame]()
            def push(td: TypeDefinition): Unit = {
                emitClass(td, session, builder)
                ancestors.add(rid(td))
                frames.append(Frame(td, td.nestedTypes.toVector, 0))
            }
            push(root)
            while (frames.nonEmpty) {
                val frame = frames(frames.length - 1)
                if (frame.next < frame.children.length) {
                    val child = frame.children(frame.next)
                    frame.next += 1
                    val childRid = rid(child)
                    if (ancestors.contains(childRid)) {
                        throw DataFormatException()
                    }
                    push(child)
                }
                else {
                    frames.remove(frames.length - 1)
                    ancestors.remove(rid(frame.td))
                }
            }
        }

        private def emitClass(
            td: TypeDefinition,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            val json = dump.CanonicalJson.typeToJson(td) match {
                case Success(text) => text
                case Failure(_) => throw DataFormatException()
            }
            val bytes = json.getBytes("UTF-8")
            if (bytes.length > maxClassCanonicalBytes) {
                throw DataFormatException()
            }
            builder += new ClassEntry(DotnetNameSanitizer.sanitize(td.fullName), td, bytes, session)
        }

        // Embedded resources only: linked / assembly-linked resources
        // are references, not payload entries (documented).
        private def enumerateResources(
            module: ModuleDefinition,
            reader: MetadataReader,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            module.resources.foreach {
                case r: EmbeddedResource =>
                    r.resourceOffset match {
                        case Some(offset) =>
                            builder += new EmbeddedResourceEntry(
                                DotnetNameSanitizer.sanitize(r.name),
                                reader.managedResourcePayload(offset),
                                session
                            )
                        case None => throw DataFormatException()
                    }
                case _ => ()
            }
        }

        private def enumerateCertificates(
            reader: MetadataReader,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            var i = 0
            reader.readCertificateEntries().foreach { cert =>
                builder += new AuthenticodeCertificateEntry(
                    DotnetNameSanitizer.sanitize("certificate-" + i),
                    cert,
                    session
                )
                i += 1
            }
        }

        private def enumerateWin32(
            reader: MetadataReader,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            reader.readWin32Resources().foreach { r =>
                val rawName = r.typeNameOrId + "-" + r.nameOrId + "-" + r.language
                builder += new Win32ResourceEntry(DotnetNameSanitizer.sanitize(rawName), r, session)
            }
        }

        private val debugTypeNames: Map[Int, String] = Map(
            2 -> "codeview",
            16 -> "deterministic",
            17 -> "embedded-portable-pdb",
            19 -> "pdbchecksum"
        )

        // Debug-directory data entries (types 2/16/17/19 — the header
        // parse refuses unknown types at the model read). Zero-size
        // entries are absent (readDebugEntryData's data filter).
        private def enumerateDebugBlobs(
            reader: MetadataReader,
            session: AssemblyWalkSession,
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]]
        ): Unit = {
            val occurrences = mutable.HashMap[Int, Int]()
            reader.readDebugEntryData().foreach { entry =>
                val base = debugTypeNames.getOrElse(entry.entryType, "debug-" + entry.entryType)
                val ordinal = occurrences.getOrElse(entry.entryType, 0)
                occurrences.update(entry.entryType, ordinal + 1)
                val name = if (ordinal == 0) base else base + "-" + ordinal
                builder += new DebugBlobEntry(DotnetNameSanitizer.sanitize(name), entry.entryType, entry, session)
            }
        }

        // Embedded portable PDB sources (phase D, D-6/D-13): only when
        // spoolRoot is provided AND the type-17 payload is a genuine
        // MPDB envelope (magic-valid). Absent or magic-invalid ->
        // DebugBlob only, no sources, no refusal. Any spool refusal
        // (hostile declaration, mismatch, IO) propagates -> the walk
        // returns None (all-or-nothing). Sources are appended after
        // the debug blobs, in PDB table order.
        private def enumerateEmbeddedSources(
            reader: MetadataReader,
            session: AssemblyWalkSession,
            spoolRoot: Option[Path],
            builder: mutable.Builder[AssemblyEntry, Vector[AssemblyEntry]],
            views: mutable.Builder[PDBView, Vector[PDBView]]
        ): Unit = {
            spoolRoot.foreach { dir =>
                reader.readEmbeddedPortablePdb(Some(dir)) match {
                    case Success(Some(view)) =>
                        view.sources.foreach { source =>
                            builder += new EmbeddedSourceEntry(
                                DotnetNameSanitizer.sanitize(source.name),
                                source,
                                session
                            )
                        }
                        views += view
                    case Success(None) => ()
                    case Failure(_) => throw DataFormatException()
                }
            }
        }
    }
}
