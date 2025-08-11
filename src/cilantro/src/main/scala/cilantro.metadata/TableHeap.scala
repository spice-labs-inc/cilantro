//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/TableHeap.cs

package io.spicelabs.cilantro.metadata

import javax.swing.text.Utilities
import io.spicelabs.cilantro
import io.spicelabs.cilantro.MetadataConsts


enum Table(val value: Byte)
{
    case module extends Table(0x00)
    case typeRef extends Table(0x01)
    case typeDef extends Table(0x02)
    case fieldPtr extends Table(0x03)
    case field extends Table(0x04)
    case methodPtr extends Table(0x05)
    case method extends Table(0x06)
    case paramPtr extends Table(0x07)
    case param extends Table(0x08)
    case interfaceImpl extends Table(0x09)
    case memberRef extends Table(0x0a)
    case constant extends Table(0x0b)
    case customAttribute extends Table(0x0c)
    case fieldMarshal extends Table(0x0d)
    case declSecurity extends Table(0x0e)
    case classLayout extends Table(0x0f)
    case fieldLayout extends Table(0x10)
    case standAloneSig extends Table(0x11)
    case eventMap extends Table(0x12)
    case eventPtr extends Table(0x13)
    case event extends Table(0x14)
    case propertyMap extends Table(0x15)
    case propertyPtr extends Table(0x16)
    case property extends Table(0x17)
    case methodSemantics extends Table(0x18)
    case methodImpl extends Table(0x19)
    case moduleRef extends Table(0x1a)
    case typeSpec extends Table(0x1b)
    case implMap extends Table(0x1c)
    case fieldRVA extends Table(0x1d)
    case encLog extends Table(0x1e)
    case encMap extends Table(0x1f)
    case assembly extends Table(0x20)
    case assemblyProcessor extends Table(0x21)
    case assemblyOS extends Table(0x22)
    case assemblyRef extends Table(0x23)
    case assemblyRefProcessor extends Table(0x24)
    case assemblyRefOS extends Table(0x25)
    case file extends Table(0x26)
    case exportedType extends Table(0x27)
    case manifestResource extends Table(0x28)
    case nestedClass extends Table(0x29)
    case genericParam extends Table(0x2a)
    case methodSpec extends Table(0x2b)
    case genericParamConstraint extends Table(0x2c)
    case document extends Table(0x30)
    case methodDebugInformation extends Table(0x31)
    case localScope extends Table(0x32)
    case localVariable extends Table(0x33)
    case localConstant extends Table(0x34)
    case importScope extends Table(0x35)
    case stateMachineMethod extends Table(0x36)
    case customDebugInformation extends Table(0x37)
}

object Table {
    def fromOrdinalValue(value: Int) =
    Table.values.find(x => {x.value == value}) match
      case Some(result) => result
      case None => throw IllegalArgumentException(s"value $value not found in Table")  
}

class TableInformation() {
    var offset = 0
    var length = 0
    var rowSize = 0
    def isLarge =
        length > Char.MaxValue
}

class TableHeap (_data: Array[Byte]) extends Heap(_data) {
    var valid = 0L
    var sorted = 0L
    val tables = Array.fill[TableInformation](MetadataConsts.tableCount){TableInformation()}

    def apply(table:Table) =
        tables(table.value)

    def hasTable(table: Table) =
        (valid & (1L << table.value)) != 0
}