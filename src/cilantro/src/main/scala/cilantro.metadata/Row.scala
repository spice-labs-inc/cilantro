//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/Row.cs

package io.spicelabs.cilantro.metadata

class Row2[T1, T2] (val col1: T1, val col2: T2) {
    given CanEqual[Row2[T1, T2], Row2[T1, T2]] = CanEqual.derived

    override def equals(that: Any): Boolean = {
        that match {
            case row: Row2[T1, T2] @unchecked =>
                col1 == row.col1 && col2 == row.col2
            case _ => false;
        }
    }
    override def hashCode(): Int = {
        val c1 = Option(col1).map(_.hashCode()).getOrElse(0)
        val c2 = Option(col2).map(_.hashCode()).getOrElse(0)
        c1 ^ c2
    }
}
class Row3[T1, T2, T3] (val col1: T1, val col2: T2, val col3: T3) {
    given CanEqual[Row3[Int, Int, Int], Row3[Int, Int, Int]] = CanEqual.derived

    override def equals(that: Any): Boolean = {
        that match {
            case row: Row3[T1, T2, T3] @unchecked =>
                col1 == row.col1 && col2 == row.col2 && col3 == row.col3
            case _ => false
        }
    }
    override def hashCode(): Int = {
        val c1 = Option(col1).map(_.hashCode()).getOrElse(0)
        val c2 = Option(col2).map(_.hashCode()).getOrElse(0)
        val c3 = Option(col3).map(_.hashCode()).getOrElse(0)
        c1 ^ c2 ^ c3
    }
}

class Row4[T1, T2, T3, T4] (private val col1: T1, private val col2: T2, private val col3: T3, private val col4: T4)
class Row5[T1, T2, T3, T4, T5] (private val col1: T1, private val col2: T2, private val col3: T3, private val col4: T4,
    private val col5: T5)
class Row6[T1, T2, T3, T4, T5, T6] (private val col1: T1, private val col2: T2, private val col3: T3, private val col4: T4,
    private val col5: T5, col6: T6)
class Row9[T1, T2, T3, T4, T5, T6, T7, T8, T9] (private val col1: T1, private val col2: T2, private val col3: T3, private val col4: T4,
    private val col5: T5, col6: T6, col7: T7, col8: T8, col9: T9)
