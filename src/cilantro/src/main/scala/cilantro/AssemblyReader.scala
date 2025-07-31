//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/AssemblyReader.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.PE.ByteBuffer
import io.spicelabs.cilantro.PE.Image
import io.spicelabs.cilantro.metadata.CodedIndex
import io.spicelabs.cilantro.metadata.Table
import scala.collection.mutable.ListBuffer


sealed class MetadataReader(val image: Image, val module: ModuleDefinition, val metadata_reader: MetadataReader) extends ByteBuffer(image.tableHeap.data) {
    // TODO
    // val metadata: MetadataSystem = module.metadataSystem
    // private var code: CodeReader
    // private var context: GenericContext


    def this(module: ModuleDefinition) =
        this(module.image, module, module.reader)

    def getCodedIndexSize(index: CodedIndex) =
        image.getCodedIndexSize(index)
    
    def readByIndexSize(size: Int) =
        if size == 4 then readUInt32() else readUInt16().toInt
    
    def readBlob() =
        val blob_heap = image.blobHeap
        if (blob_heap == null) {
            position = position + 2
            Array.emptyByteArray
        }
        blob_heap.read(readBlobIndex())

    def readBlob(signature: Int) =
        var blob_heap = image.blobHeap
        if (blob_heap == null)
            Array.emptyByteArray
        blob_heap.read(signature)
    
    def readBlobIndex() =
        var blob_heap = image.blobHeap
        readByIndexSize(if blob_heap != null then blob_heap.indexSize else 2)

    def getBlobView(signature: Int) =
        val blob_heap = image.blobHeap
        if (blob_heap != null)
            blob_heap.getView(signature)
        else
            (null, 0, 0)

    def readString() =
        image.stringHeap.read(readByIndexSize(image.stringHeap.indexSize))
    
    def readStringIndex() =
        readByIndexSize(image.stringHeap.indexSize)
    
    def readGuid() =
        image.guidHeap.read(readByIndexSize(image.guidHeap.indexSize))

    def readTableIndex(table: Table) =
        readByIndexSize(image.getTableIndexSize(table))
    
    def readMetadataToken(index: CodedIndex) =
        index.getMetadataToken(readByIndexSize(getCodedIndexSize(index)))
    
    def moveTo(table: Table) =
        val info = image.tableHeap(table)
        if (info.length != 0)
            this.position = info.offset

        info.length

    def moveTo(table: Table, row: Int): Boolean =
        val info = image.tableHeap(table)
        val length = info.length
        if (length == 0 || row > length)
            false
        else
            this.position = (info.offset + (info.rowSize * (row - 1)))
            true

    // TODO - finish

    private def initializeAssemblyReferences(): Unit = { }

    def readAssemblyReferences() =
        initializeAssemblyReferences()

        var references = ListBuffer[AssemblyNameReference]()

        // TODO
        // if (module.isWindowsMetadata())
        //     module.projections.addVirtualReferences(references)

        references

    def readModules() =
        val modules = ListBuffer[ModuleDefinition](this.module)

        val length = moveTo(Table.file)
        for i <- 1 to length do
            val attributes = FileAttributes.fromOrdinal(readUInt32())
            val name = readString()
            readBlobIndex()

            if (attributes == FileAttributes.containsMetadata)
                val prms = ReaderParameters()
                prms.readingMode_(module.readingMode)
                // TODO
                // prms.symbolReaderProvider_(module.symbolReaderProvider)
                // prms.assemblyResolver_(module.assembly_resolver)

                // val netModule = ModuleDefinition.readModule(getModuleFileName(name), parameters)
                // modules.append(netModule)
        modules


    private def initializeModuleReferences() = { }

    def readModuleReferences() =
        initializeModuleReferences()

        var references = ListBuffer.empty[ModuleReference]

        // TODO
        // references.append(metadata.moduleReferences)

        references

    // TODO
    private def initializeCustomAttributes() = { }

    def hasCustomAttributes(owner: CustomAttributeProvider) =
        initializeCustomAttributes()
        // TODO
        // val rangeOpt = metadata.tryGetCustomAttributeRanges(owner)
        // rangeOpt match
        //     case Some(ranges) => rangeSize(anges) > 0
        //     case _ => false
        
        false

    def readCustomAttributes(owner: CustomAttributeProvider): ListBuffer[CustomAttribute] =
        initializeCustomAttributes()
        val custom_attributes = ListBuffer.empty[CustomAttribute]

        // TOOD
        // val rangeOpt = metadata.tryGetCustomAttributeRanges(owner)
        // rangeOpt match
        //     case Some(ranges) => {
        //         for range <- ranges do
        //             readCustomAttributeRange(ranges, custom_attributes)
        //     }
        //     case _ => return custom_attributes

        // if (module.isWindowsMetadata)
        //     for custom_attribute <- custom_attributes do
        //         windowsRuntimeProjections.project(owner, custom_attributes, custom_attribute)        

        custom_attributes


    def readCustomAttributeBlob(signature: Int) =
        readBlob(signature)

    // TODO
    def readCustomAttributesSignature(attribute: CustomAttribute): Unit =
        ()     
}