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
import io.spicelabs.cilantro.PE.BinaryStreamReader
import io.spicelabs.cilantro.metadata.CodedIndex
import io.spicelabs.cilantro.metadata.Table
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import io.spicelabs.cilantro.cil.SymbolReader
import io.spicelabs.cilantro.cil.VariableDefinition
import io.spicelabs.cilantro.cil.PortablePdbReader
import io.spicelabs.cilantro.cil.DefaultSymbolReaderProvider
import javax.naming.OperationNotSupportedException
import java.util.zip.DataFormatException
import java.nio.file.{Path, Paths}
import io.spicelabs.cilantro.metadata.Row3
import io.spicelabs.cilantro.metadata.ElementType
import io.spicelabs.cilantro.metadata.Row2
import scala.util.boundary, boundary.break
import io.spicelabs.cilantro.MetadataReader.addRange
import io.spicelabs.cilantro.SignatureReader.checkGenericContext
import io.spicelabs.cilantro.AnyExtension.as
import java.nio.charset.StandardCharsets
import io.spicelabs.cilantro.ConstantProvider.noValue
import io.spicelabs.cilantro.MetadataReader.rangesSize
import io.spicelabs.cilantro.MetadataReader.isNested


abstract class ModuleReader() {
    protected var module: Option[ModuleDefinition] = None

    protected def this(image: Image, mode: ReadingMode) = {
        this()
        module = Some(ModuleDefinition(image))
        module.foreach(_.readingMode = mode)

    }
    protected def readModule(): Unit
    def readSymbols(module: ModuleDefinition): Unit

    protected def readModuleManifest(reader: MetadataReader) = {
        module.foreach(reader.populate)
        readAssembly(reader)

    }
    private def readAssembly(reader: MetadataReader): Unit = {
        reader.readAssemblyNameDefinition() match {
            case None =>
                module.foreach(_.moduleKind_(ModuleKind.netModule))
            case Some(name) =>
                val assembly = AssemblyDefinition()
                assembly.name_(name)
                module.foreach { m =>
                    m.assembly = assembly
                    assembly.mainModule = m
                }
        }
    }
}

object ModuleReader {
    def createModule(image: Image, parameters: ReaderParameters) = {
        val reader = createModuleReader(image, parameters.readingMode)
        val module = reader.module.getOrElse(throw IllegalArgumentException("reader produced no module"))

        parameters.assemblyResolver.foreach { resolver =>
            module.assembly_resolver = Some(Disposable.notOwned(resolver))
        
        }
        parameters.metadataResolver.foreach { resolver =>
            module.metadata_resolver = Some(resolver)

        }
        parameters.metadataImporterProvider.foreach { provider =>
            module.metadata_importer = Some(provider.getMetadataImporter(module))
        
        }
        parameters.reflectionImporterProvider.foreach { provider =>
            module.reflection_importer = Some(provider.getReflectionImporter(module))

        }
        getMetadataKind(module, parameters)

        reader.readModule()

        readSymbols(module, parameters)

        reader.readSymbols(module)

        if (parameters.readingMode == ReadingMode.immediate) {
             module.metadataSystem.clear()
        }
        module

    }
    private def readSymbols(module: ModuleDefinition, parameters: ReaderParameters) = {
        val provider = parameters.symbolReaderProvider.orElse(
            if (parameters.readSymbols) Some(DefaultSymbolReaderProvider()) else None)

        provider.foreach { symbol_reader_provider =>
            module.symbolReaderProvider = Some(symbol_reader_provider)

            val reader = parameters.symbolStream match {
                case Some(stream) => symbol_reader_provider.getSymbolReader(module, stream)
                case None => symbol_reader_provider.getSymbolReader(module, module.fileName)
            }

            reader.foreach { r =>
                try {
                    module.readSymbols(r, parameters.throwIfSymbolsAreNotMatching)
                }
                catch {
                    case err: Exception =>
                        r.close()
                        throw err

                }
            }
        }
        module.image.foreach { image =>
            if (image.hasDebugTables()) {
                module.readSymbols(PortablePdbReader(image, module))

            }
        }
    }
    private def getMetadataKind(module: ModuleDefinition, parameters: ReaderParameters): Unit = {
        if (!parameters.applyWindowsRuntimeProjections) {
            module.metadataKind_(MetadataKind.ecma335)
            return ()
        
        }
        val runtime_version = module.runtimeVersion

        if (!runtime_version.contains("WindowsRuntime")) {
            module.metadataKind_(MetadataKind.ecma335)
        }
        else if (runtime_version.contains("CLR")) {
            module.metadataKind_(MetadataKind.managedWindowsMetadata)
        }
        else {
            module.metadataKind_(MetadataKind.windowsMetadata)

            }
        }
    private def createModuleReader(image: Image, mode: ReadingMode) = {
        mode match {
            case ReadingMode.immediate => ImmediateModuleReader(image)
            case ReadingMode.deferred => DeferredModuleReader(image)

        }
    }
}

sealed class ImmediateModuleReader(image: Image) extends ModuleReader(image, ReadingMode.immediate) {
    var resolve_attributes = false

    override protected def readModule(): Unit = {
        this.module.foreach(m => m.read(m, (module, reader) => {
            readModuleManifest(reader)
            readModule(module, true)
        }))

    }
    def readModule(module: ModuleDefinition, resolve_attributes: Boolean): Unit = {
        this.resolve_attributes = resolve_attributes

        if (module.hasAssemblyReferences) {
             mixinRead(module.assemblyReferences)
        }
        if (module.hasResources) {
            mixinRead(module.resources)
        }
        if (module.hasModuleReferences) {
            mixinRead(module.moduleReferences)
        // if (module.hasTypes)
        //     readTypes(module._types)
        }
        if (module.hasExportedTypes) {
            mixinRead(module.exportedTypes)
        
        }
        readCustomAttributes(module)

        module.assembly.foreach { assembly =>
            if (module.kind != ModuleKind.netModule) {
                readCustomAttributes(assembly)
                // TODO
            // readSecurityDeclarations(assembly)

            }
        }
    }
    private def readTypes(types: ArrayBuffer[TypeDefinition]) = {
        types.foreach(readType)

    }
    private def readType(`type`: TypeDefinition): Unit = {
        readGenericParameters(`type`)
        if (`type`.hasInterfaces) {
            readInterfaces(`type`)
        
        }
        if (`type`.hasNestedTypes) {
            readTypes(`type`.nestedTypes)

        // TODO        
        // if (`type`.hasLayoutInfo)
        //     read(`type`.classSize)
        
        }
        if (`type`.hasFields) {
            readFields(`type`)

        }
        if (`type`.hasMethods) {
            readMethods(`type`)

        }
        if (`type`.hasProperties) {
            readProperties(`type`)
        
        }
        if (`type`.hasEvents) {
            readEvents(`type`)
        
        }
        readSecurityDeclarations(`type`)
        readCustomAttributes(`type`)

    }
    private def readInterfaces(`type`: TypeDefinition) = {
        var interfaces = `type`.interfaces
        interfaces.foreach(readCustomAttributes)
    
    }
    private def readGenericParameters(provider: GenericParameterProvider): Unit = {
        if (!provider.hasGenericParameters) {
            ()
        
        }
        provider.genericParameters.foreach((parameter) => {
            if (parameter.hasConstraints) {
                readGenericParameterConstraints(parameter)
            }
            readCustomAttributes(parameter)
        })
    
    }
    private def readGenericParameterConstraints(parameter: GenericParameter) = {
        parameter.constraints.foreach((constraint) => {
            readCustomAttributes(constraint.asInstanceOf[CustomAttributeProvider])
        })
    
    }
    private def readSecurityDeclarations(provider: SecurityDeclarationProvider): Unit = {
        if (!provider.hasSecurityDeclarations) {
            ()
        
        }
        if (!resolve_attributes) {
            ()
        
        // provider.securityDeclarations.foreach((declaration) => {
        //     read(declaration.securityAttributes)
        // })
    
            }
        }
    private def readCustomAttributes(provider: CustomAttributeProvider): Unit = {
        if (!provider.hasCustomAttributes) {
            ()
        }
        val custom_attributes = provider.customAttributes
        if (!resolve_attributes) {
            ()
        // custom_attributes.foreach((attribute) => {
        //     read(attribute.constructorArguments)
        // })

            }
        }
    private def readFields(`type`: TypeDefinition) = {
        `type`.fields.foreach((field) => {
            // TODO
            // if (field.hasConstant)
            //     read(field.constant)

            // if (field.hasLayoutInfo)
            //     read(field.offset)

            // if (field.rva > 0)
            //     read(field.initialValue)
            
            // if (field.hasMarshalInfo)
            //     read(field.marshalInfo)
            // readCustomAttributes(field)
        })

    }
    private def readMethods(`type`: TypeDefinition) = {
        `type`.methods.foreach((method) => {
            // TODO
            // readGenericParameters(method)

            // if (method.hasParameters)
            //     readParameters(method)
            
            // if (method.hasOverrides)
            //     read(method.overrides)
            
            // if (method.isPInvokeImpl)
            //     read(method.pInvokeInfo)
            
            // readSecurityDeclarations(method)
            // readCustomAttributes(method)

            // val return_type = method.methodReturnType
            // if (return_type.hasConstant)
            //     read(return_type.constant)
            
            // if (return_type.hasMarshalInfo)
            //     read(return_type.marshalInfo)
            
            // readCustomAttributes(return_type)
        })

    }
    private def readParameters(method: MethodDefinition) = {
        ()
        // TODO
        // method.parameters.foreach((parameter) => {
        //     if (parameter.hasConstant)
        //         read(parameter.constant)
        //     if (parameter.hasMarshalInfo)
        //         read(parameter.marshalInfo)
            
        //     readCustomAttributes(parameter)
        // })
    
    }
    private def readProperties(`type`: TypeDefinition) = {
        `type`.properties.foreach((property) => {
            // TOFO
            // read(property.getMethod)

            // if (property.hasConstant)
            //     read(property.constant)
            
            // readCustomAttributes(property)
        })
    
    }
    private def readEvents(`type`: TypeDefinition) = {
        `type`.events.foreach((event) => {
            // TODO
            // read(event.addMethod)

            // readCustomAttributes(event)
        })
    
    }
    override def readSymbols(module: ModuleDefinition): Unit = {
        module.symbol_reader.foreach { symbol_reader =>
            readTypesSymbols(module.types, symbol_reader)
        }
    
    }
    private def readTypesSymbols(types: ArrayBuffer[TypeDefinition], symbol_reader: SymbolReader): Unit = {
        types.foreach((`type`) => {
            // TODO
            // `type`._custom_infos = symbol_reader.read(`type`)

            // if (`type`.hasNestedTypes)
            //     readTypesSymbols(`type`.nestedTypes, symbol_reader)
            
            // if (`type`.hasMethods)
            //     readMethodsSymbols(`type`, symbol_reader)
        })
    
    }
    private def readMethodsSymbols(`type`: TypeDefinition, symbol_reader: SymbolReader) = {
        `type`.methods.foreach((method) => {
            // TODO
            // if (method.hasBody && method.token.RID != 0 && method.debug_info == null)
            //     method.debug_info = symbol_reader.read(method)
        })


    }
}

sealed class DeferredModuleReader(image: Image) extends ModuleReader(image, ReadingMode.deferred) {

    protected override def readModule() = {
        this.module.foreach(m => m.read(m, (_, reader) => readModuleManifest(reader)))
    
    }
    override def readSymbols(module: ModuleDefinition): Unit = ()
    
}

sealed class MetadataReader(val image: Image, val module: ModuleDefinition, val metadata_reader: Option[MetadataReader]) extends ByteBuffer(image.tableHeap.map(_.data).getOrElse(Array.emptyByteArray)) {
    val metadata: MetadataSystem = module.metadataSystem
    // TODO
    // private var code: CodeReader
    var _context: Option[GenericContext] = None


