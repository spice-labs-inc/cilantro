//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// DebugEntryData + EmbeddedPdb + EmbeddedSourceFile — the debug-directory
// data exposure (plan 13, C5-05). Cilantro exposes raw blobs only; it
// never interprets the codeview/PDB internals beyond the embedded-source
// walk documented in ADR-0011.

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer

sealed class DebugEntryData(private val _entryType: Int, private val _blob: Array[Byte]) {
    def entryType = _entryType
    def blob = _blob
}

sealed class EmbeddedSourceFile(private val _name: String, private val _bytes: Array[Byte]) {
    def name = _name
    def bytes = _bytes
}

sealed class EmbeddedPdb(private val _sources: ArrayBuffer[EmbeddedSourceFile]) {
    def sources = _sources
}
