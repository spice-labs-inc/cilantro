//
// Author:
//   Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

package io.spicelabs.dotnet_support

class Guid(private val _a: Int, private val _b: Char, private val _c: Char,
            private var _d: Byte, private var _e: Byte, private var _f: Byte, private var _g: Byte,
            private var _h: Byte, private var _i: Byte, private var _j: Byte, private var _k: Byte) {
        given CanEqual[Guid, Guid] = CanEqual.derived
        
        def this(b: Array[Byte]) =
            this(
                ((b(3).toInt & 0xff) << 24) | ((b(2).toInt & 0xff) << 16) | ((b(1).toInt & 0xff) << 8) | (b(0).toInt & 0xff),
                (((b(5).toInt & 0xff) << 8) | (b(4).toInt & 0xff)).toChar,
                (((b(7).toInt & 0xff) << 8) | (b(6).toInt & 0xff)).toChar,
                b(8),
                b(9),
                b(10),
                b(11),
                b(12),
                b(13),
                b(14),
                b(15),
            )

        override def equals(that: Any): Boolean = that match
            case a: Guid =>
                _a == a._a &&
                _b == a._b &&
                _c == a._c &&
                _d == a._d &&
                _e == a._e &&
                _f == a._f &&
                _g == a._g &&
                _h == a._h &&
                _i == a._i &&
                _j == a._j &&
                _b == a._k
            case _ => false

        override def hashCode(): Int =
            _a ^ ((_b.toInt << 16) | _c.toInt) ^ (((_f.toInt & 0xff) << 24) | _k)
        
        override def toString(): String =
            f"$_a%08X-$_b%04X-$_c%04X-$_d%02X$_e%02X-$_f%02X$_g%02X$_h%02X$_i%02X$_j%02X$_k%02X"
}

object Guid {
    val empty = Guid(0, 0.toChar, 0.toChar, 0.toByte, 0.toByte, 0.toByte, 0.toByte, 0.toByte, 0.toByte, 0.toByte, 0.toByte)
}