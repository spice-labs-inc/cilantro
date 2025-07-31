//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.PE/Image.cs

package io.spicelabs.cilantro.PE

import io.spicelabs.cilantro.*
import java.io.FileInputStream
import io.spicelabs.cilantro.metadata.*
import io.spicelabs.cilantro.cil.ImageDebugHeader

type ModuleCharacteristics = Int

sealed class Image extends AutoCloseable {
    var stream: Disposable[FileInputStream] = null
    var fileName: String = null
    var kind: ModuleKind = ModuleKind.dll
    var characteristics: Int = 0
    var runtimeVersion: String = null
    var architecture: TargetArchitecture = TargetArchitecture.i386
    var dllCharacteristics: ModuleCharacteristics = 0
    var linkerVersion: Char = 0
    var subSystemMajor: Char = 0
    var subSystemMinor: Char = 0
    var debugHeader: ImageDebugHeader = null
    var sections: Array[Section] = null
    var metadataSection: Section = null
    var entryPointToken: Int = 0
    var timeStamp: Int = 0
    var attributes: Int = 0
    var win32Resources: DataDirectory = null
    var debug: DataDirectory = null
    var resources: DataDirectory = null
    var strongName: DataDirectory = null
    var stringHeap: StringHeap = null
    var blobHeap: BlobHeap = null
    var userStringHeap: UserStringHeap = null
    var guidHeap: GuidHeap = null
    var tableHeap: TableHeap = null
    var pdbHeap: PdbHeap = null
    val coded_index_sizes = Array.ofDim[Int](14)
    var counter: (t: Table) => Int = getTableLength

    def hasTable (table: Table) =
        getTableLength(table) > 0
    
    def getTableLength(table: Table) =
        tableHeap(table).length

    def getTableIndexSize(table: Table) =
        if getTableLength(table) < 65536 then 2 else 4
    
    def getCodedIndexSize(coded_index: CodedIndex) =
        val index = coded_index.ordinal
        var size = coded_index_sizes(index)
        if (size != 0)
            size
        size = coded_index.getSize(counter)
        coded_index_sizes(index) = size
        size
    
    def resolveVirtualAddress(rva: Int) =        
        getSectionAtVirtualAddress(rva) match
            case Some(section) => resolveVirtualAddressInSection(rva, section)
            case _ => throw IllegalArgumentException()

    def resolveVirtualAddressInSection(rva: Int, section: Section) =
        rva + section.pointerToRawData - section.virtualAddress

    def getSection(name: String): Option[Section] =
        sections.find(sec => sec.name == name)

    def getSectionAtVirtualAddress(rva: Int) =
        sections.find((section) => rva >= section.virtualAddress && rva < section.virtualAddress + section.sizeOfRawData)
    
    def getReaderAt(rva: Int): BinaryStreamReader =
        val section = getSectionAtVirtualAddress(rva)
        section match
            case Some(section) => {
                val reader = BinaryStreamReader(stream.value)
                reader.moveTo(resolveVirtualAddressInSection(rva, section))
                reader
            }
            case _ => null

    def getReaderAt[TItem, TRet](rva: Int, item: TItem, read: (TItem, BinaryStreamReader) => TRet): Option[TRet] =
        var position = stream.value.getChannel().position
        try
            val reader:BinaryStreamReader = getReaderAt(rva)
            if (reader != null) then Some(read(item, reader)) else None
            
        finally
            stream.value.getChannel().position(position)

    def hasDebugTables() =
        hasTable(Table.document)
        || hasTable(Table.methodDebugInformation)
        || hasTable(Table.localScope)
        || hasTable(Table.localVariable)
        || hasTable(Table.localConstant)
        || hasTable(Table.stateMachineMethod)
        || hasTable(Table.customDebugInformation)

    override def close(): Unit = stream.dispose()
}
