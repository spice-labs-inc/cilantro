//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Metadata/Utilities.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.metadata.*;

class MetadataConsts

object MetadataConsts {
    val tableCount = 58
    val codedIndexCount = 14
    val noDataMarker: Short = -1
    val notResolvedMarker: Short = -2
}


extension (b: Byte)
    def asIntNoSign = b.toInt & 0xff

extension (data: Array[Byte])
    // returns a tuple with the first element being the data read
    // and the second element being the new position after the read
    def readCompressedInt32(position: Int) = 
        val dataAtPos = data(position);
        if ((dataAtPos & 0x80) == 0) {
            (dataAtPos.asIntNoSign, position + 1)
        } else if ((dataAtPos & 0x40) == 0) {
            (((dataAtPos & ~0x80).toInt << 8) | data(position + 1).asIntNoSign, position + 2)
        } else {
            var i = (dataAtPos.asIntNoSign & ~0xc0) << 24
            i |= data(position + 1).asIntNoSign << 16
            i |= data(position + 2).asIntNoSign << 8
            i |= data(position + 3).asIntNoSign
            (i, position + 4)
        }
    def readCompressedUInt32(position: Int) =
        val dataAtPos = data(position)
        if ((dataAtPos & 0x80) == 0)
            (dataAtPos.asIntNoSign, position + 1)
        else if ((dataAtPos & 0x40) == 0)
            (((dataAtPos.asIntNoSign & ~0x80) << 8) | data(position + 1).asIntNoSign, position + 2)
        else
            var i = (dataAtPos.asIntNoSign & 0xc0) << 24
            i |= data(position + 1).asIntNoSign << 16
            i |= data(position + 2).asIntNoSign << 8
            i |= data(position + 3)
            (i, position + 4)
            

