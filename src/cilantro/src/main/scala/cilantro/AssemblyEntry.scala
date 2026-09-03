// AssemblyEntry + AssemblyEntryKind — the container-walk entry
// contract (plan 2026_09_02, phases C/D; ADR-0014, D-1/D-2/D-4/D-8).
//
// AssemblyWalker.withinAssemblyStream hands Goat Rodeo one complete
// Vector[AssemblyEntry] or None (all-or-nothing): every entry carries
// its pre-hardened name (DotnetNameSanitizer), its kind, the
// cilantro-owned MIME hint (D-4), and its payload through
// PayloadSource.processStream (D-8) — cleanup is guaranteed when
// processStream exits. Entries are valid only inside the owning walk
// call: after it returns they refuse cleanly (IOException). Names are
// hardened but NOT guaranteed unique (documented; consumers key by
// vector position).
//
// The sealed per-kind carriers keep the kind-specific model data on
// the entry so consumers never own PE/metadata mechanics: a
// certificate entry exposes certificateRevision/certificateType
// (spec 2.2's "cert kind carries revision/type"); a class entry
// carries its TypeDefinition; a win32 entry carries the Win32Resource
// (type/name/language facts).

package io.spicelabs.cilantro

import java.io.{ByteArrayInputStream, IOException, InputStream}

enum AssemblyEntryKind(val mimeHint: Option[String]) {
    case Class extends AssemblyEntryKind(Some("cilantro/type"))
    case EmbeddedResource extends AssemblyEntryKind(None)
    case AuthenticodeCertificate extends AssemblyEntryKind(None)
    case Win32Resource extends AssemblyEntryKind(Some("pe/resource"))
    case DebugBlob extends AssemblyEntryKind(Some("pe/debug"))
    case EmbeddedSource extends AssemblyEntryKind(None)
}

sealed trait AssemblyEntry extends PayloadSource {
    def name: String
    def kind: AssemblyEntryKind
    def mimeHint: Option[String] = kind.mimeHint
}

// Internal: the owning walk's liveness flag. Entries check it before
// serving a stream so that retained entries refuse cleanly after the
// walk returns (CP-2e(3)), instead of reading maps whose owning scope
// has ended.
private[cilantro] final class AssemblyWalkSession {
    @volatile private var _closed: Boolean = false

    private[cilantro] def close(): Unit = {
        _closed = true
    }

    private[cilantro] def ensureOpen(): Unit = {
        if (_closed) {
            throw IOException("assembly walk ended; entry no longer usable")
        }
    }
}

// The walk's per-kind carriers. Each name is pre-hardened at
// construction (single place, CP-6).
final class ClassEntry private[cilantro] (
    private val _name: String,
    private val typeDefinition: TypeDefinition,
    private val jsonBytes: Array[Byte],
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.Class
    def `type`: TypeDefinition = typeDefinition

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
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.EmbeddedResource

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

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        resource.processStream(f)
    }
}

final class DebugBlobEntry private[cilantro] (
    private val _name: String,
    private val debugEntryType: Int,
    private val payload: PayloadSource,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.DebugBlob
    def debugEntryTypeValue: Int = debugEntryType

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        payload.processStream(f)
    }
}

// Produced by the Phase D spool path; declared now so the kind set is
// complete and contract-pinned (CP-7).
final class EmbeddedSourceEntry private[cilantro] (
    private val _name: String,
    private val payload: PayloadSource,
    private val session: AssemblyWalkSession
) extends AssemblyEntry {
    def name = _name
    def kind = AssemblyEntryKind.EmbeddedSource

    def processStream[T](f: InputStream => T): T = {
        session.ensureOpen()
        payload.processStream(f)
    }
}