    def this(module: ModuleDefinition) = {
        this(module.image.getOrElse(throw IllegalArgumentException("module has no image")), module, module.reader)

    }
    def getCodedIndexSize(index: CodedIndex) = {
        image.getCodedIndexSize(index)
    
    }
    def readByIndexSize(size: Int) = {
        if size == 4 then readUInt32() else readUInt16().toInt
    
    }
    def readBlob() = {
        image.blobHeap match {
            case None =>
                position = position + 2
                Array.emptyByteArray
            case Some(blob_heap) =>
                blob_heap.read(readBlobIndex())
        }

    }
    def readBlob(signature: Int) = {
        image.blobHeap match {
            case None => Array.emptyByteArray
            case Some(blob_heap) => blob_heap.read(signature)
        }
    
    }
    def readBlobIndex() = {
        readByIndexSize(image.blobHeap.map(_.indexSize).getOrElse(2))

    }
    def getBlobView(signature: Int) = {
        image.blobHeap match {
            case Some(blob_heap) => blob_heap.getView(signature)
            case None => (Array.emptyByteArray, 0, 0)
        }
    }
    def readString() = {
        image.stringHeap.map(heap => heap.read(readByIndexSize(heap.indexSize))).getOrElse("")
    
    }
    def readStringIndex() = {
        readByIndexSize(image.stringHeap.map(_.indexSize).getOrElse(2))
    
    }
    def readUserString(index: Int) = {
        image.userStringHeap.map(_.read(index))

    }
    def readGuid() = {
        image.guidHeap.map(heap => heap.read(readByIndexSize(heap.indexSize)))
    }
    def readTableIndex(table: Table) = {
        readByIndexSize(image.getTableIndexSize(table))
    
    }
    def readMetadataToken(index: CodedIndex) = {
        index.getMetadataToken(readByIndexSize(getCodedIndexSize(index)))
    
    }
    def moveTo(table: Table) = {
        image.tableHeap.map(_(table)) match {
            case Some(info) =>
                if (info.length != 0) {
                    this.position = info.offset

                }
                info.length
            case None => 0
        }

    }
    def moveTo(table: Table, row: Int): Boolean = {
        image.tableHeap.map(_(table)) match {
            case Some(info) =>
                val length = info.length
                if (length == 0 || row > length) {
                    false
                }
                else {
                    this.position = (info.offset + (info.rowSize * (row - 1)))
                    true

                }
            case None => false
        }
    }
    def readAssemblyNameDefinition(): Option[AssemblyNameDefinition] = {
        if (moveTo(Table.assembly) == 0) {
            None
        }
        else {
            val name = AssemblyNameDefinition()
            
            name.hashAlgorithm = AssemblyHashAlgorithm.fromOrdinalValue(readInt32())

            populateVersionAndFlags(name)
            
            name.publicKey = readBlob()

            populateNameAndCulture(name)
            Some(name)

        }
    }
    def populate(module: ModuleDefinition): ModuleDefinition = {
        if (moveTo(Table.module) == 0) {
            module
        }
        else {
            advance(2) // Generation

            module.name = readString()
            readGuid().foreach(module.mvid = _)
            module

    // TODO - finish

            }
        }
    private def initializeAssemblyReferences(): Unit = {
        if (metadata._assemblyReferences.length > 0) {
            ()
        }
        else {
            val length = moveTo(Table.assemblyRef)
            metadata._assemblyReferences = Array.ofDim[AssemblyNameReference](length)
            val references = metadata._assemblyReferences
            for i <- 0 until length do {
                val reference = AssemblyNameReference()
                reference._token = MetadataToken(TokenType.assemblyRef, i + 1)

                populateVersionAndFlags(reference)
                var key_or_token = readBlob()

                if (reference.hasPublicKey) {
                    reference.publicKey = key_or_token
                }
                else {
                    reference.publicKeyToken = key_or_token
                
                }
                populateNameAndCulture(reference)

                reference.hash = readBlob()
                references(i) = reference

            }
        }
    }
    def readAssemblyReferences() = {
        initializeAssemblyReferences()

        var references = ArrayBuffer[AssemblyNameReference]().addAll(metadata._assemblyReferences)

        // TODO
        // if (module.isWindowsMetadata())
        //     module.projections.addVirtualReferences(references)

        references
    
    }
    def readEntryPoint(): Option[MethodDefinition] = {
        if (module.image.map(_.entryPointToken).getOrElse(0) == 0) {
            None
        }
        else {
            val token = MetadataToken(module.image.map(_.entryPointToken).getOrElse(0))
            None
            // TODO
            // getMethodDefinition(token.RID)

        }
    }
    def readModules() = {
        val modules = ArrayBuffer[ModuleDefinition](this.module)

        val length = moveTo(Table.file)
        for i <- 1 to length do { // this inclusive intentionally
            val attributes = FileAttributes.fromOrdinal(readUInt32())
            val name = readString()
            readBlobIndex()

            if (attributes == FileAttributes.containsMetadata) {
                val prms = ReaderParameters()
                prms.readingMode_(module.readingMode)
                // TODO
                // prms.symbolReaderProvider_(module.symbolReaderProvider)
                // prms.assemblyResolver_(module.assembly_resolver)

                // val netModule = ModuleDefinition.readModule(getModuleFileName(name), parameters)
                // modules.append(netModule)
            }
        }
        modules

    }
    private def getModuleFileName(name: String) = {
        if (module.fileName.length == 0) {
            throw OperationNotSupportedException()
        }
        else {
            val pathToFile = Paths.get(module.fileName)
            val path = pathToFile.getParent()
            path.resolve(name).toAbsolutePath()


            }
        }
    private def initializeModuleReferences(): Unit = {
        if (metadata._moduleReferences.length > 0) {
            return {}
        
        }
        val length = moveTo(Table.moduleRef)
        metadata._moduleReferences = Array.ofDim[ModuleReference](length)
        val references = metadata._moduleReferences

        for i <- 0 until length do {
            val reference = ModuleReference(readString())
            reference._token = Some(MetadataToken(TokenType.moduleRef, i + 1))
            references(i) = reference

        }
    }
    def readModuleReferences() = {
        initializeModuleReferences()

        var references = ArrayBuffer.empty[ModuleReference]

        references.addAll(metadata._moduleReferences)

        references
    
    }
    def hasFileResource(): Boolean = {
        val length = moveTo(Table.file)
        if (length == 0) {
            return false
        
        }
        boundary {
            for i <- 1 to length do {
                if (readFileRecord(i).col1 == FileAttributes.containsNoMetadata) {
                    boundary.break(true)
                }
            }
            false
        }
    }
    
    def readResources(): ArrayBuffer[Resource] = {
        val length = moveTo(Table.manifestResource)
        val resources = ArrayBuffer[Resource]()

        for i <- 1 to length do {
            val offset = readUInt32()
            val flags = readUInt32()
            val name = readString()
            val implementation = readMetadataToken(CodedIndex.implementation)

            val resource:Option[Resource] = if (implementation.RID == 0) {
                val r = EmbeddedResource(name, flags, offset, this)
                Some(r.asInstanceOf[Resource])

            } else if (implementation.tokenType == TokenType.assemblyRef) {
                val r = AssemblyLinkedResource(name, flags)
                getTypeReferenceScope(implementation) match {
                    case Some(scope: AssemblyNameReference) => r.assembly = scope
                    case _ => ()
                }
                Some(r.asInstanceOf[Resource])
            } else if (implementation.tokenType == TokenType.file) {
                val file_record = readFileRecord(implementation.RID)
                val r = LinkedResource(name, flags)
                r.file = file_record.col2
                r._hash = readBlob(file_record.col3)
                Some(r.asInstanceOf[Resource])
            } else {
                None
            }
            
            resource match {
                case Some(resource) => resources.addOne(resource)
                case None => 
            }            
        }
        resources
    }

    def readFileRecord(rid: Int) = {
        val position = this.position

        if (!moveTo(Table.file, rid)) {
            throw new IllegalArgumentException()
        
        }
        val record = Row3[FileAttributes, String, Int](FileAttributes.fromOrdinal(readUInt32()), readString(), readBlobIndex())
        this.position = position
        record
    
    }
    // H2 (ADR-0013): resolve the CLI-header Resources directory plus a
    // ManifestResource offset to the raw file offset of the blob's
    // 4-byte length prefix. None = the directory is absent or does not
    // resolve into a section (the resource is absent); hostile offsets
    // (negative or past the file extent) throw DataFormatException
    // instead of escaping as an NIO IllegalArgumentException.
    private def managedResourceBlobStart(offset: Int): Option[Int] = {
        for {
            rs <- image.resources
            section <- image.getSectionAtVirtualAddress(rs.virtualAddress)
            disposable <- image.stream
        } yield {
            val length = disposable.value.getChannel.size()
            val raw = rs.virtualAddress.toLong + section.pointerToRawData.toLong - section.virtualAddress.toLong
            val start = raw + offset.toLong
            if (offset < 0 || start < 0 || start + 4L > length) {
                throw DataFormatException()
            }
            start.toInt
        }
    }

    // H2: read the validated length prefix at a raw blob start. A
    // declared length beyond the file extent fails with
    // DataFormatException before any allocation.
    private def readManagedResourceLength(start: Int): Int = {
        image.stream match {
            case None => throw DataFormatException()
            case Some(disposable) =>
                val length = disposable.value.getChannel.size()
                val reader = BinaryStreamReader(disposable.value)
                reader.moveTo(start)
                val declared = reader.readInt32()
                if (declared < 0 || start.toLong + 4L + declared.toLong > length) {
                    throw DataFormatException()
                }
                declared
        }
    }

    def getManagedResource(offset: Int): Array[Byte] = {
        managedResourceBlobStart(offset) match {
            case None => Array.emptyByteArray
            case Some(start) =>
                val declared = readManagedResourceLength(start)
                image.stream match {
                    case None => throw DataFormatException()
                    case Some(disposable) =>
                        val reader = BinaryStreamReader(disposable.value)
                        reader.moveTo(start + 4)
                        reader.readBytes(declared)
                }
        }
    }

    // H2: cheap declared-length accessor for EmbeddedResource — reads
    // only the 4-byte prefix, with the same extent validation as
    // getManagedResource. No blob materialization.
    def managedResourceLength(offset: Int): scala.util.Try[Int] = scala.util.Try {
        managedResourceBlobStart(offset) match {
            case None => throw DataFormatException()
            case Some(start) => readManagedResourceLength(start)
        }
    }

    // Streaming view of a managed-resource payload (plan 2026_09_02,
    // phase B): the same offset resolution and extent refusals as
    // getManagedResource, but zero-copy — the blob is never
    // materialized (CP-3; ADR-0014 D-10). The walk's EmbeddedResource
    // entries and the phase-B slice tests consume this seam; the
    // frozen public accessors (EmbeddedResource.resourceData /
    // resourceLength / getResourceStream) are unchanged.
    private[cilantro] def managedResourcePayload(offset: Int): PayloadSource = {
        managedResourceBlobStart(offset) match {
            case None => throw DataFormatException()
            case Some(start) =>
                val declared = readManagedResourceLength(start)
                image.stream match {
                    case None => throw DataFormatException()
                    case Some(disposable) =>
                        val reader = BinaryStreamReader(disposable.value)
                        reader.moveTo(start + 4)
                        reader.payloadSlice(declared)
                }
        }
    }
    private val maxCertificateEntries = 1000000

    // Debug-directory data exposure (plan 13, C5-05; plan 2026_09_02,
    // phase B): the directory entries already parse during the header
    // walk; this returns each entry's pointed-to data (CodeView,
    // embedded PDB, ...) as a zero-copy slice payload (D-10), raw,
    // bounds-checked against the file. Out-of-range entries are
    // skipped exactly as before.
    def readDebugEntryData(): ArrayBuffer[DebugEntryData] = {
        val entries = ArrayBuffer[DebugEntryData]()
        image.debugHeader.foreach { header =>
            header.entties.foreach { entry =>
                val dir = entry.directory
                // D-9/D-10: no byte budget on debug payloads — the
                // header walk already validated the extent; zero-size
                // entries are absent.
                if (dir.sizeOfData > 0) {
                    image.stream.foreach { disposable =>
                        val fileSize = disposable.value.getChannel.size()
                        val raw = if (dir.pointerToRawData > 0) {
                            dir.pointerToRawData
                        }
                        else {
                            image.resolveVirtualAddress(dir.addressOfRawData).getOrElse(-1)
                        }
                        if (raw >= 0 && raw.toLong + dir.sizeOfData.toLong <= fileSize) {
                            val reader = BinaryStreamReader(disposable.value)
                            reader.moveTo(raw)
                            entries.addOne(DebugEntryData(dir.`type`.value, reader.payloadSlice(dir.sizeOfData)))
                        }
                    }
                }
            }
        }
        entries
    }

    // Embedded portable PDB — callback-owned (2026_09_04, B-8): the
    // type-17 debug entry's payload is an MPDB envelope (magic +
    // declared uncompressed size + a raw deflate of the BSJB metadata
    // root). Reading a PDB (which may include decompression) is
    // entirely separate from the walk and from entry semantics:
    //
    //   withEmbeddedPdb[T](spoolDir)(f: Try[Option[PDBView]] => T):
    //       Try[Option[T]]
    //     f is ALWAYS invoked exactly once with the outcome:
    //       Success(Some(view)) = the spooled view (live only inside
    //                             f; cleanup automatic when f returns
    //                             or throws);
    //       Success(None)        = absent: no type-17 entry, or its
    //                             payload is not an MPDB envelope
    //                             (benign; spoolDir = None is also
    //                             absent — no in-memory fallback);
    //       Failure(e)           = hostile refusal (declaration >=
    //                             2^31, size mismatch, IO) — never
    //                             silent.
    //     The outer result passes f's value back (Failure only if f
    //     threw). Cleanup: map released, cilantro's scratch deleted,
    //     the caller's spool DIRECTORY never touched (D-6).
    def withEmbeddedPdb[T](spoolDir: Option[Path])(f: scala.util.Try[Option[PDBView]] => T): scala.util.Try[Option[T]] = {
        scala.util.Try {
            val outcome = scala.util.Try {
                spoolDir match {
                    case None => None
                    case Some(dir) =>
                        if (!java.nio.file.Files.isDirectory(dir)) {
                            throw DataFormatException()
                        }
                        embeddedPdbPayload() match {
                            case None => None
                            case Some(payload) => PortablePdbSpool.spoolAndParse(payload, dir)
                        }
                }
            }
            val view = outcome.toOption.flatten
            try {
                Some(f(outcome))
            } finally {
                view.foreach(_.closeNow())
            }
        }
    }

