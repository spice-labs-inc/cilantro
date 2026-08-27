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
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import io.spicelabs.cilantro.cil.SymbolReader
import io.spicelabs.cilantro.cil.PortablePdbReader
import io.spicelabs.cilantro.cil.DefaultSymbolReaderProvider
import javax.naming.OperationNotSupportedException
import java.nio.file.Paths
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
                r.assembly = getTypeReferenceScope(implementation).asInstanceOf[AssemblyNameReference]
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
    def getManagedResource(offset: Int): Array[Byte] = {
        val bytes = image.resources.flatMap(rs => image.getReaderAt(rs.virtualAddress, offset, (o, reader) => {
            reader.advance(o)
            reader.readBytes(reader.readInt32())
        }))
        bytes match {
            case Some(value) => value
            case None => Array.emptyByteArray
    
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
        ()
        // if (metadata.types != null)
        //     ()
        // else
        //     initializeNestedTypes()
        //     initializeFields()
        //     initializeMethods()
            // TODO

    }
    def hasNestedTypes(`type`: TypeDefinition) = false
        // initializeNestedTypes()
        // tryGetNestedTypeMapping(`type`) match
        //     case Some(mapping) => mapping.length > 0
        //     case _ => false
    
    def readNestedTypes(`type`: TypeDefinition): Option[MemberDefinitionCollection[TypeDefinition]] = None
        // initializeNestedTypes()
        // tryGetNestedTypeMapping(`type`) match
        //     case Some(mapping) =>
        //         val nested_types = MemberDefinitionCollection[TypeDefinition](`type`, mapping.length)
        //         for i <- 0 until mapping.length do
        //             val nested_type = getTypeDefinition(mapping(i))
        //             if (nested_type != null)
        //                 nested_types.addOne(nested_type)
        //         nested_types
        //     case _ => MemberDefinitionCollection[TypeDefinition](`type`)
    
    private def initializeNestedTypes() = {
        () // TODO

    }
    private def addNestedMapping(declaring: Int, nested: Int) = {
        () // TODO


    }
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

            // metadata.addTypeDefinition(`type`)

            this._context = Some(`type`)

            getTypeDefOrRef(readMetadataToken(CodedIndex.typeDefOrRef)) match {
                case Some(t) => `type`.baseType = t
                case None => ()
            }

            `type`.fields_range = Some(readListRange(rid, Table.typeDef, Table.field))
            `type`.methods_range = Some(readListRange(rid, Table.typeDef, Table.method))

            if (MetadataReader.isNested(attributes)) {
                getNestedTypeDeclaringType(`type`).foreach(t => `type`.declaringType = t)
            
            }
            Some(`type`)

        }
    }
    private def getNestedTypeDeclaringType(`type`: TypeDefinition): Option[TypeDefinition] = {
        None // TODO


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
    private def readTypeDefinition(rid: Int): Option[TypeDefinition] = {
        if (!moveTo(Table.typeDef, rid)) {
            None
        }
        else {
            readType(rid)

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
        None
        // val `type` = reader.readTypeSignature();
        // if (`type`.token.RID == 0)
        //     `type`.token = MetadataToken(TokenType.typeSpec, rid)
        // `type`
    
    }
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
    def readInterfaces(`type`: TypeDefinition): Option[InterfaceImplementationCollection] = None // TODO


    private def initializeInterfaces() = { } // TODO

    private def addInterfaceMapping(`type`: Int, interface: Row2[Int, MetadataToken]) = { } // TODO

    def readFields(`type`: TypeDefinition) = { } // TODO

    private def readField(field_rid: Int, fields: ArrayBuffer[FieldDefinition]) = { } // TODO

    private def initializeFields() = { } // TODO

    private def readFieldType(signature: Int): Option[TypeReference] = {
        var reader = readSignature(signature)
        
        val field_sig:Byte = 0x6

        if (reader.readByte() != field_sig) {
            throw OperationNotSupportedException()
        
        }
        None
        // reader.readTypeSignature()
    
    }
    def readFieldRVA(field: FieldDefinition) = 0 // TODO

    def getFieldInitializeValue(size: Int, rva: Int) = {
        val bytes = image.getReaderAt(rva, size, (s, reader) => reader.readBytes(s))
        bytes.getOrElse(Array.emptyByteArray)

    }
    private def initializeFieldRVAs() = { } // TODO

    def readFieldLayout(field: FieldDefinition) = 0 // TODO

    private def initializeFieldLayouts() = { } // TODO

    def hasEvents(`type`: TypeDefinition) = false // TODO

    def readEvents(`type`: TypeDefinition): Option[ArrayBuffer[EventDefinition]] = None // TODO

    def readEvent(event_rid: Int, events: ArrayBuffer[EventDefinition]) = { }

    private def initializeEvents() = { } // TODO

    def hasProperties(`type`: TypeDefinition) = { } // TODO

    def readProperties(`type`: TypeDefinition) = { } // TODO

    private def readProperty(property_rid: Int /*, properties: ArrayBuffer[PropertyDefinition] */) = { } // TODO

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

    def readMethods(`type`: TypeDefinition): Option[ArrayBuffer[MethodDefinition]] = None // TODO

    private def readPointers[TMember <: MemberDefinition](ptr: Table, table: Table, range: Range,
        members: ArrayBuffer[TMember], reader: (Int, ArrayBuffer[TMember]) => Unit) =
        for i <- 0 until range.length do {
            moveTo(ptr, range.index + i)
            val rid = readTableIndex(table)
            moveTo(table, rid)

            reader(rid, members)
    
        }
    private def initializeMethods() = { } // TODO

    private def readMethod(method_rid: Int, methods: ArrayBuffer[MethodDefinition]) = { } // TODO

    private def readParameters(method: MethodDefinition, param_range: Range) = { } // TODO

    private def readParameterPointers(method: MethodDefinition, range: Range) = { } // TODO

    private def readParameter(param_rid: Int, method: MethodDefinition) = { } // TODO

    // private def readMethodSignature(signature: Int, method: MethodSignature) = { } // TODO

    // def readPInvokeInfo(method: MethodDefinition): PInvokeInfo = null // TODO

    private def initializePInvokes() = { } // TODO

    def hasGenericParameters(provider: GenericParameterProvider) = {
        initializeGenericParameters()
        false // TODO
    
    }
    def readGenericParameters(provider: GenericParameterProvider): ArrayBuffer[GenericParameter] = ArrayBuffer.empty[GenericParameter] // TODO

    private def readGenericParametersRange(range: Range, provider: GenericParameterProvider, generic_parameters: GenericParameterCollection) = { }

    private def initializeGenericParameters() = { } // TODO

    private def initializeRanges(table: Table, get_next: () => MetadataToken): HashMap[MetadataToken, ArrayBuffer[Range]] = {
        val length = moveTo(table)
        val ranges = HashMap[MetadataToken, ArrayBuffer[Range]]()

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

    def readGenericConstraints(generic_parameter: GenericParameter): Option[GenericParameterConstraintCollection] = None // TODO

    private def initializeGenericConstraints() = { } // TODO

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

        String(blob, index, actualCount, "UTF-16")

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
        readTypeSignature(ElementType.fromOrdinalValue(readByte()))
    
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
        var has_arity = false
        if ((calling_convention.toInt & arity) != 0) {
            has_arity = true
            calling_convention = (calling_convention.toInt & ~arity).toByte

        
        }
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
            args.addOne(readCustomAttributeFixedArgument(parameterType))

            }
        }
    private def readCustomAttributeFixedArgument(`type`: TypeReference) = {
        if (`type`.isArray) {
            readCustomAttributeFixedArrayArgument(`type`.as[ArrayType].getOrElse(throw OperationNotSupportedException()))
        }
        else {
            readCustomAttributeElement(`type`)
    
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
        val `type` = readCustomAttributeFieldOrPropType()
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
        container.addOne(CustomAttributeNamedArgument(name.getOrElse(""), readCustomAttributeFixedArgument(`type`)))
        (localFields, localProps)
    
    }
    private def getCustomAttributeNamedArgumentCollection(coll: Option[ArrayBuffer[CustomAttributeNamedArgument]]) = {
        coll.getOrElse(ArrayBuffer[CustomAttributeNamedArgument]())

    }
    private def readCustomAttributeFixedArrayArgument(`type`: ArrayType): CustomAttributeArgument = {
        val length = readUInt32()
        length match {
            case 0xffffffff => CustomAttributeArgument(`type`, CilNullConstant)
            case 0 => CustomAttributeArgument(`type`, Array[CustomAttributeArgument]())
            case _ =>
                val arguments = Array.ofDim[CustomAttributeArgument](length)
                val element_type = `type`.elementType

                for i <- 0 until length do {
                    arguments(i) = readCustomAttributeElement(element_type)
                }
                CustomAttributeArgument(`type`, arguments)
    
            }
        }
    private def readCustomAttributeElement(`type`: TypeReference): CustomAttributeArgument = {
        if (`type`.isArray) {
            readCustomAttributeFixedArrayArgument(`type`.asInstanceOf[ArrayType])
        }
        else {
            CustomAttributeArgument(`type`,
                if `type`.etype == ElementType.`object` then readCustomAttributeElement(readCustomAttributeFieldOrPropType())
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
                readUTF8String().getOrElse("")
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
            case ElementType.i1 | ElementType.u1 => readByte()
            case ElementType.u2 | ElementType.char => readUInt16()
            case ElementType.i2 => readInt16()
            case ElementType.i4 | ElementType.u4 => readInt32()
            case ElementType.i8 | ElementType.u8 => readInt64()
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
    private def readCustomAttributeFieldOrPropType():TypeReference = {
        var etype = ElementType.fromOrdinalValue(readByte())
        etype match {
            case ElementType.boxed => _typeSystem.`object`
            case ElementType.szArray => ArrayType(readCustomAttributeFieldOrPropType())
            case ElementType.`enum` => readTypeReference().getOrElse(throw OperationNotSupportedException())
            case ElementType.`type` => _typeSystem.lookupType("System", "Type")
            case _ => getPrimitiveType(etype)
    
        }
    }
    def readTypeReference() = {
        TypeParser.parseType(Some(_reader.module), readUTF8String().getOrElse(""))

    }
    private def readCustomAttributeEnum(enum_type: TypeReference): Any = {
        val `type` = enum_type.checkedResolve().getOrElse(throw IllegalArgumentException())
        if (!`type`.isEnum) {
            throw IllegalArgumentException()
        }
        readCustomAttributeElementValue(`type`.getEnumUnderlyingType().getOrElse(throw OperationNotSupportedException()))

    // TODO
    // def readSecurityAttribute(): SecurityAttribute = null

    // TODO
    // def readMarshalInfo(): MarshalInfo = null

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