// AssemblyEntry + AssemblyEntryKind — the container-walk entry
// contract (plans 2026_09_02 + 2026_09_04; ADR-0014, D-1/D-2/D-4/D-8
// and the byte-faithful amendment B-1..B-11).
//
// AssemblyWalker.withinAssemblyStream hands Goat Rodeo one complete
// Vector[AssemblyEntry] or None (all-or-nothing). Every entry carries
// its pre-hardened name (DotnetNameSanitizer), its kind (routing), its
// MIME hint, its LENGTH, and its payload through
// PayloadSource.processStream.
//
// The byte-faithful principle (2026_09_04, B-9): an entry only
// delivers bytes — no decompression, no envelope checks, no magic
// sniffing, no payload-validity logic of any kind. For file-backed
// payloads, `length` is the exact count of the bytes as they exist in
// the artifact (a zero-copy slice); for the Class kind, `length` is
// the canonical-JSON byte count — the single documented exception
// (a class has no contiguous in-file bytes; the JSON is the
// deterministic content-addressed identity text, spec 2.3/9.2).
// `length` is captured at entry construction (never re-derived from
// the file) and needs no read beyond construction-time reads.
//
// Entries are valid only inside the owning walk call: after it
// returns, `processStream` and `length` refuse cleanly (IOException),
// while the pure metadata (name/kind/hint/facts) stays readable.
// Names are hardened but NOT guaranteed unique (consumers key by
// vector position).
//
// MIME hints are cilantro-owned (D-4). The DebugBlob hint is the one
// per-entry override: the type-17 debug payload (the compressed
// portable-PDB envelope) is routed as `pe/debug; format=mpdb` — a
// claim about the DECLARED debug-directory type, never a content-
// validity promise (B-9: the walk does not sniff payload magic).

package io.spicelabs.cilantro

import java.io.{ByteArrayInputStream, IOException, InputStream}

enum AssemblyEntryKind(val mimeHint: Option[String]) {
    case Class extends AssemblyEntryKind(Some("cilantro/type"))
    case EmbeddedResource extends AssemblyEntryKind(None)
    case AuthenticodeCertificate extends AssemblyEntryKind(None)
    case Win32Resource extends AssemblyEntryKind(Some("pe/resource"))
    case DebugBlob extends AssemblyEntryKind(Some("pe/debug"))
}

sealed trait AssemblyEntry extends PayloadSource {
    def name: String
    def kind: AssemblyEntryKind
    def mimeHint: Option[String] = kind.mimeHint

    // The exact byte count of the payload this entry delivers (B-4):
    // for file-backed payloads the bytes as they exist in the
    // artifact; for Class the canonical-JSON bytes (the documented
    // exception). Refuses after the owning walk ends, exactly like
    // processStream.
    def length: Long
}

// Internal: the owning call's liveness flag. Entries (and the PDB
// view's sources) check it before serving so retained references
// refuse cleanly after the owning call ends (CP-2e(3)), instead of
// reading maps whose owning scope has ended.
private[cilantro] final class AssemblyWalkSession(
    private val refuseMessage: String = "assembly walk ended; entry no longer usable"
) {
    @volatile private var _closed: Boolean = false

    private[cilantro] def close(): Unit = {
        _closed = true
    }

    private[cilantro] def ensureOpen(): Unit = {
        if (_closed) {
            throw IOException(refuseMessage)
        }
    }
}

// The walk's per-kind carriers. Each name is pre-hardened at
// construction (single place, CP-6); each length is captured at
// construction (byte-faithfulness, B-4/B-11).
final class ClassEntry private[cilantro] (
    private val _name: String,
    private val typeDefinition: TypeDefinition,
    private val jsonBytes: Array[Byte],
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.Class
    def `type`: TypeDefinition = typeDefinition

    // The documented exception: the canonical-JSON byte count.
    def length: Long = {
        session.ensureOpen()
        jsonBytes.length.toLong
    }

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        val in = new ByteArrayInputStream(jsonBytes)
        try {
            f(in)
        } finally {
            in.close()
        }
    }
}

final class EmbeddedResourceEntry private[cilantro] (
    private val _name: String,
    private val payload: PayloadSource,
    private val payloadLength: Long,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.EmbeddedResource

    def length: Long = {
        session.ensureOpen()
        payloadLength
    }

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        payload.processStream(f)
    }
}

final class AuthenticodeCertificateEntry private[cilantro] (
    private val _name: String,
    private val certificate: CertificateEntry,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.AuthenticodeCertificate

    def certificateRevision: Int = certificate.revision
    def certificateType: Int = certificate.certificateType

    def length: Long = {
        session.ensureOpen()
        certificate.payloadByteLength
    }

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        certificate.processStream(f)
    }
}

final class Win32ResourceEntry private[cilantro] (
    private val _name: String,
    private val resource: Win32Resource,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.Win32Resource

    def resourceTypeId: Int = resource.typeId
    def resourceNameId: Int = resource.nameId
    def resourceLanguage: Int = resource.language

    def length: Long = {
        session.ensureOpen()
        resource.payloadByteLength
    }

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        resource.processStream(f)
    }
}

final class DebugBlobEntry private[cilantro] (
    private val _name: String,
    private val debugEntryType: Int,
    private val payload: PayloadSource,
    private val payloadLength: Long,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.DebugBlob
    def debugEntryTypeValue: Int = debugEntryType

    // B-7: the type-17 debug payload is the compressed portable-PDB
    // envelope — routed with the discriminating hint. The hint is a
    // claim about the DECLARED debug-directory type; the walk never
    // validates payload content (B-9).
    override def mimeHint: Option[String] =
        if (debugEntryType == 17) Some("pe/debug; format=mpdb") else Some("pe/debug")

    def length: Long = {
        session.ensureOpen()
        payloadLength
    }

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        payload.processStream(f)
    }
}
