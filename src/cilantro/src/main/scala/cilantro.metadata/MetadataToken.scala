//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/MetadataToken.cs

package io.spicelabs.cilantro

import scala.language.strictEquality

class MetadataToken(val token: Int) {
    given CanEqual[MetadataToken, MetadataToken] = CanEqual.derived

    def this(type_ : TokenType) = this(type_.value)
    def this(type_ : TokenType, rid: Int) = this (type_.value | rid)

    def RID = token & 0x00_ffffff
    def tokenType =
        val ott = TokenType.methodSpec
        val tv = token & 0xff_000000
        val ts = f"0x$tv%x"
        TokenType.fromOrdinalValue(token & 0xff_000000)

    override def equals(that: Any): Boolean = that match
        case a: MetadataToken => this.token == a.token
        case _ => false

    override def hashCode(): Int = token.##

    override def toString(): String =
        val name = tokenType.toString()
        f"[$name:0x$RID%04x]"
}

object MetadataToken {
    val zero = MetadataToken(0)
}