    // The type-17 debug entry's data as a slice payload (first match,
    // the pre-spool rule), or None when absent.
    private def embeddedPdbPayload(): Option[PayloadSource] = {
        image.debugHeader.flatMap { header =>
            header.entties.find(_.directory.`type` == io.spicelabs.cilantro.cil.ImageDebugType.embeddedPortablePdb).flatMap { entry =>
                val dir = entry.directory
                image.stream.flatMap { disposable =>
                    val fileSize = disposable.value.getChannel.size()
                    val raw = if (dir.pointerToRawData > 0) {
                        dir.pointerToRawData
                    }
                    else {
                        image.resolveVirtualAddress(dir.addressOfRawData).getOrElse(-1)
                    }
                    if (raw >= 0 && dir.sizeOfData > 0 && raw.toLong + dir.sizeOfData.toLong <= fileSize) {
                        val reader = BinaryStreamReader(disposable.value)
                        reader.moveTo(raw)
                        Some(reader.payloadSlice(dir.sizeOfData))
                    }
                    else {
                        None
                    }
                }
            }
        }
    }

    private val maxWin32EntriesPerDirectory = 1000000
    private val maxWin32TotalLeaves = 1000000

    private val win32ResourceTypeNames: Map[Int, String] = Map(
        1 -> "RT_CURSOR", 2 -> "RT_BITMAP", 3 -> "RT_ICON", 4 -> "RT_MENU",
        5 -> "RT_DIALOG", 6 -> "RT_STRING", 7 -> "RT_FONTDIR", 8 -> "RT_FONT",
        9 -> "RT_ACCELERATOR", 10 -> "RT_RCDATA", 11 -> "RT_MESSAGETABLE",
        12 -> "RT_GROUP_CURSOR", 14 -> "RT_GROUP_ICON", 16 -> "RT_VERSION",
        17 -> "RT_DLGINCLUDE", 19 -> "RT_PLUGPLAY", 20 -> "RT_VXD",
        21 -> "RT_ANICURSOR", 22 -> "RT_ANIICON", 23 -> "RT_HTML", 24 -> "RT_MANIFEST"
    )

    // Win32 resource tree walk (plan 13, C5-04; plan 2026_09_02,
    // phase B). The tree lives at the Resource data directory: three
    // directory levels (type / name / language) then data entries.
    // Every tree offset is an RVA resolved through the section map and
    // bounds-checked against the file; a hostile tree (bad offsets,
    // oversize data) fails cleanly with DataFormatException. Leaves
    // are exposed as zero-copy slice payloads with no byte budget
    // (D-10): the only payload rule is that the declared size lies
    // inside the file. Object guards: per-directory entries and total
    // leaves capped at 1,000,000; the directory visited set makes the
    // walk cycle-safe. Blobs are raw — cilantro never interprets ICON
    // / VERSION / MANIFEST contents.
    def readWin32Resources(): ArrayBuffer[Win32Resource] = {
        val resources = ArrayBuffer[Win32Resource]()
        image.win32Resources match {
            case None => resources
            case Some(dir) if dir.size <= 0 || dir.virtualAddress == 0 => resources
            case Some(dir) =>
                image.stream match {
                    case None => resources
                    case Some(disposable) =>
                        val fileSize = disposable.value.getChannel.size()
                        val reader = BinaryStreamReader(disposable.value)
                        // H4/ADR-0014 (D-10): a visited set on directory
                        // raw offsets — hostile directory-offset aliasing
                        // must not multiply the walk — plus the total-leaf
                        // object guard (1,000,000). Byte budgets are gone;
                        // leaf sizes are extent-checked only.
                        val visitedDirectories = scala.collection.mutable.HashSet[Int]()
                        var totalLeaves = 0L
                        // The .rsrc section quirk: the tree lives at the
                        // section's raw start; directory-entry targets are
                        // offsets relative to the tree's raw position, and
                        // data-entry/name offsets are relative to the
                        // header's resource directory RVA. Both mappings
                        // are bounds-checked against the file.
                        val section = image.sections.find(s => s.virtualAddress.toLong <= dir.virtualAddress.toLong && dir.virtualAddress.toLong < s.virtualAddress.toLong + s.sizeOfRawData.toLong)
                        val treeBaseRaw = section.map { s =>
                            val inSection = dir.virtualAddress - s.virtualAddress
                            if (inSection.toLong <= s.sizeOfRawData.toLong) {
                                s.pointerToRawData + inSection
                            }
                            else {
                                s.pointerToRawData
                            }
                        }.getOrElse(throw DataFormatException())
                        def dataOffsetRaw(offset: Int): Int = {
                            val raw = treeBaseRaw.toLong + (offset.toLong - dir.virtualAddress.toLong)
                            if (raw < 0 || raw >= fileSize) {
                                throw DataFormatException()
                            }
                            raw.toInt
                        }
                        def treeOffsetRaw(offset: Int): Int = {
                            val raw = treeBaseRaw.toLong + offset.toLong
                            if (raw < 0 || raw >= fileSize) {
                                throw DataFormatException()
                            }
                            raw.toInt
                        }
                        def readDirectoryHeader(raw: Int): (Int, Int) = {
                            if (raw < 0 || raw.toLong + 16 > fileSize) {
                                throw DataFormatException()
                            }
                            reader.moveTo(raw)
                            reader.readInt32() // Characteristics
                            reader.readInt32() // TimeDateStamp
                            reader.readUInt16() // MajorVersion
                            reader.readUInt16() // MinorVersion
                            val named = reader.readUInt16().toInt
                            val ids = reader.readUInt16().toInt
                            if (named.toLong + ids.toLong > maxWin32EntriesPerDirectory) {
                                throw DataFormatException()
                            }
                            (named, ids)
                        }
                        def readNameValue(nameValue: Int): (Int, Option[String]) = {
                            if ((nameValue & 0x80000000) != 0) {
                                val raw = dataOffsetRaw(nameValue & 0x7fffffff)
                                if (raw.toLong + 2 > fileSize) {
                                    throw DataFormatException()
                                }
                                reader.moveTo(raw)
                                val length = reader.readUInt16().toInt
                                if (length > 0x10000 || raw.toLong + 2 + length.toLong * 2 > fileSize) {
                                    throw DataFormatException()
                                }
                                val chars = Array.ofDim[Char](length)
                                var i = 0
                                while (i < length) {
                                    chars(i) = reader.readUInt16()
                                    i += 1
                                }
                                (-1, Some(String(chars)))
                            }
                            else {
                                (nameValue, None)
                            }
                        }
                        def readDataBlob(target: Int, typeId: Int, typeName: Option[String], nameId: Int, name: Option[String], language: Int): Unit = {
                            if ((target & 0x80000000) != 0) {
                                throw DataFormatException()
                            }
                            val dataRaw = treeOffsetRaw(target)
                            if (dataRaw.toLong + 16 > fileSize) {
                                throw DataFormatException()
                            }
                            reader.moveTo(dataRaw)
                            val offset = reader.readInt32()
                            val size = reader.readInt32()
                            if (size < 0) {
                                throw DataFormatException()
                            }
                            val blobRaw = dataOffsetRaw(offset)
                            if (blobRaw.toLong + size.toLong > fileSize) {
                                throw DataFormatException()
                            }
                            totalLeaves += 1
                            if (totalLeaves > maxWin32TotalLeaves) {
                                throw DataFormatException()
                            }
                            reader.moveTo(blobRaw)
                            resources.addOne(Win32Resource(typeId, typeName, nameId, name, language, reader.payloadSlice(size)))
                        }
                        val rootRaw = treeBaseRaw
                        val (rootNamed, rootIds) = readDirectoryHeader(rootRaw)
                        var t = 0L
                        val typeTotal = rootNamed.toLong + rootIds.toLong
                        while (t < typeTotal) {
                            val typeEntryRaw = rootRaw + 16 + (t * 8).toInt
                            if (typeEntryRaw.toLong + 8 > fileSize) {
                                throw DataFormatException()
                            }
                            reader.moveTo(typeEntryRaw)
                            val nameValue = reader.readInt32()
                            val typeTarget = reader.readInt32()
                            val (typeId, typeName) = readNameValue(nameValue)
                            val fullTypeName = typeName.orElse(win32ResourceTypeNames.get(typeId))
                            if ((typeTarget & 0x80000000) != 0) {
                                val nameRaw = treeOffsetRaw(typeTarget & 0x7fffffff)
                                if (visitedDirectories.add(nameRaw)) {
                                val (nameNamed, nameIds) = readDirectoryHeader(nameRaw)
                                var n = 0L
                                val nameTotal = nameNamed.toLong + nameIds.toLong
                                while (n < nameTotal) {
                                    val nameEntryRaw = nameRaw + 16 + (n * 8).toInt
                                    if (nameEntryRaw.toLong + 8 > fileSize) {
                                        throw DataFormatException()
                                    }
                                    reader.moveTo(nameEntryRaw)
                                    val nameNameValue = reader.readInt32()
                                    val nameTarget = reader.readInt32()
                                    val (nameId, name) = readNameValue(nameNameValue)
                                    if ((nameTarget & 0x80000000) != 0) {
                                        val langRaw = treeOffsetRaw(nameTarget & 0x7fffffff)
                                        if (visitedDirectories.add(langRaw)) {
                                        val (langNamed, langIds) = readDirectoryHeader(langRaw)
                                        var l = 0L
                                        val langTotal = langNamed.toLong + langIds.toLong
                                        while (l < langTotal) {
                                            val langEntryRaw = langRaw + 16 + (l * 8).toInt
                                            if (langEntryRaw.toLong + 8 > fileSize) {
                                                throw DataFormatException()
                                            }
                                            reader.moveTo(langEntryRaw)
                                            val langName = reader.readInt32()
                                            val langTarget = reader.readInt32()
                                            readDataBlob(langTarget, typeId, fullTypeName, nameId, name, langName)
                                            l += 1
                                        }
                                        }
                                    }
                                    else {
                                        readDataBlob(nameTarget, typeId, fullTypeName, nameId, name, 0)
                                    }
                                    n += 1
                                }
                                }
                            }
                            else {
                                readDataBlob(typeTarget, typeId, fullTypeName, -1, None, 0)
                            }
                            t += 1
                        }
                        resources
                }
        }
    }

    // WIN_CERTIFICATE table reader (plan 13, C5-03). Entries are 8-byte
    // aligned relative to the table start; each is dwLength(4),
    // wRevision(2), wCertificateType(2) + dwLength-8 blob bytes. The
    // table may live beyond the sections (the Authenticode overlay case),
    // where the directory's virtual address is the raw file offset.
    // Caps: entry count, dwLength >= 8, every read bounded by both the
    // directory size and the file size — a hostile file fails cleanly
    // with DataFormatException, never OOM, never a past-EOF read.
    def readCertificateEntries(): ArrayBuffer[CertificateEntry] = {
        val entries = ArrayBuffer[CertificateEntry]()
        image.securityDirectory match {
            case None => entries
            case Some(dir) if dir.size <= 0 || dir.virtualAddress == 0 => entries
            case Some(dir) =>
                image.stream match {
                    case None => entries
                    case Some(disposable) =>
                        val fileSize = disposable.value.getChannel.size()
                        // The Authenticode convention: the Security
                        // directory's virtual address is the raw FILE
                        // offset of the certificate table (the table is
                        // appended after the sections). Interpret it as a
                        // file offset first; only fall back to the
                        // section map for the exotic in-section layout.
                        val asFileOffset = dir.virtualAddress.toLong
                        val base = if (asFileOffset >= 0 && asFileOffset + dir.size.toLong <= fileSize) {
                            dir.virtualAddress
                        }
                        else {
                            image.resolveVirtualAddress(dir.virtualAddress)
                                .getOrElse(throw DataFormatException())
                        }
                        if (base.toLong < 0 || base.toLong + dir.size.toLong > fileSize) {
                            throw DataFormatException()
                        }
                        val reader = BinaryStreamReader(disposable.value)
                        reader.moveTo(base)
                        var remaining = dir.size.toLong
                        var count = 0
                        while (remaining >= 8) {
                            count += 1
                            if (count > maxCertificateEntries) {
                                throw DataFormatException()
                            }
                            val dwLength = reader.readInt32()
                            if (dwLength < 8 || dwLength.toLong > remaining) {
                                throw DataFormatException()
                            }
                            val revision = reader.readUInt16().toInt
                            val certificateType = reader.readUInt16().toInt
                            val payload = reader.payloadSlice(dwLength - 8)
                            entries.addOne(CertificateEntry(revision, certificateType, payload))
                            val consumed = (dwLength.toLong + 7L) & ~7L
                            remaining -= consumed
                            reader.moveTo(base + (dir.size.toLong - remaining).toInt)
                        }
                        entries
                }
        }
    }
    private def populateVersionAndFlags(name: AssemblyNameReference) = {
        val maj = readUInt16().toInt
        val min = readUInt16().toInt
        val rev = readUInt16().toInt
        val build = readUInt16().toInt
        name.version = CSVersion(maj, min, rev, build)
        name.attributes = readUInt32()

    }
    private def populateNameAndCulture(name: AssemblyNameReference) = {
        name.name = readString()
        name.culture = readString()
    
    }
    def readTypes() = {
        initializeTypeDefinitions()
        val mtypes = metadata._types
        val type_count = 0 // mtypes.length - metadata.nestedTypes.length
        val types = TypeDefinitionCollection(module, type_count)

        mtypes.foreach((`type`) => if (!isNested(`type`.attributes)) types.addOne(`type`))

        types
    
    }
    private def completeTypes() = ()

