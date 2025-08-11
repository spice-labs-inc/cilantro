//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.PE/ImageReader.cs

package io.spicelabs.cilantro.PE

import io.spicelabs.cilantro.Disposable
import java.io.FileInputStream
import java.util.zip.DataFormatException
import io.spicelabs.cilantro.TargetArchitecture
import io.spicelabs.cilantro.ModuleKind
import scala.util.boundary, boundary.break
import io.spicelabs.cilantro.cil.ImageDebugHeader
import io.spicelabs.cilantro.cil.ImageDebugHeaderEntry
import io.spicelabs.cilantro.cil.ImageDebugDirectory
import io.spicelabs.cilantro.cil.ImageDebugType
import io.spicelabs.cilantro.metadata.GuidHeap
import io.spicelabs.cilantro.metadata.TableHeap
import io.spicelabs.cilantro.metadata.StringHeap
import io.spicelabs.cilantro.metadata.BlobHeap
import io.spicelabs.cilantro.metadata.UserStringHeap
import io.spicelabs.cilantro.metadata.PdbHeap
import io.spicelabs.cilantro.MetadataConsts.tableCount
import io.spicelabs.cilantro.MetadataConsts
import io.spicelabs.cilantro.metadata.Table
import io.spicelabs.cilantro.metadata.Heap
import io.spicelabs.cilantro.metadata.CodedIndex
import java.io.IOException

class ImageReader(stream: Disposable[FileInputStream], file_name: String) extends BinaryStreamReader(stream.value) {
    import ImageReader.makeImage
    import ImageReader.getModuleKind
    import ImageReader.setIndexSize

    val image = makeImage(stream, file_name)

    private var cli: DataDirectory = null
    private var metadata: DataDirectory = null
    private var table_heap_offset = 0
    private var pdb_heap_offset = 0

    private def moveTo(directory: DataDirectory) =
        fileInputStream.getChannel().position(image.resolveVirtualAddress(directory.virtualAddress))
    
    private def readImage(): Unit =
        if (fileInputStream.getChannel().size() < 128)
            throw new DataFormatException()

        // - DOSHeader

        // PE                   2
        // Start                58
        // Lfanew               4
        // End                  64

        if (readUInt16() != 0x5a4d)
            throw DataFormatException()
        
        advance(58)

        moveTo(readInt32())

        if (readInt32() != 0x00004550)
            throw DataFormatException()
        
        // - PEFileHeader

        // Machine              2
        image.architecture = readArchitecture()

        // NumberOfSections     2
        val sections = readUInt16()

        // TimeDataStamp        4
        image.timeStamp = readInt32()
        // PointerToSymbolTable 4
        // NumberOfSymbols      4
        // OptionalHeaderSize   2
        advance(10)

        // Characteristics      2
        val characteristics = readUInt16()

        val (subsystem, dll_characteristics) = readOptionalHeaders()
        readSections(sections)
        readCLIHeader()
        readMetadata()
        readDebugHeader()

        image.characteristics = characteristics
        image.kind = getModuleKind(characteristics, subsystem)
        image.dllCharacteristics = dll_characteristics

    private def readArchitecture() = TargetArchitecture.fromOrdinalValue(readUInt16().toInt)

    private def readOptionalHeaders(): (Char, Char) =
        // - PEOptionalHeader
        //   - StandardFileHeader
        // Magic                2
        val pe64 = readUInt16() == 0x20b

        //                      pe32 || pe64

        image.linkerVersion = readUInt16()
        // CodeSize             4
        // InitializedDataSize  4
        // UninitializedDataSize4
        // EntryPointRVA        4
        // BaseOfCode           4
        // BaseOfData           4 || 0

        //    - NTSpecificFieldsHeader
        // ImageBase            4 || 8
        // SectionAlignment     4
        // FileAlignment        4
        // OSMajor              2
        // OSMinor              2
        // UserMajor            2
        // UserMinor            2
        // SubSysMajor          2
        // SubSysMinor          2
        advance(44)

