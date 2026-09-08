// AssemblyWalker — the container walk (plan 2026_09_02 + the
// 2026_09_04 byte-faithful amendment; ADR-0014, D-1/D-2/D-4/D-5/D-8
// + B-1..B-11).
//
// withinAssemblyStream[T](artifact)(f) hands Goat Rodeo one complete
// Vector[AssemblyEntry] or None:
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

    def withinAssemblyStream[T](artifact: File)(f: Vector[AssemblyEntry] => T): Option[T] =
        withinAssemblyStream(artifact.toPath)(f)

    def withinAssemblyStream[T](artifact: Path)(f: Vector[AssemblyEntry] => T): Option[T] = {
        // 2026_09_04 (B-2/B-9): the walk is byte-faithful — it takes no
        // spool directory, never decompresses, and contains zero
        // payload logic.
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
                                case Some(reader) => AssemblyEnumerator.enumerate(module, reader, session)
                            }
                        }
                        enumerated match {
                            case Failure(_) =>
                                session.close()
                                assembly.close()
                                None
                            case Success(entries) =>
                                try {
                                    val result = f(entries)
                                    Some(result)
                                } finally {
                                    session.close()
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
            session: AssemblyWalkSession
        ): Vector[AssemblyEntry] = {
            val builder = Vector.newBuilder[AssemblyEntry]
            enumerateClasses(module, session, builder)
            enumerateResources(module, reader, session, builder)
            enumerateCertificates(reader, session, builder)
            enumerateWin32(reader, session, builder)
            enumerateDebugBlobs(reader, session, builder)
            builder.result()
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
                            val payload = reader.managedResourcePayload(offset)
                            builder += new EmbeddedResourceEntry(
                                DotnetNameSanitizer.sanitize(r.name),
                                payload,
                                PayloadBytes.lengthOf(payload),
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
                // B-4: the type-17 payload length is its in-file sizeOfData;
                // the hint override (format=mpdb) lives on the entry.
                builder += new DebugBlobEntry(
                    DotnetNameSanitizer.sanitize(name),
                    entry.entryType,
                    entry,
                    entry.payloadByteLength,
                    session
                )
            }
        }
    }
}