    private def initializeTypeDefinitions() = {
        if (metadata._types.length == 0) {
            val count = moveTo(Table.typeDef)
            metadata._types = Array.ofDim[TypeDefinition](count)
            for rid <- 1 to count do {
                readTypeDefinition(rid).foreach(t => metadata._types(rid - 1) = t)
            }
        }
        // Nested-type declaring-type resolution (initializeNestedTypes /
        // getNestedTypeDeclaringType) lands with the Phase 4 parity work.
        ()
            // TODO

    }
    def hasNestedTypes(`type`: TypeDefinition) = false
        // initializeNestedTypes()
        // tryGetNestedTypeMapping(`type`) match
        //     case Some(mapping) => mapping.length > 0
        //     case _ => false
    
    def readNestedTypes(`type`: TypeDefinition): Option[MemberDefinitionCollection[TypeDefinition]] = {
        initializeNestedTypes()
        `type`.token.flatMap(tok => metadata._nestedTypes.get(tok.RID)).map { mapping =>
            val nested_types = MemberDefinitionCollection[TypeDefinition](`type`, mapping.length)
            for i <- 0 until mapping.length do {
                getTypeDefinition(mapping(i)).foreach(nested_type => nested_types.addOne(nested_type))
            }
            nested_types
        }
    }
        //         nested_types
        //     case _ => MemberDefinitionCollection[TypeDefinition](`type`)
    
    private def initializeNestedTypes() = {
        if (metadata._nestedTypes.isEmpty) {
            val count = moveTo(Table.nestedClass)
            for rid <- 1 to count do {
                moveTo(Table.nestedClass, rid)
                val nested = readTableIndex(Table.typeDef)
                val enclosing = readTableIndex(Table.typeDef)
                metadata._nestedTypes.getOrElseUpdate(enclosing, ArrayBuffer[Int]()).addOne(nested)
            }
        }
    }
    private def addNestedMapping(declaring: Int, nested: Int) = { }
    private def initializeCustomAttributes(): Unit = {
        if (metadata._customAttributes.nonEmpty) {
            return ()
        
        }
        metadata._customAttributes = initializeRanges(
            Table.customAttribute, () => {
                val next = readMetadataToken(CodedIndex.hasCustomAttribute);
                readMetadataToken(CodedIndex.customAttributeType)
                readBlobIndex()
                next
            }
        )

    }
    def hasCustomAttributes(owner: CustomAttributeProvider) = {
        initializeCustomAttributes()
        val rangeOpt = metadata.tryGetCustomAttributeRanges(owner)
        rangeOpt match {
            case Some(ranges) => ranges.exists(r => rangesSize(r) > 0)
            case _ => false

        }
    }
    def readCustomAttributes(owner: CustomAttributeProvider): ArrayBuffer[CustomAttribute] = {
        initializeCustomAttributes()
        val custom_attributes = ArrayBuffer.empty[CustomAttribute]

        val rangeOpt = metadata.tryGetCustomAttributeRanges(owner)
        rangeOpt match {
            case Some(ranges) => {
                for {
                    rngs <- ranges
                    range <- rngs
                } do {
                    readCustomAttributeRange(range, custom_attributes)
                }
            }
            case _ => return custom_attributes

        // TODO
        // if (module.isWindowsMetadata)
        //     for custom_attribute <- custom_attributes do
        //         windowsRuntimeProjections.project(owner, custom_attributes, custom_attribute)        

        }
        custom_attributes
    
    }
    def readCustomAttributeRange(range: Range, custom_attributes: ArrayBuffer[CustomAttribute]): Unit = {
        if (!moveTo(Table.customAttribute, range.index)) {
            return ()
        
        }
        for i <- 0 until range.length do {
            readMetadataToken(CodedIndex.hasCustomAttribute)

            val constructor = lookupToken(readMetadataToken(CodedIndex.customAttributeType)).map(_.asInstanceOf[MethodReference]).getOrElse(throw OperationNotSupportedException())
            val signature = readBlobIndex()
            custom_attributes.addOne(CustomAttribute(signature, constructor))
        


            }
        }
    private def readType(rid: Int): Option[TypeDefinition] =  {
        if (!moveTo(Table.typeDef, rid)) {
            None
        }
        else {
            val attributes = readUInt32()
            val name = readString()
            val namespace = readString()
            val `type` = TypeDefinition(namespace, name, attributes)
            `type`.token = Some(MetadataToken(TokenType.typeDef, rid))
            `type`.scope = module
            `type`._module = Some(module)

            metadata.addTypeDefinition(`type`)

            this._context = Some(`type`)

            getTypeDefOrRef(readMetadataToken(CodedIndex.typeDefOrRef)) match {
                case Some(t) => `type`.baseType = t
                case None => ()
            }

            `type`.fields_range = Some(readListRange(rid, Table.typeDef, Table.field))
            `type`.methods_range = Some(readListRange(rid, Table.typeDef, Table.method))
            // PropertyMap/EventMap rows carry a Parent (TypeDef) index
            // before the list index; skip it before reading the range.
            // Tables with no rows for this type yield an empty range.
            `type`.properties_range = Some(readPropertiesRange(rid))
            `type`.events_range = Some(readEventsRange(rid))

            if (MetadataReader.isNested(attributes)) {
                getNestedTypeDeclaringType(`type`).foreach(t => `type`.declaringType = t)
            
            }
            Some(`type`)

        }
    }

    // PropertyMap/EventMap rows carry a Parent (TypeDef) index before
    // the list index; the row index is unrelated to the type RID, so the
    // table is scanned for the row whose parent matches.
    private def readPropertiesRange(type_rid: Int): io.spicelabs.cilantro.Range = {
        val count = moveTo(Table.propertyMap)
        boundary {
            for i <- 1 to count do {
                moveTo(Table.propertyMap, i)
                val parent = readTableIndex(Table.typeDef)
                if (parent == type_rid) {
                    break(readListRange(i, Table.propertyMap, Table.property))
                }
            }
            io.spicelabs.cilantro.Range(0, 0)
        }
    }
    private def readEventsRange(type_rid: Int): io.spicelabs.cilantro.Range = {
        val count = moveTo(Table.eventMap)
        boundary {
            for i <- 1 to count do {
                moveTo(Table.eventMap, i)
                val parent = readTableIndex(Table.typeDef)
                if (parent == type_rid) {
                    break(readListRange(i, Table.eventMap, Table.event))
                }
            }
            io.spicelabs.cilantro.Range(0, 0)
        }
    }

    private def getNestedTypeDeclaringType(`type`: TypeDefinition): Option[TypeDefinition] = {
        initializeNestedTypes()
        `type`.token.flatMap { tok =>
            metadata._nestedTypes.find { case (_, list) => list.contains(tok.RID) }
        }.flatMap { case (enclosing, _) => getTypeDefinition(enclosing) }
    }
    private def readListRange(current_index: Int, current: Table, target: Table) = {
        val list:io.spicelabs.cilantro.Range = new Range(0, 0)

        val start = readTableIndex(target)
        if (start == 0) {
            list
        }
        else {
            var next_index = 0
            val current_table = image.tableHeap.map(_(current))
            val target_table = image.tableHeap.map(_(target))

            if (current_index == current_table.map(_.length).getOrElse(0)) {
                next_index = target_table.map(_.length).getOrElse(0) + 1
            }
            else {
                val position = this.position
                this.position += (current_table.map(_.rowSize).getOrElse(0) - image.getTableIndexSize(target))
                next_index = readTableIndex(target)
                this.position = position
            
            }
            list.index = start
            list.length = next_index - start

            list

        }
    }
    def readTypeLayout(`type`: TypeDefinition) = {
        initializeTypeLayouts()
        val class_layout = Row2[Short, Int](0, 0)

        // TODO
        class_layout
    
    }
    private def initializeTypeLayouts() = { }

    def getTypeDefOrRef(token: MetadataToken) = {
        lookupToken(token).map(_.asInstanceOf[TypeReference])
    
    }
    def getTypeDefinition(rid: Int) = {
        initializeTypeDefinitions()

        // TODO
        metadata.getTypeDefinition(rid) match {
            case Some(t) => Some(t)
            case None =>
                readTypeDefinition(rid).map { `type` =>
                    if (module.isWindowsMetadata) {
                        WindowsRuntimeProjections.project(`type`)
                    }
                    `type`
                }
        }

    }
    private val maxTypeRecursionDepth = 128
    private var typeRecursionDepth = 0

    private def readTypeDefinition(rid: Int): Option[TypeDefinition] = {
        typeRecursionDepth += 1
        if (typeRecursionDepth > maxTypeRecursionDepth) {
            typeRecursionDepth -= 1
            throw DataFormatException()
        }
        if (!moveTo(Table.typeDef, rid)) {
            typeRecursionDepth -= 1
            None
        }
        else {
            val t = readType(rid)
            typeRecursionDepth -= 1
            t

        }
        }
    private def initializeTypeReferences() = {
        if (metadata._typeReferences.length > 0) {
            ()
        }
        else {
            metadata._typeReferences = Array.ofDim[TypeReference](image.getTableLength(Table.typeRef))

        }
    }
    def getTypeReference(scope: String, full_name: String): Option[TypeReference] = {
        initializeTypeReferences()

        val length = metadata._typeReferences.length

        var found: Option[TypeReference] = None
        boundary {
            for i <- 1 to length do { // intentionally inclusive
                getTypeReference(i).foreach { `type` =>
                    if (`type`.fullName == full_name) {
                        if (scope.length == 0) {
                            found = Some(`type`)
                            break()
                        }
                        if (`type`.scope.exists(_.name == scope)) {
                            found = Some(`type`)
                            break()
                        }
                    }
                }
            }
        }
        found

    }
    private def getTypeReference(rid: Int): Option[TypeReference] = {
        initializeTypeReferences()

        metadata.getTypeReference(rid) match {
            case Some(t) => Some(t)
            case None => readTypeReference(rid)
        }
    }
    private def readTypeReference(rid: Int): Option[TypeReference] = {
        if (!moveTo(Table.typeRef, rid)) {
            return None
        
        }
        var declaring_type: Option[TypeReference] = None
        var scope: Option[MetadataScope] = None

        val scope_token = readMetadataToken(CodedIndex.resolutionScope)

        val name = readString()
        val namespace = readString()
        val `type` = TypeReference(namespace, name, module)
        `type`.token = Some(MetadataToken(TokenType.typeRef, rid))

        metadata.addTypeReference(`type`)
        
        if (scope_token.tokenType == TokenType.typeRef) {
            if (scope_token.RID != rid) {
                declaring_type = getTypeDefOrRef(scope_token)

                scope = declaring_type.flatMap(_.scope).orElse(Some(module))
            }
            else {
                scope = Some(module)
            }
        }
        else {
            scope = getTypeReferenceScope(scope_token)
        }
        scope.foreach(sc => `type`.scope = sc)
        declaring_type.foreach(t => `type`.declaringType = t)

        MetadataSystem.tryProcessPrimitiveTypeReference(`type`)

        // TODO
        // if (`type`.module.isWindowsMetadata())
        //     windowsRuntimeProjections.project(`type`)

        Some(`type`)

    }
    def getTypeReferenceScope(scope: MetadataToken): Option[MetadataScope] = {
        if (scope.tokenType == TokenType.module) {
            Some(module)
        }
        else {
            var scopes: Array[MetadataScope] = scope.tokenType match {
                case TokenType.assemblyRef =>
                    initializeAssemblyReferences()
                    metadata._assemblyReferences.map((ar)=> ar.asInstanceOf[MetadataScope])
                case TokenType.moduleRef =>
                    initializeModuleReferences()
                    metadata._moduleReferences.map((ar) => ar.asInstanceOf[MetadataScope])
                case _ => throw OperationNotSupportedException()

            }
            var index = scope.RID - 1
            if (index < 0 || index >= scopes.length) {
                None
            }
            else {
                Option(scopes(index))

            }
        }
    }
    def getTypeReferences(): Iterable[TypeReference] = {
        initializeTypeReferences()

        val length = image.getTableLength(Table.typeRef)
        val type_references = Array.ofDim[TypeReference](length)

        for i <- 1 to length do { // intentionally inclusive
            getTypeReference(i).foreach(tr => type_references(i - 1) = tr)
        
        }
        type_references
    
    }
    private def getTypeSpecification(rid: Int): Option[TypeReference] = {
        if (!moveTo(Table.typeSpec, rid)) {
            return None
        }
        val reader = readSignature(readBlobIndex())
        val `type` = reader.readTypeSignature()
        `type`.token match {
            case Some(tok) if tok.RID == 0 => `type`.token = Some(MetadataToken(TokenType.typeSpec, rid))
            case _ => ()
        }
        Some(`type`)

    }
    def readSignaturePublic(signature: Int) = readSignature(signature)