        image.subSystemMajor = readUInt16()
        image.subSystemMinor = readUInt16()

        // Reserved             4
        // ImageSize            4
        // HeaderSize           4
        // FileChecksum         4
        advance(16)

        // SubSystem            2
        val subsystem = readUInt16()

        // DllFlags             2
        val dll_characteristics = readUInt16()
        // StackReserveSize     4 || 8
        // StackCommitSize      4 || 8
        // HeapReserveSize      4 || 8
        // HeapCommitSize       4 || 8
        // LoaderFlags          4
        // NumberOfDataDir      4

        //    - DataDirectoriesHeader

        // ExportTable          8
        // ImportTable          8

        advance(if pe64 then 56 else 40)

        // ResourceTable        8

        image.win32Resources = readDataDirectory()

        // ExceptionTable       8
        // CertificateTable     8
        // BaseRelocationTable  8

        advance(24)

        // Debug
        image.debug = readDataDirectory()

        // Copyright            8
        // GlobalPtr            8
        // TLSTable             8
        // LoadConfigTable      8
        // BoundImport          8
        // IAT                  8
        // DelayImportDescriptor8
        advance(56)

        // CLIHeader            8
        cli = readDataDirectory()
        
        if (cli.isZero)
            throw DataFormatException()
        
        // Reserved             8
        advance(8)

        (subsystem, dll_characteristics)

    private def readAlignedString(length: Int) =
        var read = 0
        val buffer = Array.ofDim[Char](length)
        boundary:
            while read < length do
                val current = readByte()
                if (current == 0) break()
                buffer(read) = current.toChar
                read += 1
        advance (-1 + ((read + 4) & ~3) - read)

        String(buffer, 0, read)

    private def readZeroTerminatedString(length: Int) =
        var read = 0
        val buffer = Array.ofDim[Char](length)
        val bytes = readBytes(length)
        boundary:
            while read < length do
                val current = bytes(read)
                if (current == 0) break()

                buffer(read) = current.toChar
                read += 1
        String(buffer, 0, read)

    private def readSections(count: Char) =
        val sections = Array.ofDim[Section](count)
        
        for i <- 0 to count-1 do
            val section = Section()

            // Name
            section.name = readZeroTerminatedString(8)

            // VirtualSize      4
            advance(4)

            // VirtualAddress   4
            section.virtualAddress = readInt32()
            // SizeOfRawData    4
            section.sizeOfRawData = readInt32()
            // PointerToRawData 4
            section.pointerToRawData = readInt32()

            // PointerToRelocations     4
            // PointerToLineNumbers     4
            // NumberOfRelocations      2
            // NumberOfLineNumbers      2
            // Characteristics          4
            advance(16)
            sections(i) = section
        
        image.sections = sections
    
    private def readCLIHeader() =
        moveTo(cli)

        // - CLIHeader

        // Cb                       4
        // MajorRuntimeVersion      2
        // MinorRuntimeVersion      2
        advance(8)

        // Metadata                 8
        metadata = readDataDirectory()
        // Flags                    4
        image.attributes = readInt32()
        // EntryPointToken          4
        image.entryPointToken = readInt32()
        // Resources                8
        image.resources = readDataDirectory()
        // StrongNameSignature      8
        image.strongName = readDataDirectory()
        // CodeManagerTable         8
        // VTableFixups             8
        // ExportAddressTableJumps  8
        // ManagedNativeHeader      8

    private def readMetadata() =
        moveTo(metadata)
        if (readInt32() != 0x424a5342)
            throw DataFormatException()

        // MajorVersion         2
        // MinorVersion         2
        // Reserved             4
        advance(8)

        image.runtimeVersion = readZeroTerminatedString(readInt32())

        // Flags                2
        advance(2)

        val streams = readUInt16()

        val section = image.getSectionAtVirtualAddress(metadata.virtualAddress) match
            case Some(a) => a
            case None => throw new DataFormatException()
        
        image.metadataSection = section

        for i <- 0 to streams do
            readMetadataStream(section)
        
