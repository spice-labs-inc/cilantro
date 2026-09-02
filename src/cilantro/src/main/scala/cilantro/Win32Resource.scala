//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Win32Resource — one leaf of the PE resource directory tree (plan 13,
// C5-04; plan 2026_09_02, phase B). Cilantro exposes raw blobs only:
// it never interprets ICON / VERSION / MANIFEST contents — consumers
// decide what the bytes mean. The leaf payload is a streaming,
// zero-copy view (PayloadSource) over the file; there is no byte
// budget on it — the only rule is that the declared size lies inside
// the file (ADR-0014, D-10).

package io.spicelabs.cilantro

final class Win32Resource(
    private val _typeId: Int,
    private val _typeName: Option[String],
    private val _nameId: Int,
    private val _name: Option[String],
    private val _language: Int,
    private val payload: PayloadSource
) extends PayloadSource {
    def typeId = _typeId
    def typeName = _typeName
    def nameId = _nameId
    def name = _name
    def language = _language

    // Stable display name: the RT_* type name (or the numeric id) and
    // the resource name (or numeric id).
    def typeNameOrId = _typeName.getOrElse(_typeId.toString)
    def nameOrId = _name.getOrElse(_nameId.toString)

    def processStream[T](f: java.io.InputStream => T): T = payload.processStream(f)
}