    private def readSignature(signature: Int) = {
        SignatureReader(signature, this)
    
    }
    def hasInterfaces(`type`: TypeDefinition) = {
        false
        // TODO
        // initializeInterfaces()
        // tryGetInterfaceMapping(`type`) match
        //     case Some(mapping) => true
        //     case None => false
        
    }
    def readInterfaces(`type`: TypeDefinition): Option[InterfaceImplementationCollection] = {
        // The InterfaceImpl table's first column is the Class (TypeDef);
        // a type's rows form a contiguous run (Cecil reads it as a range).
        `type`.token.map { tok =>
            val interfaces = InterfaceImplementationCollection(`type`)
            this._context = Some(`type`)
            val count = moveTo(Table.interfaceImpl)
            var rid = 1
            var done = false
            while (!done && rid <= count) {
                moveTo(Table.interfaceImpl, rid)
                val clazz = readTableIndex(Table.typeDef)
                if (clazz < tok.RID) {
                    rid += 1
                } else if (clazz == tok.RID) {
                    val interface_type = getTypeDefOrRef(readMetadataToken(CodedIndex.typeDefOrRef))
                        .getOrElse(module.typeSystem.`object`)
                    interfaces.addOne(InterfaceImplementation(interface_type, MetadataToken(TokenType.interfaceImpl, rid)))
                    rid += 1
                } else {
                    done = true
                }
            }
            interfaces
        }
    }


    private def initializeInterfaces() = { } // TODO

    private def addInterfaceMapping(`type`: Int, interface: Row2[Int, MetadataToken]) = { } // TODO

    def readFields(`type`: TypeDefinition): Option[ArrayBuffer[FieldDefinition]] = {
        `type`.fields_range.map { range =>
            if (metadata._fields.length == 0) {
                metadata._fields = Array.ofDim[FieldDefinition](moveTo(Table.field))
            }
            val fields = ArrayBuffer[FieldDefinition]()
            this._context = Some(`type`)
            for i <- 0 until range.length do {
                moveTo(Table.field, range.index + i)
                val field = readField(range.index + i, fields)
                field.declaringType = `type`
            }
            fields
        }
    }

    private def readField(field_rid: Int, fields: ArrayBuffer[FieldDefinition]): FieldDefinition = {
        val attributes = readUInt16()
        val name = readString()
        val signature = readBlobIndex()
        val field = FieldDefinition(name, attributes.toChar, readFieldType(signature).getOrElse(module.typeSystem.void))
        field.token = Some(MetadataToken(TokenType.field, field_rid))
        metadata.addFieldDefinition(field)
        fields.addOne(field)
        field
    }

    private def initializeFields() = { }

    private def readFieldType(signature: Int): Option[TypeReference] = {
        val reader = readSignature(signature)

        val field_sig: Byte = 0x6

        if (reader.readByte() != field_sig) {
            None
        } else {
            Some(reader.readTypeSignature())
        }

    }
    def readFieldRVA(field: FieldDefinition) = 0 // TODO

    def getFieldInitializeValue(size: Int, rva: Int) = {
        val bytes = image.getReaderAt(rva, size, (s, reader) => reader.readBytes(s))
        bytes.getOrElse(Array.emptyByteArray)

    }
    private def initializeFieldRVAs() = { } // TODO

    def readFieldLayout(field: FieldDefinition) = 0 // TODO

    private def initializeFieldLayouts() = { } // TODO

    def hasEvents(`type`: TypeDefinition) = {
        `type`.events_range.exists(_.length > 0)
    }

    def readEvents(`type`: TypeDefinition): Option[ArrayBuffer[EventDefinition]] = {
        `type`.events_range.map { range =>
            if (metadata._event_definitions.length == 0) {
                metadata._event_definitions = Array.ofDim[EventDefinition](moveTo(Table.event))
            }
            this._context = Some(`type`)
            val events = ArrayBuffer[EventDefinition]()
            for i <- 0 until range.length do {
                moveTo(Table.event, range.index + i)
                val event = readEvent(range.index + i, events)
                event.declaringType = `type`
            }
            events
        }
    }

    def readEvent(event_rid: Int, events: ArrayBuffer[EventDefinition]): EventDefinition = {
        val attributes = readUInt16()
        val name = readString()
        val event_type = getTypeDefOrRef(readMetadataToken(CodedIndex.typeDefOrRef))
            .getOrElse(module.typeSystem.`object`)
        val event = EventDefinition(name, attributes.toChar, event_type)
        event.token = Some(MetadataToken(TokenType.event, event_rid))
        metadata.addEventDefinition(event)
        events.addOne(event)
        event
    }

    private def initializeEvents() = { }

    def hasProperties(`type`: TypeDefinition) = {
        `type`.properties_range.exists(_.length > 0)
    }

    def readProperties(`type`: TypeDefinition): Option[ArrayBuffer[PropertyDefinition]] = {
        `type`.properties_range.map { range =>
            if (metadata._property_definitions.length == 0) {
                metadata._property_definitions = Array.ofDim[PropertyDefinition](moveTo(Table.property))
            }
            this._context = Some(`type`)
            val properties = ArrayBuffer[PropertyDefinition]()
            for i <- 0 until range.length do {
                moveTo(Table.property, range.index + i)
                readProperty(range.index + i, properties).foreach(_.declaringType = `type`)
            }
            properties
        }
    }

    private def readProperty(property_rid: Int, properties: ArrayBuffer[PropertyDefinition]): Option[PropertyDefinition] = {
        val attributes = readUInt16()
        val name = readString()
        val signature = readBlobIndex()

        val reader = readSignature(signature)
        val property_signature = 0x8

        // Cecil skips rows whose signature does not carry the property bit.
        if ((reader.readByte().toInt & property_signature) == 0) {
            None
        } else {
            reader.readCompressedUInt32() // parameter count
            val property = PropertyDefinition(name, attributes.toChar, reader.readTypeSignature())
            property.token = Some(MetadataToken(TokenType.property, property_rid))
            metadata.addPropertyDefinition(property)
            properties.addOne(property)
            Some(property)
        }
    }

    private def initializeProperties() = { } // TODO

    private def readMethodSemantics(method: MethodDefinition) = { } // TODO

    private def initializeMethodSemantics() = { } // TODO

    // TODO
    // def readMethods(property: PropertyDefinition) =
    //     readAllSemantics(property.declaringType)

    def readMethods(event: EventDefinition) = { }
        // readAllSemantics(event)

    def readAllSemantics(method: MethodDefinition): Unit = { }
        // readAllSemantics(method._declaringType)

    def readAllSemantics(`type`: TypeDefinition): Unit = { }
        // val methods = `type`.methods
        // methods.foreach((m) => {
        //     if (m._sem_attrs_ready)
        //         m._sem_attrs = readMethodSemantics(m)
        //         m._sem_attrs_ready = true
        // })

    def readMethods(`type`: TypeDefinition): Option[ArrayBuffer[MethodDefinition]] = {
        `type`.methods_range.map { range =>
            if (metadata._methods.length == 0) {
                metadata._methods = Array.ofDim[MethodDefinition](moveTo(Table.method))
            }
            val methods = ArrayBuffer[MethodDefinition]()
            for i <- 0 until range.length do {
                moveTo(Table.method, range.index + i)
                readMethod(range.index + i, methods, `type`)
            }
            methods
        }
    }

    private def readPointers[TMember <: MemberDefinition](ptr: Table, table: Table, range: Range,
        members: ArrayBuffer[TMember], reader: (Int, ArrayBuffer[TMember]) => Unit) =
        for i <- 0 until range.length do {
            moveTo(ptr, range.index + i)
            val rid = readTableIndex(table)
            moveTo(table, rid)

            reader(rid, members)
    
        }
    private def initializeMethods() = { } // TODO

    private def readMethod(method_rid: Int, methods: ArrayBuffer[MethodDefinition], declaringType: TypeDefinition): MethodDefinition = {
        val rva = readUInt32()
        val impl_attrs = readUInt16()
        val attrs = readUInt16()
        val name = readString()
        val signature = readBlobIndex()

        val method = MethodDefinition(name, attrs.toChar, module.typeSystem.void)
        method.implAttributes = impl_attrs.toChar
        method._rva = rva
        method.token = Some(MetadataToken(TokenType.method, method_rid))
        method.parameter_range = Some(readListRange(method_rid, Table.method, Table.param))
        method.declaringType = declaringType
        metadata.addMethodDefinition(method)
        methods.addOne(method)

        // The signature and parameters are read eagerly with the method
        // as the generic context (var/mvar resolution).
        val savedContext = this._context
        this._context = Some(method)
        readMethodSignature(signature, method)
        this._context = savedContext
        readParameters(method, method.parameter_range.getOrElse(io.spicelabs.cilantro.Range(1, 0)))
        readSemantics(method)
        method
    }

    private def readParameters(method: MethodDefinition, param_range: Range) = {
        // Method pointer tables (paramPtr) are a legacy CLR 1.x shape;
        // modern assemblies index the Param table directly.
        for i <- 0 until param_range.length do {
            moveTo(Table.param, param_range.index + i)
            readParameter(param_range.index + i, method)
        }
    }

    private def readParameterPointers(method: MethodDefinition, range: Range) = { }

    private def readParameter(param_rid: Int, method: MethodDefinition) = {
        val attributes = readUInt16()
        val sequence = readUInt16()
        val name = readString()

        val parameter = if (sequence == 0) method.methodReturnType.parameter else method.parameters(sequence - 1)
        parameter.metadataToken = MetadataToken(TokenType.param, param_rid)
        parameter.name = name
        parameter.attributes = attributes.toChar
    }

    def readMethodSignature(signature: Int, method: MethodSignature) = {
        val reader = readSignature(signature)
        reader.readMethodSignature(method)

    }
    def readLocalVariables(sigToken: Int): Option[ArrayBuffer[VariableDefinition]] = {
        // The fat header's local-signature field is a StandAloneSig RID
        // (0x11 << 24 | rid); the signature blob lives in that table row.
        val rid = sigToken & 0x00ffffff
        if (moveTo(Table.standAloneSig, rid)) {
            readVariables(readBlobIndex())
        } else {
            None
        }
    }
    def readVariables(signature: Int): Option[ArrayBuffer[VariableDefinition]] = {
        val reader = readSignature(signature)
        val local_sig: Byte = 0x7

        if (reader.readByte() != local_sig) {
            None
        } else {
            val count = reader.readCompressedUInt32()
            val variables = ArrayBuffer[VariableDefinition]()
            for i <- 0 until count do {
                variables.addOne(VariableDefinition(reader.readTypeSignature()))
            }
            Some(variables)
        }

    }
    private def initializeSemantics() = {
        if (metadata._semantics.isEmpty) {
            val count = moveTo(Table.methodSemantics)
            for rid <- 1 to count do {
                moveTo(Table.methodSemantics, rid)
                val semantics = readUInt16()
                val method_rid = readTableIndex(Table.method)
                val association = readMetadataToken(CodedIndex.hasSemantics)
                metadata._semantics.update(method_rid, (semantics.toChar, association))
            }
        }
    }
    def readSemantics(method: MethodDefinition): Unit = {
        initializeSemantics()
        method.token.flatMap(tok => metadata._semantics.get(tok.RID)).foreach { case (semantics, association) =>
            method._sem_attrs = semantics
            method._sem_attrs_ready = true
            val value = semantics.toInt
            if ((value & MethodSemanticsAttributes.setter.value) != 0) {
                getPropertyDefinition(association.RID).foreach(_.setMethod = method)
            }
            if ((value & MethodSemanticsAttributes.getter.value) != 0) {
                getPropertyDefinition(association.RID).foreach(_.getMethod = method)
            }
            if ((value & MethodSemanticsAttributes.addOn.value) != 0) {
                getEventDefinition(association.RID).foreach(_.addMethod = method)
            }
            if ((value & MethodSemanticsAttributes.removeOn.value) != 0) {
                getEventDefinition(association.RID).foreach(_.removeMethod = method)
            }
            if ((value & MethodSemanticsAttributes.fire.value) != 0) {
                getEventDefinition(association.RID).foreach(_.invokeMethod = method)
            }
        }
    }

    // def readPInvokeInfo(method: MethodDefinition): PInvokeInfo = null // TODO

    private def initializePInvokes() = { } // TODO