        if (image.pdbHeap != null)
            readPdbHeap()
        
        if (image.tableHeap != null)
            readTableHeap()

    private def readDebugHeader(): Unit =
        if (image.debug.isZero)
            image.debugHeader = ImageDebugHeader(Array.empty[ImageDebugHeaderEntry])
            return ()
        
        moveTo(image.debug)

        var entries = Array.ofDim[ImageDebugHeaderEntry](image.debug.size / ImageDebugDirectory.size)
        
        for i <- 0 to entries.length do
            var directory = ImageDebugDirectory()
            directory.characteristics = readInt32()
            directory.timeDataStamp = readInt32()
            directory.majorVersion = readInt16()
            directory.minorVersion = readInt16()
            directory.`type` = ImageDebugType.fromOrdinalValue(readInt32())
            directory.sizeOfData = readInt32()
            directory.addressOfRawData = readInt32()
            directory.pointerToRawData = readInt32()

            if (directory.pointerToRawData == 0 || directory.sizeOfData < 0)
                entries(i) = ImageDebugHeaderEntry(directory, Array.emptyByteArray)
            else
                val position = this.position

                try
                    moveTo(directory.pointerToRawData)
                    val data = readBytes(directory.sizeOfData)
                    entries(i) = ImageDebugHeaderEntry(directory, data)
                finally
                    this.position_(position)

        image.debugHeader = ImageDebugHeader(entries)

    private def readMetadataStream(section: Section) =
        // Offset       4
        val offset = metadata.virtualAddress - section.virtualAddress + readInt32()

        // Size         4
        val size = readInt32()

        val data = readHeapData(offset, size)

        val name = readAlignedString(16)
        name match
            case "#~" | "#-" =>
                image.tableHeap = TableHeap(data)
                table_heap_offset = offset
            case "#Strings" => image.stringHeap = StringHeap(data)
            case "#Blob" => image.blobHeap = BlobHeap(data)
            case "#GUID" => image.guidHeap = GuidHeap(data)
            case "#US" => image.userStringHeap = UserStringHeap(data)
            case "#Pdb" =>
                image.pdbHeap = PdbHeap(data)
                pdb_heap_offset = offset

    private def readHeapData(offset: Int, size: Int) =
        val position = fileInputStream.getChannel().position()
        moveTo(offset + image.metadataSection.pointerToRawData)
        val data = readBytes(size)
        fileInputStream.getChannel().position(position)
        data

    private def readTableHeap() =
        val heap = image.tableHeap

        moveTo(table_heap_offset + image.metadataSection.pointerToRawData)

        // Reserved         4
        // MajorVersion     1
        // MinorVersion     1
        advance(6)

        // HeapSizes        1
        val sizes = readByte()

        // Reserved2        1
        advance(1)

        // Valid            8
        heap.valid = readInt64()

        // Sorted           8
        heap.sorted = readInt64()

        if (image.pdbHeap != null)
            for i <- 0 to MetadataConsts.tableCount do
                if (image.pdbHeap.hasTable(Table.fromOrdinalValue(i)))
                    heap.tables(i).length = image.pdbHeap.typeSystemTableRows(i)
        
        for i <- 0 to MetadataConsts.tableCount do
            if (heap.hasTable(Table.fromOrdinalValue(i)))
                heap.tables(i).length = readInt32()

        setIndexSize(image.stringHeap, sizes, 0x1)
        setIndexSize(image.guidHeap, sizes, 0x2)
        setIndexSize(image.blobHeap, sizes, 0x4)

        computeTableInformations()

    private def getTableIndexSize(table: Table) =
        image.getTableIndexSize(table)

    private def getCodedIndexSize(index: CodedIndex) =
        image.getCodedIndexSize(index)

    private def computeTableInformations() =
        var offset = (fileInputStream.getChannel().position() - table_heap_offset - image.metadataSection.pointerToRawData).toInt // header

