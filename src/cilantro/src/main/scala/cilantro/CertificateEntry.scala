//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// CertificateEntry — one WIN_CERTIFICATE entry from a PE Security data
// directory (plan 13, C5-03). Cilantro exposes the raw blob only; it
// never parses PKCS#7 — certificate processing belongs to consumers.

package io.spicelabs.cilantro

sealed class CertificateEntry(private val _revision: Int, private val _certificateType: Int, private val _blob: Array[Byte]) {
    def revision = _revision
    def certificateType = _certificateType
    def blob = _blob
}