    def hasGenericParameters(provider: GenericParameterProvider) = {
        initializeGenericParameters()
        false // TODO
    
    }
    private def initializeGenericParameters() = {
        if (metadata._genericParameters.isEmpty) {
            val count = moveTo(Table.genericParam)
            var currentOwner = -1
            var start = -1
            var length = 0
            val grouped = HashMap[Int, ArrayBuffer[io.spicelabs.cilantro.Range]]()
            def flush(): Unit = {
                if (currentOwner != -1) {
                    grouped.getOrElseUpdate(currentOwner, ArrayBuffer[io.spicelabs.cilantro.Range]()).addOne(io.spicelabs.cilantro.Range(start, length))
                }
            }
            for rid <- 1 to count do {
                moveTo(Table.genericParam, rid)
                readUInt16() // number
                readUInt16() // flags
                val owner = readMetadataToken(CodedIndex.typeOrMethodDef)
                if (owner.token != currentOwner) {
                    flush()
                    currentOwner = owner.token
                    start = rid
                    length = 0
                }
                length += 1
            }
            flush()
            grouped.foreach { case (owner, ranges) =>
                metadata._genericParameters.update(MetadataToken(owner), ranges.toArray)
            }
        }
    }

    def readGenericParameters(provider: GenericParameterProvider): ArrayBuffer[GenericParameter] = {
        initializeGenericParameters()
        val collection = GenericParameterCollection(provider)
        provider.metadataToken.flatMap(tok => metadata._genericParameters.get(tok))
            .getOrElse(Array.empty[io.spicelabs.cilantro.Range])
            .foreach(range => readGenericParametersRange(range, provider, collection))
        collection
    }

    private def readGenericParametersRange(range: Range, provider: GenericParameterProvider, generic_parameters: GenericParameterCollection) = {
        for i <- 0 until range.length do {
            moveTo(Table.genericParam, range.index + i)
            readGenericParameter(range.index + i, provider, generic_parameters)
        }
    }

    private def readGenericParameter(gp_rid: Int, provider: GenericParameterProvider, generic_parameters: GenericParameterCollection) = {
        val number = readUInt16()
        val attributes = readUInt16()
        readMetadataToken(CodedIndex.typeOrMethodDef) // owner
        val name = readString()


        val parameter = GenericParameter(name, Some(provider))

        parameter._position = number
        parameter.attributes_(attributes.toChar)
        parameter.token = Some(MetadataToken(TokenType.genericParam, gp_rid))
        generic_parameters.addOne(parameter)
    }

    private def initializeRanges(table: Table, get_next: () => MetadataToken): HashMap[MetadataToken, ArrayBuffer[io.spicelabs.cilantro.Range]] = {
        val length = moveTo(table)
        val ranges = HashMap[MetadataToken, ArrayBuffer[io.spicelabs.cilantro.Range]]()

        if (length == 0) {
            return ranges

        }
        var owner = MetadataToken.zero
        var range = new Range(1, 0)

        for i <- 1 to length do { // yes, to and not until
            val next = get_next()
            if (i == 1) {
                owner = next
                range = new Range(range.index, range.length + 1)
            }
            else if (next != owner) {
                addRange(ranges, owner, range)
                range = new Range(i, 1)
                owner = next
            }
            else {
                range = new Range(range.index, range.length + 1)
        
            }
        }
        addRange(ranges, owner, range)
        ranges

    }
    def hasGenericConstraints(generic_parameter: GenericParameter) = false // TODO

    private def initializeGenericConstraints() = {
        if (metadata._genericConstraints.isEmpty) {
            val count = moveTo(Table.genericParamConstraint)
            var currentOwner = -1
            val grouped = HashMap[Int, ArrayBuffer[Row2[Int, MetadataToken]]]()
            for rid <- 1 to count do {
                moveTo(Table.genericParamConstraint, rid)
                val owner = readTableIndex(Table.genericParam)
                val constraint = readMetadataToken(CodedIndex.typeDefOrRef)
                grouped.getOrElseUpdate(owner, ArrayBuffer[Row2[Int, MetadataToken]]())
                    .addOne(Row2(rid, constraint))
            }
            grouped.foreach { case (owner, rows) =>
                metadata._genericConstraints.update(owner, rows)
            }
        }
    }

    def readGenericConstraints(generic_parameter: GenericParameter): Option[GenericParameterConstraintCollection] = {
        initializeGenericConstraints()
        val collection = GenericParameterConstraintCollection(generic_parameter)
        generic_parameter.owner.foreach { owner =>
            this._context = Some(owner.asInstanceOf[GenericContext])
        }
        generic_parameter.token.flatMap(tok => metadata._genericConstraints.get(tok.RID))
            .getOrElse(ArrayBuffer[Row2[Int, MetadataToken]]())
            .foreach { row =>
                val constraint = GenericParameterConstraint(
                    getTypeDefOrRef(row.col2).getOrElse(module.typeSystem.`object`),
                    Some(MetadataToken(TokenType.genericParamConstraint, row.col1))
                )
                constraint._generic_parameter = Some(generic_parameter)
                collection.addOne(constraint)
            }
        Some(collection)
    }

    private def addGenericConstraintMapping(generic_parameter: Int, constraint: Row2[Int, MetadataToken]) = { } // TODO

    def hasOverrides(method: MethodDefinition) = false // TODO

    def readOverrides(method: MethodDefinition): ArrayBuffer[MethodReference] = ArrayBuffer.empty[MethodReference] // TODO

    private def initializeOverrides() = { } // TODO

    private def addOverrideMapping(method_rid: Int, `override`: MetadataToken) = { } // TODO

    // def readMethodBody(method: MethodDefinition): MethodBody = null // TODO
    
    def readCodeSize(method: MethodDefinition): Int = 0

    // def readCallSize(token: MetadataToken): CallSite = null // TODO

    // def readVariables(local_var_token: MetadataToken, method: MethodDefinition): VariableDefinitionCollection = null // TODO

    def lookupToken(token: MetadataToken): Option[MetadataTokenProvider] = {
        var rid = token.RID
        if (rid == 0) {
            return None

        }
        metadata_reader match {
            case Some(mr) => return mr.lookupToken(token)
            case None => ()
        }

        val position = this.position
        val context = this._context

        val element: Option[MetadataTokenProvider] = token.tokenType match {
            case TokenType.typeDef => getTypeDefinition(rid)
            case TokenType.typeRef => getTypeReference(rid)
            case TokenType.typeSpec => getTypeSpecification(rid)
            case TokenType.field => getFieldDefinition(rid)
            case TokenType.method => getMethodDefinition(rid)
            case TokenType.memberRef => getMemberReference(rid)
            case TokenType.methodSpec => getMethodSpecification(rid)
            case _ => None

        }
        this.position = position
        this._context = context
        element

    }
    def getFieldDefinition(rid: Int): Option[FieldDefinition] = {
        initializeTypeDefinitions()
        metadata.getFieldDefinition(rid) match {
            case Some(field) => Some(field)
            case None => lookupField(rid)
        }
    }
    private def lookupField(rid: Int): Option[FieldDefinition] = {
        metadata.getFieldDeclaringType(rid) match {
            case None => None
            case Some(t) =>
                mixinRead(t.fields)
                metadata.getFieldDefinition(rid)
        }
    }
    def getMethodDefinition(rid: Int): Option[MethodDefinition] = {
        initializeTypeDefinitions()
        metadata.getMethodDefinition(rid) match {
            case Some(method) => Some(method)
            case None => lookupMethod(rid)
        }
    }
    private def lookupMethod(rid: Int): Option[MethodDefinition] = {
        metadata.getMethodDeclaringType(rid) match {
            case None => None
            case Some(t) =>
                mixinRead(t.methods)
                metadata.getMethodDefinition(rid)
        }
    }
    def getPropertyDefinition(rid: Int): Option[PropertyDefinition] = {
        initializeTypeDefinitions()
        metadata.getPropertyDefinition(rid) match {
            case Some(p) => Some(p)
            case None => lookupProperty(rid)
        }
    }
    private def lookupProperty(rid: Int): Option[PropertyDefinition] = {
        metadata.getPropertyDeclaringType(rid) match {
            case None => None
            case Some(t) =>
                mixinRead(t.properties)
                metadata.getPropertyDefinition(rid)
        }
    }
    def getEventDefinition(rid: Int): Option[EventDefinition] = {
        initializeTypeDefinitions()
        metadata.getEventDefinition(rid) match {
            case Some(e) => Some(e)
            case None => lookupEvent(rid)
        }
    }
    private def lookupEvent(rid: Int): Option[EventDefinition] = {
        metadata.getEventDeclaringType(rid) match {
            case None => None
            case Some(t) =>
                mixinRead(t.events)
                metadata.getEventDefinition(rid)
        }
    }
    private def getMethodSpecification(rid: Int): Option[MethodSpecification] = {
        if (!moveTo(Table.methodSpec, rid)) {
            return None
        
        }
        for {
            token_provider <- lookupToken(readMetadataToken(CodedIndex.methodDefOrRef))
            element_method = token_provider.asInstanceOf[MethodReference]
        } yield {
            val signature = readBlobIndex()

            val method_spec = readMethodSpecSignature(signature, element_method)
            method_spec.token = Some(MetadataToken(TokenType.methodSpec, rid))
            method_spec
        }

    }
    private def readMethodSpecSignature(signature: Int, method: MethodReference): MethodSpecification = {
        val reader = readSignature(signature)
        val methodspec_sig = 0x0a.toByte
        val call_conv = reader.readByte()

        if (call_conv != methodspec_sig) {
            throw OperationNotSupportedException()
        
        }
        val arity = reader.readCompressedUInt32()
        val instance = GenericInstanceMethod(method, arity.toInt & 0xff)

        reader.readGenericInstanceSignature(method, instance, arity)

        instance

    }
    private def getMemberReference(rid: Int): Option[MemberReference] = {
        initializeMemberReferences()

        metadata.getMemberReference(rid) match {
            case Some(member) => Some(member)
            case None =>
                readMemberReference(rid).map { member =>
                    if (!member.containsGenericParameter) {
                        metadata.addMemberReference(member)
                    }
                    member
                }
        }
    }
    private def readMemberReference(rid: Int): Option[MemberReference] = {
        if (!moveTo(Table.memberRef, rid)) {
            return None
        
        }
        val token = readMetadataToken(CodedIndex.memberRefParent)
        val name = readString()
        val signature = readBlobIndex()

        val member = token.tokenType match {
            case TokenType.typeDef | TokenType.typeRef | TokenType.typeSpec =>
                readTypeMemberReference(token, name, signature)
            case TokenType.method =>
                readMethodMemberReference(token, name, signature)
            case _ => throw OperationNotSupportedException()
        
        }
        member.map { m =>
            m.token = Some(MetadataToken(TokenType.memberRef, rid))
            m
        }
    
    }
    private def readTypeMemberReference(`type`: MetadataToken, name: String, signature: Int): Option[MemberReference] = {
        getTypeDefOrRef(`type`).flatMap { declaring_type =>
            if (!declaring_type.isArray) {
                this._context = Some(declaring_type)

            }
            val member = readMemberReferenceSignature(signature, declaring_type)
            member.name = name

            Option(member)
        }

    }
    private def readMemberReferenceSignature(signature: Int, declaring_type: TypeReference): MemberReference = {
        val reader = readSignature(signature)

        val field_sig:Byte = 0x6

        if (reader.buffer(reader.position) == field_sig) {
            reader.position += 1
            val field = FieldReference()
            field.declaringType = declaring_type
            field.fieldType = reader.readTypeSignature()
            field
        }
        else {
            val method = MethodReference()
            method.declaringType = declaring_type
            reader.readMethodSignature(method)
            method

            }
        }
    private def readMethodMemberReference(token: MetadataToken, name: String, signature: Int): Option[MemberReference] = {
        getMethodDefinition(token.RID).flatMap { method =>
            this._context = Some(method)

            val member = readMemberReferenceSignature(signature, method.declaringType.getOrElse(throw OperationNotSupportedException()))
            member.name = name

            Option(member)
        }

    }
    private def initializeMemberReferences(): Unit = {
        if (metadata._memberReferences.length > 0) {
            return ()

        }
        metadata._memberReferences = Array.ofDim[MemberReference](image.getTableLength(Table.memberRef))

    }
    def getMemberReferences(): Iterable[MemberReference] = {
        initializeMemberReferences()
        val length = image.getTableLength(Table.memberRef)

        val type_system = module.typeSystem

        val context = MethodDefinition("", MethodAttributes.static.value, type_system.void)
        context.declaringType = TypeDefinition("", "", TypeAttributes.public.value)

        val member_references = Array.ofDim[MemberReference](length)
        for i <- 1 to length do {
            this._context = Some(context)
            getMemberReference(i).foreach(mr => member_references(i - 1) = mr)
        }
        member_references

    }
    private def initializeConstants(): Unit = {
        if (metadata._constants.size > 0) {
            return ()

        }
        val length = moveTo(Table.constant)

        metadata._constants = HashMap[MetadataToken, Row2[ElementType, Int]]()
        val constants = metadata._constants

        for i <- 1 to length do {
            val `type` = ElementType.fromOrdinalValue(readUInt16().toByte)
            val owner = readMetadataToken(CodedIndex.hasConstant)
            val signature = readBlobIndex()
            constants.addOne(owner, Row2[ElementType, Int](`type`, signature))

        }
    }
    def readConstantSignature(token: MetadataToken): Option[TypeReference] = {
        if (token.tokenType != TokenType.signature) {
            throw OperationNotSupportedException()
        
        }
        if (token.RID == 0) {
            return None
        
        }
        if (!moveTo(Table.standAloneSig, token.RID)) {
            return None
        
        }
        readFieldType(readBlobIndex())

    }
    def readConstant(owner: ConstantProvider): Any = {
        initializeConstants()

        owner.metadataToken.flatMap(tok => metadata._constants.get(tok)) match {
            case Some(row) =>
                owner.metadataToken.foreach(tok => metadata._constants.remove(tok))
                readConstantValue(row.col1, row.col2)
            case None => noValue
        

            }
        }
    private def readConstantValue(etype: ElementType, signature: Int): Any = {
        etype match {
            case ElementType.`class` | ElementType.`object` => CilNullConstant
            case ElementType.string => readConstantString(signature)
            case _ => readConstantPrimitive(etype, signature)


            }
        }
    private def readConstantString(signature: Int) = {
        val (blob, index, count) = getBlobView(signature)
        val actualCount = if ((count & 1) == 1) then count - 1 else count

        String(blob, index, actualCount, "UTF-16LE")

    }
    private def readConstantPrimitive(`type`: ElementType, signature: Int): Any = {
        val reader = readSignature(signature)
        reader.readConstantSignature(`type`)


    }
    def getCustomAttributes(): Iterable[CustomAttribute] = {
        initializeTypeDefinitions()
        val length = image.tableHeap.map(_(Table.customAttribute).length).getOrElse(0)
        val custom_attributes = ArrayBuffer[CustomAttribute]()
        readCustomAttributeRange(new Range(1, length), custom_attributes)
        custom_attributes

    }
    def readCustomAttributeBlob(signature: Int) = {
        readBlob(signature)

    
    }
    def readCustomAttributesSignature(attribute: CustomAttribute): Unit = {
        val reader = readSignature(attribute._signature)
        if (!reader.canReadMore()) {
            return ()
        
        }
        if (reader.readUInt16() != 0x0001) {
            throw OperationNotSupportedException()
        
        }
        val constructor = attribute.constructor
        if (constructor.hasParameters) {
            reader.readCustomAttributeConstructorArguments(attribute, constructor.parameters)
        
        }
        if (!reader.canReadMore()) {
            return ()
        
        }
        val named = reader.readUInt16()

        if (named == 0) {
            return ()
        
        }
        val (fields, props) = reader.readCustomArgumentAttributeNamedArguments(named)
        fields.foreach { fields =>
            attribute._fields match {
                case None => attribute._fields = Some(fields)
                case Some(f) => f.addAll(fields)
            }
        }
        props.foreach { props =>
            attribute._properties match {
                case None => attribute._properties = Some(props)
                case Some(p) => p.addAll(props)
            }
        }
    }
    private def initializeMarshalInfos() = { } // TODO