        val stridx_size = if image.stringHeap != null then image.stringHeap.indexSize else 2
        val guididx_size = if image.guidHeap != null then image.guidHeap.indexSize else 2
        val blobidx_size = if image.blobHeap != null then image.blobHeap.indexSize else 2

        val heap = image.tableHeap
        val tables = heap.tables

        for i <- 0 to MetadataConsts.tableCount do
            val table = Table.fromOrdinalValue(i)

            if (heap.hasTable(table))
                val size = table match
                    case Table.module => 2 + stridx_size + (guididx_size * 3)
                    case Table.typeRef =>
                        getCodedIndexSize(CodedIndex.resolutionScope) + (stridx_size * 2)
                    case Table.typeDef =>
                        4 + (stridx_size * 2) + getCodedIndexSize(CodedIndex.typeDefOrRef)
                            + getTableIndexSize(Table.field)
                            + getTableIndexSize(Table.method)
                    case Table.fieldPtr => getTableIndexSize(Table.field)
                    case Table.field => 2 + stridx_size + blobidx_size
                    case Table.methodPtr => getTableIndexSize(Table.method)
                    case Table.method => 8 + stridx_size + blobidx_size + getTableIndexSize(Table.param)
                    case Table.paramPtr => getTableIndexSize(Table.param)
                    case Table.param => 4 + stridx_size
                    case Table.interfaceImpl => getTableIndexSize(Table.typeDef) + getCodedIndexSize(CodedIndex.typeDefOrRef)
                    case Table.memberRef => getCodedIndexSize(CodedIndex.memberRefParent) + stridx_size + blobidx_size
                    case Table.constant => 2 + getCodedIndexSize(CodedIndex.hasConstant) + blobidx_size
                    case Table.customAttribute => getCodedIndexSize(CodedIndex.hasCustomAttribute)
                        + getCodedIndexSize(CodedIndex.customAttributeType) + blobidx_size
                    case Table.fieldMarshal => getCodedIndexSize(CodedIndex.hasFieldMarshal) + blobidx_size
                    case Table.declSecurity => 2 + getCodedIndexSize(CodedIndex.hasDeclSecurity) + blobidx_size
                    case Table.classLayout => 6 + getTableIndexSize(Table.typeDef)
                    case Table.fieldLayout => 4 + getTableIndexSize(Table.field)
                    case Table.standAloneSig => blobidx_size
                    case Table.eventMap => getTableIndexSize(Table.typeDef) + getTableIndexSize(Table.event)
                    case Table.eventPtr => getTableIndexSize(Table.event)
                    case Table.event => 2 + stridx_size + getCodedIndexSize(CodedIndex.typeDefOrRef)
                    case Table.propertyMap => getTableIndexSize(Table.typeDef) + getTableIndexSize(Table.property)
                    case Table.propertyPtr => getTableIndexSize(Table.property)
                    case Table.property => 2 + stridx_size + blobidx_size
                    case Table.methodSemantics => 2 + getTableIndexSize(Table.method) + getCodedIndexSize(CodedIndex.hasSemantics)
                    case Table.methodImpl => getTableIndexSize(Table.typeDef)
                        + getCodedIndexSize(CodedIndex.methodDefOrRef) + getCodedIndexSize(CodedIndex.methodDefOrRef)
                    case Table.moduleRef => stridx_size
                    case Table.typeSpec => blobidx_size
                    case Table.implMap => 2 + getCodedIndexSize(CodedIndex.memberForwarded) + stridx_size + getTableIndexSize(Table.moduleRef)
                    case Table.fieldRVA => 4 + getTableIndexSize(Table.field)
                    case Table.encLog => 8
                    case Table.encMap => 45
                    case Table.assembly => 16 + blobidx_size + (stridx_size * 2)
                    case Table.assemblyProcessor => 4
                    case Table.assemblyOS => 12
                    case Table.assemblyRef => 12 + (blobidx_size * 2) + (stridx_size * 2)
                    case Table.assemblyRefProcessor => 4 + getTableIndexSize(Table.assemblyRef)
                    case Table.assemblyRefOS => 12 + getTableIndexSize(Table.assemblyRef)
                    case Table.file => 4 + stridx_size + blobidx_size
                    case Table.exportedType => 8 + (stridx_size * 2) + getCodedIndexSize(CodedIndex.implementation)
                    case Table.manifestResource => 8 + stridx_size + getCodedIndexSize(CodedIndex.implementation)
                    case Table.nestedClass => getTableIndexSize(Table.typeDef) + getTableIndexSize(Table.typeDef)
                    case Table.genericParam => 4 + getCodedIndexSize(CodedIndex.typeOrMethodDef) + stridx_size
                    case Table.methodSpec => getCodedIndexSize(CodedIndex.methodDefOrRef) + blobidx_size
                    case Table.genericParamConstraint => getTableIndexSize(Table.genericParam) + getCodedIndexSize(CodedIndex.typeDefOrRef)
                    case Table.document => blobidx_size + guididx_size + blobidx_size + guididx_size
                    case Table.methodDebugInformation => getTableIndexSize(Table.document) + blobidx_size
                    case Table.localScope => getTableIndexSize(Table.method)
                        + getTableIndexSize(Table.importScope) + getTableIndexSize(Table.localVariable)
                        + getTableIndexSize(Table.localConstant) + 4 * 2
                    case Table.localVariable => 2 + 2 + stridx_size
                    case Table.localConstant => stridx_size + blobidx_size
                    case Table.importScope => getTableIndexSize(Table.importScope) + blobidx_size
                    case Table.stateMachineMethod => getTableIndexSize(Table.method) + getTableIndexSize(Table.method)
                    case Table.customDebugInformation => getCodedIndexSize(CodedIndex.hasCustomDebugInformation) + guididx_size + blobidx_size
                    case null => throw UnsupportedOperationException()
                