extension (self: CodedIndex)
    def getMetadataToken(data: Int) =
    self match
        case CodedIndex.typeDefOrRef =>
            val rid = data >>> 2
            data & 3 match
                case 0 => MetadataToken(TokenType.typeDef, rid)
                case 1 => MetadataToken(TokenType.typeRef, rid)
                case 2 => MetadataToken(TokenType.typeSpec, rid)
                case _ => MetadataToken.zero

        case CodedIndex.hasConstant =>
            val rid = data >>> 2
            data & 3 match
                case 0 => MetadataToken(TokenType.field, rid)
                case 1 => MetadataToken(TokenType.param, rid)
                case 2 => MetadataToken(TokenType.property, rid)
                case _ => MetadataToken.zero

        case CodedIndex.hasCustomAttribute =>
            var rid = data >>> 5
            data & 31 match
                case 0 => MetadataToken(TokenType.method, rid)
                case 1 => MetadataToken(TokenType.field, rid)
                case 2 => MetadataToken(TokenType.typeRef, rid)
                case 3 => MetadataToken(TokenType.typeDef, rid)
                case 4 => MetadataToken(TokenType.param, rid)
                case 5 => MetadataToken(TokenType.interfaceImpl, rid)
                case 6 => MetadataToken(TokenType.memberRef, rid)
                case 7 => MetadataToken(TokenType.module, rid)
                case 8 => MetadataToken(TokenType.permission, rid)
                case 9 => MetadataToken(TokenType.property, rid)
                case 10 => MetadataToken(TokenType.event, rid)
                case 11 => MetadataToken(TokenType.signature, rid)
                case 12 => MetadataToken(TokenType.moduleRef, rid)
                case 13 => MetadataToken(TokenType.typeSpec, rid)
                case 14 => MetadataToken(TokenType.assembly, rid)
                case 15 => MetadataToken(TokenType.assemblyRef, rid)
                case 16 => MetadataToken(TokenType.file, rid)
                case 17 => MetadataToken(TokenType.exportedType, rid)
                case 18 => MetadataToken(TokenType.manifestResource, rid)
                case 19 => MetadataToken(TokenType.genericParam, rid)
                case 20 => MetadataToken(TokenType.genericParamConstraint, rid)
                case 21 => MetadataToken(TokenType.methodSpec, rid)
                case _ => MetadataToken.zero
        case CodedIndex.hasFieldMarshal =>
            val tt = if (data & 1) == 0 then TokenType.field else TokenType.param
            MetadataToken(tt, data >>> 1)
        case CodedIndex.hasDeclSecurity =>
            val rid = data >>> 2
            data & 3 match
                case 0 => MetadataToken(TokenType.typeDef, rid)
                case 1 => MetadataToken(TokenType.method, rid)
                case 2 => MetadataToken(TokenType.assembly, rid)
                case _ => MetadataToken.zero
        case CodedIndex.memberRefParent =>
            val rid = data >>> 3
            data & 7 match
                case 0 => MetadataToken(TokenType.typeDef, rid)
                case 1 => MetadataToken(TokenType.typeRef, rid)
                case 2 => MetadataToken(TokenType.moduleRef, rid)
                case 3 => MetadataToken(TokenType.method, rid)
                case 4 => MetadataToken(TokenType.typeSpec, rid)
                case _ => MetadataToken.zero
        case CodedIndex.hasSemantics =>
            val tt = if (data & 1) == 0 then TokenType.event else TokenType.property
            MetadataToken(tt, data >>> 1)
        case CodedIndex.methodDefOrRef =>
            val tt = if (data & 1) == 0 then TokenType.method else TokenType.memberRef
            MetadataToken(tt, data >>> 1)
        case CodedIndex.memberForwarded =>
            val tt = if (data & 1) == 0 then TokenType.field else TokenType.method
            MetadataToken(tt, data >>> 1)
        case CodedIndex.implementation =>
            val rid = data >>> 2
            data & 3 match
                case 0 => MetadataToken(TokenType.file, rid)
                case 1 => MetadataToken(TokenType.assemblyRef, rid)
                case 2 => MetadataToken(TokenType.exportedType, rid)
                case _ => MetadataToken.zero
        case CodedIndex.customAttributeType =>
            val rid = data >>> 3
            data & 7 match
                case 2 => MetadataToken(TokenType.method, rid)
                case 3 => MetadataToken(TokenType.memberRef, rid)
                case _ => MetadataToken.zero
        case CodedIndex.resolutionScope =>
            val tt = data & 3 match
                case 0 => TokenType.module
                case 1 => TokenType.moduleRef
                case 2 => TokenType.assemblyRef
                case 3 => TokenType.typeRef
            MetadataToken(tt, data >> 2)
        case CodedIndex.typeOrMethodDef =>
            val tt = if (data & 1) == 0 then TokenType.typeDef else TokenType.method
            MetadataToken(tt, data >> 1)
        case CodedIndex.hasCustomDebugInformation =>
            val rid = data >>> 5
            data & 31 match
                case 0 => MetadataToken(TokenType.method, rid)
                case 1 => MetadataToken(TokenType.field, rid)
                case 2 => MetadataToken(TokenType.typeRef, rid)
                case 3 => MetadataToken(TokenType.typeDef, rid)
                case 4 => MetadataToken(TokenType.param, rid)
                case 5 => MetadataToken(TokenType.interfaceImpl, rid)
                case 6 => MetadataToken(TokenType.memberRef, rid)
                case 7 => MetadataToken(TokenType.module, rid)
                case 8 => MetadataToken(TokenType.permission, rid)
                case 9 => MetadataToken(TokenType.property, rid)
                case 10 => MetadataToken(TokenType.event, rid)
                case 11 => MetadataToken(TokenType.signature, rid)
                case 12 => MetadataToken(TokenType.moduleRef, rid)
                case 13 => MetadataToken(TokenType.typeSpec, rid)
                case 14 => MetadataToken(TokenType.assembly, rid)
                case 15 => MetadataToken(TokenType.assemblyRef, rid)
                case 16 => MetadataToken(TokenType.file, rid)
                case 17 => MetadataToken(TokenType.exportedType, rid)
                case 18 => MetadataToken(TokenType.manifestResource, rid)
                case 19 => MetadataToken(TokenType.genericParam, rid)
                case 20 => MetadataToken(TokenType.genericParamConstraint, rid)
                case 21 => MetadataToken(TokenType.methodSpec, rid)
                case 22 => MetadataToken(TokenType.document, rid)
                case 23 => MetadataToken(TokenType.localScope, rid)
                case 24 => MetadataToken(TokenType.localVariable, rid)
                case 25 => MetadataToken(TokenType.localConstant, rid)
                case 26 => MetadataToken(TokenType.importScope, rid)
                case _ => MetadataToken.zero
        case null => MetadataToken.zero
    
    def compressMetadataToken (token: MetadataToken) =
        val ret: Option[Int] =
            if token.RID == 0 then Some(0)
            else
                self match
                    case CodedIndex.typeDefOrRef => 
                        val rid = token.RID << 2
                        token.tokenType match
                            case TokenType.typeDef => Some(rid)
                            case TokenType.typeRef => Some(rid | 1)
                            case TokenType.typeSpec => Some(rid | 2)
                            case _ => None
                    case CodedIndex.hasConstant =>
                        val rid = token.RID << 2
                        token.tokenType match
                            case TokenType.field => Some(rid)
                            case TokenType.param => Some(rid | 1)
                            case TokenType.property => Some(rid | 2)
                            case _ => None
                    case CodedIndex.hasCustomAttribute =>
                        val rid = token.RID << 5
                        token.tokenType match
                            case TokenType.method => Some(rid)
                            case TokenType.field => Some(rid | 1)
                            case TokenType.typeRef => Some(rid |2)
                            case TokenType.typeDef => Some(rid |3)
                            case TokenType.param => Some(rid | 4)
                            case TokenType.interfaceImpl => Some(rid | 5)
                            case TokenType.memberRef => Some(rid | 6)
                            case TokenType.module => Some(rid | 7)
                            case TokenType.permission => Some(rid | 8)
                            case TokenType.property => Some(rid | 9)
                            case TokenType.event => Some(rid | 10)
                            case TokenType.signature => Some(rid | 11)
                            case TokenType.moduleRef => Some(rid | 12)
                            case TokenType.typeSpec => Some(rid | 13)
                            case TokenType.assembly => Some(rid | 14)
                            case TokenType.assemblyRef => Some(rid | 15)
                            case TokenType.file => Some(rid | 16)
                            case TokenType.exportedType => Some(rid | 17)
                            case TokenType.manifestResource => Some(rid | 18)
                            case TokenType.genericParam => Some(rid | 19)
                            case TokenType.genericParamConstraint => Some(rid | 20)
                            case TokenType.methodSpec => Some(rid | 21)
                            case _ => None                        
                    case CodedIndex.hasFieldMarshal =>
                        val rid = token.RID << 1
                        token.tokenType match
                            case TokenType.field => Some(rid)
                            case TokenType.param => Some(rid | 1)
                            case _ => None
                    case CodedIndex.hasDeclSecurity =>
                        val rid = token.RID << 2
                        token.tokenType match
                            case TokenType.typeDef => Some(rid)
                            case TokenType.method => Some(rid | 1)
                            case TokenType.assembly => Some(rid | 2)
                            case _ => None
                    case CodedIndex.memberRefParent =>
                        val rid = token.RID << 3
                        token.tokenType match
                            case TokenType.typeDef => Some(rid)
                            case TokenType.typeRef => Some(rid | 1)
                            case TokenType.moduleRef => Some(rid | 2)
                            case TokenType.method => Some(rid | 3)
                            case TokenType.typeSpec => Some(rid | 4)
                            case _ => None
                    case CodedIndex.hasSemantics =>
                        val rid = token.RID << 1
                        token.tokenType match
                            case TokenType.event => Some(rid)
                            case TokenType.property => Some(rid | 1)
                            case _ => None
                    case CodedIndex.methodDefOrRef =>
                        val rid = token.RID << 1
                        token.tokenType match
                            case TokenType.method => Some(rid)
                            case TokenType.memberRef => Some(rid | 1)
                            case _ => None
                    case CodedIndex.memberForwarded =>
                        val rid = token.RID << 1
                        token.tokenType match
                            case TokenType.field => Some(rid)
                            case TokenType.method => Some(rid | 1)
                            case _ => None
                    case CodedIndex.implementation =>
                        val rid = token.RID << 2
                        token.tokenType match
                            case TokenType.file => Some(rid)
                            case TokenType.assemblyRef => Some(rid | 1)
                            case TokenType.exportedType => Some(rid | 2)
                            case _ => None                        
                    case CodedIndex.customAttributeType =>
                        val rid = token.RID << 3
                        token.tokenType match
                            case TokenType.method => Some(rid | 2)
                            case TokenType.memberRef => Some(rid | 3)
                            case _ => None                        
                    case CodedIndex.resolutionScope =>
                        val rid = token.RID << 2
                        token.tokenType match
                            case TokenType.module => Some(rid)
                            case TokenType.moduleRef => Some(rid | 1)
                            case TokenType.assemblyRef => Some(rid | 2)
                            case TokenType.typeRef => Some(rid | 3)
                            case _ => None                        
                    case CodedIndex.typeOrMethodDef =>
                        val rid = token.RID << 1
                        token.tokenType match
                            case TokenType.typeDef => Some(rid)
                            case TokenType.method => Some(rid | 1)
                            case _ => None
                    case CodedIndex.hasCustomDebugInformation =>
                        val rid = token.RID << 5
                        token.tokenType match
                            case TokenType.method => Some(rid)
                            case TokenType.field => Some(rid | 1)
                            case TokenType.typeRef => Some(rid |2)
                            case TokenType.typeDef => Some(rid |3)
                            case TokenType.param => Some(rid | 4)
                            case TokenType.interfaceImpl => Some(rid | 5)
                            case TokenType.memberRef => Some(rid | 6)
                            case TokenType.module => Some(rid | 7)
                            case TokenType.permission => Some(rid | 8)
                            case TokenType.property => Some(rid | 9)
                            case TokenType.event => Some(rid | 10)
                            case TokenType.signature => Some(rid | 11)
                            case TokenType.moduleRef => Some(rid | 12)
                            case TokenType.typeSpec => Some(rid | 13)
                            case TokenType.assembly => Some(rid | 14)
                            case TokenType.assemblyRef => Some(rid | 15)
                            case TokenType.file => Some(rid | 16)
                            case TokenType.exportedType => Some(rid | 17)
                            case TokenType.manifestResource => Some(rid | 18)
                            case TokenType.genericParam => Some(rid | 19)
                            case TokenType.genericParamConstraint => Some(rid | 20)
                            case TokenType.methodSpec => Some(rid | 21)
                            case TokenType.document => Some(rid | 22)
                            case TokenType.localScope => Some(rid | 23)
                            case TokenType.localVariable => Some(rid | 24)
                            case TokenType.localConstant => Some(rid | 25)
                            case TokenType.importScope => Some(rid | 26)
                            case _ => None
        ret match
            case Some(value) => value
            case None => throw IllegalArgumentException()
    def getSize(counter: Table => Int) =
        val (bits, table): (Int, Array[Table]) =
            self match
                case CodedIndex.typeDefOrRef => (2, Array(Table.typeDef, Table.typeRef, Table.typeSpec))
                case CodedIndex.hasConstant => (2, Array(Table.field, Table.param, Table.property))
                case CodedIndex.hasCustomAttribute => (5, Array(Table.method, Table.field, Table.typeRef, Table.typeDef,
                        Table.param, Table.interfaceImpl, Table.memberRef,
                        Table.module, Table.declSecurity, Table.property, Table.event, Table.standAloneSig, Table.moduleRef,
                        Table.typeSpec, Table.assembly, Table.assemblyRef, Table.file, Table.exportedType,
                        Table.manifestResource, Table.genericParam, Table.genericParamConstraint, Table.methodSpec))
                case CodedIndex.hasFieldMarshal => (1, Array(Table.field, Table.param))
                case CodedIndex.hasDeclSecurity => (2, Array(Table.typeDef, Table.method, Table.assembly))
                case CodedIndex.memberRefParent => (3, Array(Table.typeDef, Table.typeRef, Table.moduleRef, Table.method,
                        Table.typeSpec))
                case CodedIndex.hasSemantics => (1, Array(Table.event, Table.property))
                case CodedIndex.methodDefOrRef => (1, Array(Table.method, Table.memberRef))
                case CodedIndex.memberForwarded => (1, Array(Table.field, Table.method))
                case CodedIndex.implementation => (2, Array(Table.file, Table.assemblyRef, Table.exportedType))
                case CodedIndex.customAttributeType => (3, Array(Table.method, Table.memberRef))
                case CodedIndex.resolutionScope => (2, Array(Table.module, Table.moduleRef, Table.assemblyRef, Table.typeRef))
                case CodedIndex.typeOrMethodDef => (1, Array(Table.typeDef, Table.method))
                case CodedIndex.hasCustomDebugInformation => (5, Array(Table.method, Table.field, Table.typeRef,
                        Table.typeDef, Table.param, Table.interfaceImpl, Table.memberRef,
                        Table.module, Table.declSecurity, Table.property, Table.event, Table.standAloneSig, Table.moduleRef,
                        Table.typeSpec, Table.assembly, Table.assemblyRef, Table.file, Table.exportedType,
                        Table.manifestResource, Table.genericParam, Table.genericParamConstraint, Table.methodSpec,
                        Table.document, Table.localScope, Table.localVariable, Table.localConstant, Table.importScope))

        val max = table.map(counter).max
        if max < (1 << (16 - bits)) then 2 else 4