    // def hasMarshalInfo(owner: MarshalInfoProvider): Boolean = false // TODO

    // def readMarshalInfo(owner: MarshalInfoProvider): MarshalInfo = null

    private def initializeSecurityDeclarations() = { }

    def hasSecurityDeclarations(owner: SecurityDeclarationProvider): Boolean = false // TODO

    def readSecurityDeclarations(owner: SecurityDeclarationProvider): ArrayBuffer[SecurityDeclaration] = ArrayBuffer.empty[SecurityDeclaration] // TODO

    private def readSecurityDeclarationRange(range: Range, security_declaration: ArrayBuffer[SecurityDeclaration]) = { } // TODO

    def readSecurityDeclarationBlob(signature: Int): Array[Byte] = {
        readBlob(signature)

    }
    def readSecurityDeclarationSignature(declaration: SecurityDeclaration) = { } // TODO

    private def readXmlSecurityDeclaration(signature: Int, declaration: SecurityDeclaration) = { } // TODO

    def readExportedTypes() = {
        val length = moveTo(Table.exportedType)
        if (length == 0) {
            ArrayBuffer[ExportedType]()
        }
        else {
            val exported_types = ArrayBuffer[ExportedType]()

            for i <- 1 to length do { // yes, to is intentional
                val attributes = readUInt32()
                val identifier = readUInt32()
                val name = readString()
                val namespace = readString()
                val implementation = readMetadataToken(CodedIndex.implementation)

                var declaring_type: Option[ExportedType] = None
                var scope: Option[MetadataScope] = None

                implementation.tokenType match {
                    case TokenType.assemblyRef | TokenType.file =>
                        scope = getExportedTypeScope(implementation)
                    case TokenType.exportedType =>
                        // FIXME: if the table is not properly sorted
                        declaring_type = Some(exported_types(implementation.RID - 1))
                    case _ => { }

                }
                val exported_type = ExportedType(namespace, name, module, scope.getOrElse(throw OperationNotSupportedException()))
                exported_type.attributes = attributes
                exported_type.identifier = identifier
                declaring_type.foreach(exported_type.declaringType = _)
                exported_type.metadataToken = MetadataToken(TokenType.exportedType, i)

                exported_types.addOne(exported_type)
            
            }
            exported_types

        }
    }
    def getExportedTypeScope(token: MetadataToken) = {
        val position = this.position
        val scope = token.tokenType match {
            case TokenType.assemblyRef =>
                initializeAssemblyReferences()
                metadata.getAssemblyNameReference(token.RID)
            case TokenType.file =>
                initializeModuleReferences()
                getModuleReferenceFromFile(token)
            case _ => throw OperationNotSupportedException()
        
        }
        this.position = position
        scope

    }
    def getModuleReferenceFromFile(token: MetadataToken): Option[ModuleReference] = {
        if (!moveTo(Table.file, token.RID)) {
            None
        }
        else {
            readUInt32()
            val file_name = readString()
            val modules = module.moduleReferences

            val reference = modules.find((m) => m.name == file_name) match {
                case Some(ref) => ref
                case None =>
                    val newRef = ModuleReference(file_name)
                    modules.addOne(newRef)
                    newRef
            }
            Some(reference)
    
            

        }
    }
}

object MetadataReader {
    def isNested(attributes: TypeAttributes): Boolean = {
        isNested(attributes.value)
    }
    def isNested (attributes: Int): Boolean =  {
        attributes & TypeAttributes.visibilityMask.value match {
            case TypeAttributes.nestedAssembly.value |
                TypeAttributes.nestedFamANDAssem.value |
                TypeAttributes.nestedFamily.value |
                TypeAttributes.nestedFamORAssem.value |
                TypeAttributes.nestedPrivate.value |
                TypeAttributes.nestedPublic.value => true
            case _ => false
            }
        }
    private def addMapping[TKey, TValue](cache: HashMap[TKey, ArrayBuffer[TValue]], key: TKey, value: TValue) = {
        val mapped = cache.get(key) match {
            // the C# code doesn't add the new collection into the cache. That seems wrong.
            case None => ArrayBuffer[TValue]()
            case Some(value) => value
        }
        mapped.addOne(value)
        mapped

    }
    private def getFieldTypeSize(`type`: TypeReference): Int = {
        `type`.etype match {
            case ElementType.boolean | ElementType.u1 | ElementType.i1 => 1
            case ElementType.u2 | ElementType.i2 | ElementType.char => 2
            case ElementType.u4 | ElementType.i4 | ElementType.r4 => 4
            case ElementType.u8 | ElementType.i8 | ElementType.r8 => 8
            case ElementType.ptr | ElementType.fnPtr => 8 // FIXME - this is the machine word size, eg IntPtr.Size
            case ElementType.cModOpt | ElementType.cModReqD => 0 // TODO getFieldTypeSize(`type`.asInstanceOf[ModifierType].elementType)
            case _ =>
                val field_type = `type`.resolve().asInstanceOf[FieldDefinition]
                // if (field_type != null && field_type.hasLayoutInfo)
                //     field_type.classSize
                // else
                    0

            }
        }
    private def getEvent(`type`: TypeDefinition, token: MetadataToken): Option[EventDefinition] = {
        if (token.tokenType != TokenType.event) {
            throw IllegalArgumentException()
        }
        None // TODO
        // getMember(`type`._events, token)
    

    // TODO
    // private def getProperty(`type`: TypeDefinition, token: MetadataToken): PropertyDefinition =
    //     if (token.tokenType != TokenType.property)
    //         throw IllegalArgumentException()
    //     getMember(`type`.properties, token)
    
    }
    private def getMember[TMember <: MemberDefinition](members: ArrayBuffer[TMember], token: MetadataToken) = {
        members.find((m) => m.metadataToken == token) match {
            case Some(member) => member
            case None => throw IllegalArgumentException()

            }
        }
    private def isDeleted(member: MemberDefinition) = false
//        member.isSpecialName && member.name == "_Deleted"

    private def rangesSize(ranges: ArrayBuffer[Range]) = {
        ranges.view.map((r) => r.length).fold(0)((a, b) => a + b)    

    }
    private def addRange(ranges: HashMap[MetadataToken, ArrayBuffer[Range]], owner: MetadataToken, range: Range): Unit = {
        if (owner.RID == 0) {
            return ()
        }
        ranges.get(owner) match {
            case None =>
                ranges.addOne(owner, ArrayBuffer(range))
            case Some(slots) =>
                slots.addOne(range)
        
        

        }
    }
}

sealed class SignatureReader(blob: Int, private val _reader: MetadataReader) extends ByteBuffer(_reader.image.blobHeap.getOrElse(throw OperationNotSupportedException()).data) {
    this.position = blob
    private val _sig_length = readCompressedUInt32()
    private val _start = position
    private var _typeDepth = 0
    private val maxTypeSignatureDepth = 128

    private def _typeSystem = _reader.module.typeSystem

