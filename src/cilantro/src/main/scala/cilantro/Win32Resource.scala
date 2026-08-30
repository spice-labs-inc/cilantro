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
// C5-04). Cilantro exposes raw blobs only: it never interprets ICON /
// VERSION / MANIFEST contents — consumers decide what the bytes mean.

package io.spicelabs.cilantro

sealed class Win32Resource(
    private val _typeId: Int,
    private val _typeName: Option[String],
    private val _nameId: Int,
    private val _name: Option[String],
    private val _language: Int,
    private val _blob: Array[Byte]
) {
    def typeId = _typeId
    def typeName = _typeName
    def nameId = _nameId
    def name = _name
    def language = _language
    def blob = _blob

    // Stable display name: the RT_* type name (or the numeric id) and
    // the resource name (or numeric id).
    def typeNameOrId = _typeName.getOrElse(_typeId.toString)
    def nameOrId = _name.getOrElse(_nameId.toString)
}