                tables(i).rowSize = size
                tables(i).offset = offset

                offset += size * tables(i).length

    private def readPdbHeap() =
        val heap = image.pdbHeap
        val buffer = ByteBuffer(heap.data)

        heap.id = buffer.readBytes(20)
        heap.entryPoint = buffer.readInt32()
        heap.typeSystemTables = buffer.readInt64()
        heap.typeSystemTableRows = Array.ofDim[Int](MetadataConsts.tableCount)

        for i <- 0 to MetadataConsts.tableCount do
            val table = Table.fromOrdinalValue(i)
            if (heap.hasTable(table))
                heap.typeSystemTableRows(i) = buffer.readInt32()

}

object ImageReader {
    private def makeImage(stream: Disposable[FileInputStream], file_name: String) = 
        val image = Image()
        image.stream = stream
        image.fileName = file_name

        image
    
    private def getModuleKind(characteristics: Char, subsystem: Char) =
        if ((characteristics & 0x2000) != 0)
            ModuleKind.dll
        
        if (subsystem == 0x02 || subsystem == 0x9)
            ModuleKind.windows

        ModuleKind.console

    private def setIndexSize(heap: Heap, sizes: Int, flag: Int) =
        if (heap != null)
            heap.indexSize = if (sizes & flag) > 0 then 4 else 2

    def readImage(stream: Disposable[FileInputStream], file_name: String) =
        try
            val reader = ImageReader(stream, file_name)
            reader.readImage()
            reader.image
        catch
            case err: IOException => throw DataFormatException(file_name)

    def readPortablePdb(stream: Disposable[FileInputStream], file_name: String): (Image, Int) =
        try 
            val reader = ImageReader(stream, file_name)
            val length = stream.value.getChannel().size().toInt
            val section = Section()
            section.pointerToRawData = 0
            section.sizeOfRawData = length
            section.virtualAddress = 0
            section.virtualSize = 0
            reader.image.sections = Array[Section](section)

            reader.metadata = DataDirectory(0, length)
            reader.readMetadata()
            (reader.image, reader.pdb_heap_offset)
        catch
            case err: IOException => throw DataFormatException(file_name)

}