    private def readTypeTokenSignature() = {
        CodedIndex.typeDefOrRef.getMetadataToken(readCompressedUInt32())
    

    }
    private def getGenericParameter(`type`: GenericParameterType, `var`: Int) = {
        val context = _reader._context
        val index = `var`

        context match {
            case None =>
                getUnboundGenericParameter(`type`, index)
            case Some(ctx) =>
                val providerOpt = `type` match {
                    case GenericParameterType.`type` => ctx.`type`
                    case GenericParameterType.method => ctx.method
                }
                providerOpt match {
                    case Some(provider) =>
                        if (!ctx.isDefinition) {
                            checkGenericContext(provider, index)

                        }
                        if (index >= provider.genericParameters.length) {
                            getUnboundGenericParameter(`type`, index)
                        }
                        else {
                            provider.genericParameters(index)
                        }
                    case None => getUnboundGenericParameter(`type`, index)
                }
        }
    }
    private def getUnboundGenericParameter(`type`: GenericParameterType, index: Int) = {
        GenericParameter(index, `type`, _reader.module)

    }
    def readGenericInstanceSignature(provider: GenericParameterProvider, instance: GenericInstance, arity: Int) = {
        if (!provider.isDefinition) {
            checkGenericContext(provider, arity - 1)
        }
        val instance_arguments = instance.genericArguments

        for i <- 0 until arity do  {
            instance_arguments.addOne(readTypeSignature())
            
            }
        }
    private def readArrayTypeSignature() = {
        val array = ArrayType(readTypeSignature())
        val rank = readCompressedUInt32()

        val sizes = Array.ofDim[Int](readCompressedUInt32())
        for i <- 0 until sizes.length do {
            sizes(i) = readCompressedUInt32()
        
        }
        val low_bounds = Array.ofDim[Int](readCompressedUInt32())
        for i <- 0 until low_bounds.length do {
            low_bounds(i) = readCompressedUInt32()
        
        }
        array.dimensions.clear()

        for i <- 0 until rank do {
            var lower: Option[Int] = None
            var upper: Option[Int] = None

            if (i < low_bounds.length) {
                lower = Some(low_bounds(i))
            
            }
            if (i < sizes.length) {
                upper = Some(lower.get + sizes(i) - 1)
            
            }
            array.dimensions.addOne(ArrayDimension(lower, upper))
        }
        array

    }
    private def getTypeDefOrRef(token: MetadataToken) = {
        _reader.getTypeDefOrRef(token)
    
    }
    def readTypeSignature(): TypeReference = {
        // Recursion bound from plan 04: a hostile signature blob can nest
        // ptr/byref/array/genericInst/modifier types arbitrarily deep and
        // blow the stack. Every recursive arm goes through this entry
        // point, so one depth guard covers the whole family (and bounds
        // the structure before GenericParameterResolver ever walks it).
        _typeDepth += 1
        if (_typeDepth > maxTypeSignatureDepth) {
            throw DataFormatException()
        }
        try {
            readTypeSignature(ElementType.fromOrdinalValue(readByte()))
        } finally {
            _typeDepth -= 1
        }
    
    }
    def readTypeToken(): TypeReference = {
        getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException())
    
    }
    def readTypeSignature(etype: ElementType): TypeReference = {
        etype match {
            case ElementType.valueType =>
                val value_type = getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException())
                value_type.knownValueType()
                value_type
            case ElementType.`class` =>
                getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException())
            case ElementType.ptr =>
                PointerType(readTypeSignature())
            case ElementType.fnPtr =>
                val fptr = FunctionPointerType()
                readMethodSignature(fptr)
                fptr
            case ElementType.byRef =>
                ByReferenceType(readTypeSignature())
            case ElementType.pinned =>
                PinnedType(readTypeSignature())
            case ElementType.szArray =>
                ArrayType(readTypeSignature())
            case ElementType.array =>
                readArrayTypeSignature()
            case ElementType.cModOpt =>
                OptionalModifierType(getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException()), readTypeSignature())
            case ElementType.cModReqD =>
                RequiredModifierType(getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException()), readTypeSignature())
            case ElementType.sentinel =>
                SentinelType(readTypeSignature())
            case ElementType.`var` =>
                getGenericParameter(GenericParameterType.`type`, readCompressedUInt32())
            case ElementType.mVar =>
                getGenericParameter(GenericParameterType.method, readCompressedUInt32())
            case ElementType.genericInst =>
                val is_value_type = readByte() == ElementType.valueType.value
                val element_type = getTypeDefOrRef(readTypeTokenSignature()).getOrElse(throw OperationNotSupportedException())

                val arity = readCompressedUInt32()
                val generic_instance = GenericInstanceType(element_type, arity)

                readGenericInstanceSignature(element_type, generic_instance, arity)
                if (is_value_type) {
                    generic_instance.knownValueType()
                    element_type.getElementType().knownValueType()
                
                }
                generic_instance
            case ElementType.`object` => _typeSystem.`object`
            case ElementType.void => _typeSystem.void
            case ElementType.typedByRef => _typeSystem.typedReference
            case ElementType.i => _typeSystem.intPtr
            case ElementType.u => _typeSystem.uintPtr
            case _ => getPrimitiveType(etype)


        }
    }
    def readMethodSignature(method: MethodSignature) = {
        var calling_convention = readByte()
        val has_this = 0x20
        val explicit_this = 0x40
        val arity = 0x10

        if ((calling_convention.toInt & has_this) != 0) {
            method.hasThis = true
            calling_convention = (calling_convention.toInt & ~has_this).toByte
        
        }
        if ((calling_convention.toInt & explicit_this) != 0) {
            method.explicitThis = true
            calling_convention = (calling_convention.toInt & ~explicit_this).toByte
        
        }
        // Cecil keeps the 0x10 (Generic) bit in CallingConvention; only
        // hasThis and explicitThis are stripped (golden-pinned).
        val has_arity = (calling_convention.toInt & arity) != 0
        method.callingConvention = MethodCallingConvention.fromOrdinalValue(calling_convention)

        method.as[MethodReference] match {
            case None => ()
            case Some(generic_context) =>
                if (generic_context.declaringType.exists(!_.isArray)) {
                    _reader._context = Some(generic_context)
                
                }
                if (has_arity) {
                    val theArity = readCompressedUInt32()
                    if (!generic_context.isDefinition) {
                        checkGenericContext(generic_context, theArity - 1)
                    
                    }
                }
        }
        val param_count = readCompressedUInt32()

        method.methodReturnType.returnType = readTypeSignature()

        if (param_count != 0) {
            val method_ref = method.as[MethodReference]
            val parameters = {
                method_ref match {
                    case Some(mref) =>
                        mref._parameters = Some(ParameterDefinitionCollection(method, param_count))
                        mref.parameters
                    case None => ArrayBuffer.empty[ParameterDefinition]
                }
            }
            for i <- 0 until param_count do {
                parameters.addOne(ParameterDefinition(readTypeSignature()))

            }
        }
    }
    def readConstantSignature(`type`: ElementType) = readPrimitiveValue(`type`)

    def readCustomAttributeConstructorArguments(attribute: CustomAttribute, parameters: ArrayBuffer[ParameterDefinition]): Unit = {
        val count = parameters.length
        if (count == 0) {
            return ()
        
        }
        val args = ArrayBuffer[CustomAttributeArgument]()
        attribute._arguments = Some(args)

        for i <- 0 until count do {
            val parameterType = GenericParameterResolver.resolveParameterTypeIfNeeded(
                attribute.constructor, parameters(i)
            )
            args.addOne(readCustomAttributeFixedArgument(parameterType, 0))

            }
        }
    private def readCustomAttributeFixedArgument(`type`: TypeReference, depth: Int) = {
        if (depth > maxCustomAttributeDepth) {
            throw DataFormatException()
        }
        if (`type`.isArray) {
            readCustomAttributeFixedArrayArgument(`type`.as[ArrayType].getOrElse(throw OperationNotSupportedException()), depth + 1)
        }
        else {
            readCustomAttributeElement(`type`, depth + 1)
    
        }
    }
    def readCustomArgumentAttributeNamedArguments(count: Char) : (Option[ArrayBuffer[CustomAttributeNamedArgument]], Option[ArrayBuffer[CustomAttributeNamedArgument]]) = {
        var fields: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None
        var properties: Option[ArrayBuffer[CustomAttributeNamedArgument]] = None

        for i <- 0 until count do {
            if (canReadMore()) {
                val (f, p) = readCustomAttributeNamedArgument(fields, properties)
                fields = f
                properties = p
            }
        }
        (fields, properties)
    
    }
    def readCustomAttributeNamedArgument(fields: Option[ArrayBuffer[CustomAttributeNamedArgument]], properties: Option[ArrayBuffer[CustomAttributeNamedArgument]]): (Option[ArrayBuffer[CustomAttributeNamedArgument]], Option[ArrayBuffer[CustomAttributeNamedArgument]]) = {
        var localFields = fields
        var localProps = properties
        val kind = readByte()
        val `type` = readCustomAttributeFieldOrPropType(0)
        val name = readUTF8String()

        val container = kind match {
            case 0x53 =>
                val ct = getCustomAttributeNamedArgumentCollection(localFields)
                localFields = Some(ct)
                ct
            case 0x54 =>
                val ct = getCustomAttributeNamedArgumentCollection(localProps)
                localProps = Some(ct)
                ct
            case _ => throw OperationNotSupportedException()
        }
        container.addOne(CustomAttributeNamedArgument(name.getOrElse(""), readCustomAttributeFixedArgument(`type`, 0)))
        (localFields, localProps)
    
    }
    private def getCustomAttributeNamedArgumentCollection(coll: Option[ArrayBuffer[CustomAttributeNamedArgument]]) = {
        coll.getOrElse(ArrayBuffer[CustomAttributeNamedArgument]())

    }
    // H5 (ADR-0013): custom-attribute value nesting (boxed-object
    // elements, szArray type tags, arrays of arrays) is attacker-driven
    // one byte per level; cap it at the same 128 the type/signature
    // readers use.
    private val maxCustomAttributeDepth = 128

    private def readCustomAttributeFixedArrayArgument(`type`: ArrayType, depth: Int): CustomAttributeArgument = {
        if (depth > maxCustomAttributeDepth) {
            throw DataFormatException()
        }
        val length = readUInt32()
        length match {
            case 0xffffffff => CustomAttributeArgument(`type`, CilNullConstant)
            case 0 => CustomAttributeArgument(`type`, Array[CustomAttributeArgument]())
            case _ =>
                val arguments = Array.ofDim[CustomAttributeArgument](length)
                val element_type = `type`.elementType

                for i <- 0 until length do {
                    arguments(i) = readCustomAttributeElement(element_type, depth + 1)
                }
                CustomAttributeArgument(`type`, arguments)
    
            }
        }
    private def readCustomAttributeElement(`type`: TypeReference, depth: Int): CustomAttributeArgument = {
        if (depth > maxCustomAttributeDepth) {
            throw DataFormatException()
        }
        if (`type`.isArray) {
            readCustomAttributeFixedArrayArgument(`type`.asInstanceOf[ArrayType], depth + 1)
        }
        else {
            CustomAttributeArgument(`type`,
                if `type`.etype == ElementType.`object` then readCustomAttributeElement(readCustomAttributeFieldOrPropType(depth + 1), depth + 1)
                else readCustomAttributeElementValue(`type`)
            )

            }
        }
    private def readCustomAttributeElementValue(`type`: TypeReference): Any = {
        var thisType = `type`
        var etype = `type`.etype
        if (etype == ElementType.genericInst) {
            thisType = `type`.getElementType()
            etype = thisType.etype
        
        }
        etype match {
            case ElementType.string =>
                readUTF8String() match {
                    case Some(s) => s
                    case None => CilNullConstant
                }
            case ElementType.none =>
                if (thisType.isTypeOf("System", "Type")) {
                    readTypeReference()
                }
                else {
                    readCustomAttributeEnum(thisType)
                }
            case _ =>
                readPrimitiveValue(etype)

            }
        }
    private def readPrimitiveValue(`type`: ElementType): Any = {
        `type` match {
            case ElementType.boolean => readByte() == 1
            case ElementType.i1 => readByte()
            case ElementType.u1 => readByte().toInt & 0xff
            case ElementType.u2 => readUInt16().toInt
            case ElementType.char => readUInt16()
            case ElementType.i2 => readInt16()
            case ElementType.i4 => readInt32()
            case ElementType.u4 => readInt32().toLong & 0xffffffffL
            case ElementType.i8 => readInt64()
            case ElementType.u8 => java.math.BigInteger(java.lang.Long.toUnsignedString(readInt64()))
            case ElementType.r4 => readSingle()
            case ElementType.r8 => readDouble()
            case _ => throw OperationNotSupportedException(`type`.toString())
    
            }
        }
    private def getPrimitiveType(etype: ElementType) = {
        etype match {
            case ElementType.boolean => _typeSystem.boolean
            case ElementType.char => _typeSystem.char
            case ElementType.i1 => _typeSystem.sByte
            case ElementType.u1 => _typeSystem.byte
            case ElementType.i2 => _typeSystem.int16
            case ElementType.u2 => _typeSystem.uInt16
            case ElementType.i4 => _typeSystem.int32
            case ElementType.u4 => _typeSystem.uInt32
            case ElementType.i8 => _typeSystem.int64
            case ElementType.u8 => _typeSystem.uInt64
            case ElementType.r4 => _typeSystem.single
            case ElementType.r8 => _typeSystem.double
            case ElementType.string => _typeSystem.string
            case _ => throw OperationNotSupportedException(etype.toString())

            }
        }
    private def readCustomAttributeFieldOrPropType(depth: Int):TypeReference = {
        if (depth > maxCustomAttributeDepth) {
            throw DataFormatException()
        }
        var etype = ElementType.fromOrdinalValue(readByte())
        etype match {
            case ElementType.boxed => _typeSystem.`object`
            case ElementType.szArray => ArrayType(readCustomAttributeFieldOrPropType(depth + 1))
            case ElementType.`enum` => readTypeReference().getOrElse(throw OperationNotSupportedException())
            case ElementType.`type` => _typeSystem.lookupType("System", "Type")
            case _ => getPrimitiveType(etype)
    
        }
    }
    def readTypeReference() = {
        TypeParser.parseType(Some(_reader.module), readUTF8String().getOrElse(""))

    }
    private def readCustomAttributeEnum(enum_type: TypeReference): Any = {
        val `type` = resolveForCustomAttributeEnum(enum_type).getOrElse(throw ResolutionException())
        if (!`type`.isEnum) {
            throw IllegalArgumentException()
        }
        readCustomAttributeElementValue(`type`.getEnumUnderlyingType().getOrElse(throw OperationNotSupportedException()))

    // TODO
    // def readSecurityAttribute(): SecurityAttribute = null

    // TODO
    // def readMarshalInfo(): MarshalInfo = null

    }
    private def resolveForCustomAttributeEnum(reference: TypeReference): Option[TypeDefinition] = {
        reference.scope match {
            case Some(scope) if scope == _reader.module =>
                if (!reference.isNested) {
                    _reader.module.getType(reference.fullName)
                }
                else {
                    reference.declaringType.flatMap(resolveForCustomAttributeEnum).flatMap(_.getNestedType(reference.name))
                }
            case _ => None
        }
    }
    private def readNativeType() = {
        NativeType.fromOrdinalValue(readByte())
    
    }
    private def readVariantType() = {
        VariantType.fromOrdinalValue(readByte())

    }
    private def readUTF8String(): Option[String] = {
        if (buffer(position) == 0xff.toByte) {
            position += 1
            return None
        
        }
        val length = readCompressedUInt32()
        if (length == 0) {
            return Some("")
        
        }
        if (position + length > buffer.length) {
            return Some("")
        
        }
        val string = new String(buffer, position, length, StandardCharsets.UTF_8)
        position += length
        Some(string)

    }
    def canReadMore() = {
        (position - _start) < _sig_length
    }
}

object SignatureReader {
    private def checkGenericContext(owner: GenericParameterProvider, index: Int) = {
        var owner_parameters = owner.genericParameters
        for i <- owner_parameters.length to index do { // yes, to is intentional
            owner_parameters.addOne(GenericParameter(owner))
            }
        }
}