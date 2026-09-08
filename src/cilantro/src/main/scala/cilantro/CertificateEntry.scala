//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// CertificateEntry — one WIN_CERTIFICATE entry (plan 13, C5-03; plan
// 2026_09_02, phase B). Cilantro exposes the raw blob only: it never
// interprets PKCS#7 content (ADR-0010's no-PKCS#7 rule). The payload
// is a streaming, zero-copy view (PayloadSource) over the file's
// certificate table; the 8-byte WIN_CERTIFICATE header (dwLength /
// wRevision / wCertificateType) stays on the entry as
// certificateRevision / certificateType so consumers can stamp
// without owning any PE layout.

package io.spicelabs.cilantro

final class CertificateEntry(
    private val _revision: Int,
    private val _certificateType: Int,
    private val payload: PayloadSource
) extends PayloadSource {
    def revision = _revision
    def certificateType = _certificateType

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)

    // Internal byte-faithful length (2026_09_04, B-4): the exact
    // in-file byte count of the blob this entry delivers.
    private[cilantro] def payloadByteLength: Long = PayloadBytes.lengthOf(payload)
}
