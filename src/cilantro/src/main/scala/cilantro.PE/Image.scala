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
    var stream: Option[Disposable[FileInputStream]] = None
    var fileName: Option[String] = None
    var kind: ModuleKind = ModuleKind.dll
    var characteristics: Int = 0
    var runtimeVersion: Option[String] = None
    var architecture: TargetArchitecture = TargetArchitecture.i386
    var dllCharacteristics: ModuleCharacteristics = 0
    var linkerVersion: Char = 0
    var subSystemMajor: Char = 0
    var subSystemMinor: Char = 0
    var debugHeader: Option[ImageDebugHeader] = None
    var sections: Array[Section] = Array.empty
    var metadataSection: Option[Section] = None
    var entryPointToken: Int = 0
    var timeStamp: Int = 0
    var attributes: Int = 0
    var win32Resources: Option[DataDirectory] = None
    var debug: Option[DataDirectory] = None
    var resources: Option[DataDirectory] = None
    var strongName: Option[DataDirectory] = None
    var stringHeap: Option[StringHeap] = None
    var blobHeap: Option[BlobHeap] = None
    var userStringHeap: Option[UserStringHeap] = None
    var guidHeap: Option[GuidHeap] = None
    var tableHeap: Option[TableHeap] = None
    var pdbHeap: Option[PdbHeap] = None
    val coded_index_sizes = Array.ofDim[Int](14)
    var counter: (t: Table) => Int = getTableLength

    def hasTable(table: Table) = {
        getTableLength(table) > 0

    }
    def getTableLength(table: Table) = {
        tableHeap.map(_(table).length).getOrElse(0)

    }
    def getTableIndexSize(table: Table) = {
        if getTableLength(table) < 65536 then 2 else 4

    }
    def getCodedIndexSize(coded_index: CodedIndex) = {
        val index = coded_index.ordinal
        var size = coded_index_sizes(index)
        if (size != 0) {
            size
        }
        else {
            size = coded_index.getSize(counter)
            coded_index_sizes(index) = size
            size

        }
    }
    def resolveVirtualAddress(rva: Int) = {
        for {
            section <- getSectionAtVirtualAddress(rva)
        } yield resolveVirtualAddressInSection(rva, section)

    }
    def resolveVirtualAddressInSection(rva: Int, section: Section) = {
        rva + section.pointerToRawData - section.virtualAddress

    }
    def getSection(name: String): Option[Section] = {
        sections.find(sec => sec.name.contains(name))

    }
    def getSectionAtVirtualAddress(rva: Int) = {
        sections.find((section) =>
            rva >= section.virtualAddress && rva < section.virtualAddress + section.sizeOfRawData)

    }
    def getReaderAt(rva: Int): Option[BinaryStreamReader] = {
        for {
            section <- getSectionAtVirtualAddress(rva)
            theStream <- stream
        } yield {
            val reader = BinaryStreamReader(theStream.value)
            reader.moveTo(resolveVirtualAddressInSection(rva, section))
            reader
        }

    }
    def getReaderAt[TItem, TRet](rva: Int, item: TItem, read: (TItem, BinaryStreamReader) => TRet): Option[TRet] = {
        for {
            theStream <- stream
            position = theStream.value.getChannel().position
            reader <- getReaderAt(rva)
            result <- try {
                Some(read(item, reader))
            } finally {
                theStream.value.getChannel().position(position)
            }
        } yield result

    }
    def hasDebugTables() = {
        hasTable(Table.document)
        || hasTable(Table.methodDebugInformation)
        || hasTable(Table.localScope)
        || hasTable(Table.localVariable)
        || hasTable(Table.localConstant)
        || hasTable(Table.stateMachineMethod)
        || hasTable(Table.customDebugInformation)

    }
    override def close(): Unit = stream.foreach(_.dispose())
}
