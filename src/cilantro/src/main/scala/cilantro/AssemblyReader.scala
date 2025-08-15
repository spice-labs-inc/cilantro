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
import java.io.File
import java.nio.file.Paths
import io.spicelabs.cilantro.metadata.Row3
import io.spicelabs.cilantro.metadata.ElementType
import io.spicelabs.cilantro.metadata.Row2
import scala.util.boundary, boundary.break


abstract class ModuleReader() {
    protected var module: ModuleDefinition = null

    protected def this(image: Image, mode: ReadingMode) =
        this()
        module = ModuleDefinition(image)
        module.readingMode = mode

    protected def readModule(): Unit
    def readSymbols(module: ModuleDefinition): Unit

    protected def readModuleManifest(reader: MetadataReader) =
        reader.populate(module)
        readAssembly(reader)

    private def readAssembly(reader: MetadataReader): Unit =
        val name = reader.readAssemblyNameDefinition()
        if (name == null)
            module.moduleKind_(ModuleKind.netModule)
            return ()

        val assembly = AssemblyDefinition()
        assembly.name_(name)

        module.assembly = assembly
        assembly.mainModule = module
}

object ModuleReader {
    def createModule(image: Image, parameters: ReaderParameters) =
        val reader = createModuleReader(image, parameters.readingMode)
        val module = reader.module

        if (parameters.assemblyResolver != null)
            module.assembly_resolver = Disposable.notOwned(parameters.assemblyResolver)
        
        if (parameters.metadataResolver != null)
            module.metadata_resolver = parameters.metadataResolver

        if (parameters.metadataImporterProvider != null)
            module.metadata_importer = parameters.metadataImporterProvider.getMetadataImporter(module)
        
        if (parameters.reflectionImporterProvider != null)
            module.reflection_importer = parameters.reflectionImporterProvider.getReflectionImporter(module)

        getMetadataKind(module, parameters)

        reader.readModule()

        readSymbols(module, parameters)

        reader.readSymbols(module)

        // TODO
        // if (parameters.readingMode == ReadingMode.immediate)
        //     module.metadata_system.clear()
        module

    private def readSymbols(module: ModuleDefinition, parameters: ReaderParameters) =
        var symbol_reader_provider = parameters.symbolReaderProvider

        if (symbol_reader_provider == null && parameters.readSymbols)
            symbol_reader_provider = DefaultSymbolReaderProvider()
        
        if (symbol_reader_provider != null)
            module.symbolReaderProvider = symbol_reader_provider

            val reader = if parameters.symbolStream != null then symbol_reader_provider.getSymbolReader(module, parameters.symbolStream)
                            else symbol_reader_provider.getSymbolReader(module, module.fileName)
            
            if (reader != null)
                try
                    module.readSymbols(reader, parameters.throwIfSymbolsAreNotMatching)
                catch
                    case err: Exception =>
                        reader.close()
                        throw err
        
        if (module.image.hasDebugTables())
            module.readSymbols(PortablePdbReader(module.image, module))

    private def getMetadataKind(module: ModuleDefinition, parameters: ReaderParameters): Unit =
        if (!parameters.applyWindowsRuntimeProjections)
            module.metadataKind_(MetadataKind.ecma335)
            return ()
        
        val runtime_version = module.runtimeVersion

        if (!runtime_version.contains("WindowsRuntime"))
            module.metadataKind_(MetadataKind.ecma335)
        else if (runtime_version.contains("CLR"))
            module.metadataKind_(MetadataKind.managedWindowsMetadata)
        else
            module.metadataKind_(MetadataKind.windowsMetadata)

    private def createModuleReader(image: Image, mode: ReadingMode) =
        mode match
            case ReadingMode.immediate => ImmediateModuleReader(image)
            case ReadingMode.deferred => DeferredModuleReader(image)
            case null => throw IllegalArgumentException()

}

sealed class ImmediateModuleReader(image: Image) extends ModuleReader(image, ReadingMode.immediate) {
    var resolve_attributes = false

    override protected def readModule(): Unit =
        this.module.read(this.module, (module, reader) => {
            readModuleManifest(reader)
            readModule(module, true)
        })

    def readModule(module: ModuleDefinition, resolve_attributes: Boolean): Unit =
        this.resolve_attributes = resolve_attributes

        // TODO
        // if (module.hasAssemblyReferences)
        //     read(module.assemblyReferences)
        // if (module.hasResources)
        //     read(module.resources)
        // if (module.hasModuleReferences)
        //     read(module.moduleReferences)
        // if (module.hasTypes)
        //     readTypes(module._types)
        // if (module.hasExportedTypes)
        //     read(module.exportedTypes)
        
        readCustomAttributes(module)

        val assembly = module.assembly
        if (module.kind != ModuleKind.netModule && assembly != null)
            readCustomAttributes(assembly)
            // TODO
            // readSecurityDeclarations(assembly)

    private def readTypes(types: ArrayBuffer[TypeDefinition]) =
        types.foreach(readType)

    private def readType(`type`: TypeDefinition): Unit =
        readGenericParameters(`type`)
        if (`type`.hasInterfaces)
            readInterfaces(`type`)
        
        if (`type`.hasNestedTypes)
            readTypes(`type`.nestedTypes)

        // TODO        
        // if (`type`.hasLayoutInfo)
        //     read(`type`.classSize)
        
        if (`type`.hasFields)
            readFields(`type`)

        if (`type`.hasMethods)
            readMethods(`type`)

        if (`type`.hasProperties)
            readProperties(`type`)
        
        if (`type`.hasEvents)
            readEvents(`type`)
        
        readSecurityDeclarations(`type`)
        readCustomAttributes(`type`)

    private def readInterfaces(`type`: TypeDefinition) =
        var interfaces = `type`.interfaces
        interfaces.foreach(readCustomAttributes)
    
    private def readGenericParameters(provider: GenericParameterProvider): Unit =
        if (!provider.hasGenericParameters)
            ()
        
        provider.genericParameters.foreach((parameter) => {
            if (parameter.hasConstraints)
                readGenericParameterConstraints(parameter)
            readCustomAttributes(parameter)
        })
    
    private def readGenericParameterConstraints(parameter: GenericParameter) =
        parameter.constraints.foreach((constraint) => {
            readCustomAttributes(constraint.asInstanceOf[CustomAttributeProvider])
        })
    
    private def readSecurityDeclarations(provider: SecurityDeclarationProvider): Unit =
        if (!provider.hasSecurityDeclarations)
            ()
        
        if (!resolve_attributes)
            ()
        
        // provider.securityDeclarations.foreach((declaration) => {
        //     read(declaration.securityAttributes)
        // })
    
    private def readCustomAttributes(provider: CustomAttributeProvider): Unit =
        if (!provider.hasCustomAttributes)
            ()
        val custom_attributes = provider.customAttributes
        if (!resolve_attributes)
            ()
        // custom_attributes.foreach((attribute) => {
        //     read(attribute.constructorArguments)
        // })

    private def readFields(`type`: TypeDefinition) =
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

    private def readMethods(`type`: TypeDefinition) =
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

    private def readParameters(method: MethodDefinition) =
        ()
        // TODO
        // method.parameters.foreach((parameter) => {
        //     if (parameter.hasConstant)
        //         read(parameter.constant)
        //     if (parameter.hasMarshalInfo)
        //         read(parameter.marshalInfo)
            
        //     readCustomAttributes(parameter)
        // })
    
    private def readProperties(`type`: TypeDefinition) =
        `type`.properties.foreach((property) => {
            // TOFO
            // read(property.getMethod)

            // if (property.hasConstant)
            //     read(property.constant)
            
            // readCustomAttributes(property)
        })
    
    private def readEvents(`type`: TypeDefinition) =
        `type`.events.foreach((event) => {
            // TODO
            // read(event.addMethod)

            // readCustomAttributes(event)
        })
    
    override def readSymbols(module: ModuleDefinition): Unit =
        if (module.symbol_reader == null)
            ()
        
        readTypesSymbols(module.types, module.symbol_reader)
    
    private def readTypesSymbols(types: ArrayBuffer[TypeDefinition], symbol_reader: SymbolReader): Unit =
        types.foreach((`type`) => {
            // TODO
            // `type`._custom_infos = symbol_reader.read(`type`)

            // if (`type`.hasNestedTypes)
            //     readTypesSymbols(`type`.nestedTypes, symbol_reader)
            
            // if (`type`.hasMethods)
            //     readMethodsSymbols(`type`, symbol_reader)
        })
    
    private def readMethodsSymbols(`type`: TypeDefinition, symbol_reader: SymbolReader) =
        `type`.methods.foreach((method) => {
            // TODO
            // if (method.hasBody && method.token.RID != 0 && method.debug_info == null)
            //     method.debug_info = symbol_reader.read(method)
        })


}

sealed class DeferredModuleReader(image: Image) extends ModuleReader(image, ReadingMode.deferred) {

    protected override def readModule() =
        this.module.read(this.module, (_, reader) => readModuleManifest(reader))
    
    override def readSymbols(module: ModuleDefinition): Unit = ()
    
}

sealed class MetadataReader(val image: Image, val module: ModuleDefinition, val metadata_reader: MetadataReader) extends ByteBuffer(image.tableHeap.data) {
    val metadata: MetadataSystem = module.metadataSystem
    // TODO
    // private var code: CodeReader
    var _context: GenericContext = null


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

    def readAssemblyNameDefinition(): AssemblyNameDefinition =
        if (moveTo(Table.assembly) == 0)
            null
        else
            val name = AssemblyNameDefinition()
            
            name.hashAlgorithm = AssemblyHashAlgorithm.fromOrdinalValue(readInt32())

            populateVersionAndFlags(name)
            
            name.publicKey = readBlob()

            populateNameAndCulture(name)
            name

    def populate(module: ModuleDefinition): ModuleDefinition =
        if (moveTo(Table.module) == 0)
            module
        else
            advance(2) // Generation

            module.name = readString()
            module.mvid = readGuid()
            module

    // TODO - finish

    private def initializeAssemblyReferences(): Unit =
        if (metadata._assemblyReferences != null)
            ()
        else
            val length = moveTo(Table.assemblyRef)
            metadata._assemblyReferences = Array.ofDim[AssemblyNameReference](length)
            val references = metadata._assemblyReferences
            for i <- 0 until length do
                val reference = AssemblyNameReference()
                reference._token = MetadataToken(TokenType.assemblyRef, i + 1)

                populateVersionAndFlags(reference)
                var key_or_token = readBlob()

                if (reference.hasPublicKey)
                    reference.publicKey = key_or_token
                else
                    reference.publicKeyToken = key_or_token
                
                populateNameAndCulture(reference)

                reference.hash = readBlob()
                references(i) = reference

    def readAssemblyReferences() =
        initializeAssemblyReferences()

        var references = ArrayBuffer[AssemblyNameReference]().addAll(metadata._assemblyReferences)

        // TODO
        // if (module.isWindowsMetadata())
        //     module.projections.addVirtualReferences(references)

        references
    
    def readEntryPoint(): MethodDefinition =
        if (module.image.entryPointToken == 0)
            null
        else
            val token = MetadataToken(module.image.entryPointToken)
            null
            // TODO
            // getMethodDefinition(token.RID)

    def readModules() =
        val modules = ArrayBuffer[ModuleDefinition](this.module)

        val length = moveTo(Table.file)
        for i <- 1 to length do // this inclusive intentionally
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

    private def getModuleFileName(name: String) =
        if (module.fileName == null)
            throw OperationNotSupportedException()
        else
            val pathToFile = Paths.get(module.fileName)
            val path = pathToFile.getParent()
            path.resolve(name).toAbsolutePath()


    private def initializeModuleReferences(): Unit =
        if (metadata._moduleReferences != null)
            return {}
        
        val length = moveTo(Table.moduleRef)
        metadata._moduleReferences = Array.ofDim[ModuleReference](length)
        val references = metadata._moduleReferences

        for i <- 0 until length do
            val reference = ModuleReference(readString())
            reference._token = MetadataToken(TokenType.moduleRef, i + 1)
            references(i) = reference

    def readModuleReferences() =
        initializeModuleReferences()

        var references = ArrayBuffer.empty[ModuleReference]

        references.addAll(metadata._moduleReferences)

        references
    
    def hasFileResource(): Boolean =
        false
        // TODO
    
    // TODO
    def readResources(): ArrayBuffer[Any] = null

    def readFileRecord(rid: Int) =
        val position = this.position

        if (!moveTo(Table.file, rid))
            throw new IllegalArgumentException()
        
        val record = Row3[FileAttributes, String, Int](FileAttributes.fromOrdinal(readUInt32()), readString(), readBlobIndex())
        this.position = position
        record
    
    def getManagedResource(offset: Int) =
        val bytes = image.getReaderAt(image.resources.virtualAddress, offset, (o, reader) => {
            reader.advance(o)
            reader.readBytes(reader.readInt32())
        })
        if bytes != null then bytes else Array.emptyByteArray
    
    private def populateVersionAndFlags(name: AssemblyNameReference) =
        val maj = readUInt16().toInt
        val min = readUInt16().toInt
        val rev = readUInt16().toInt
        val build = readUInt16().toInt
        name.version = CSVersion(maj, min, rev, build)
        name.attributes = readUInt32()

    private def populateNameAndCulture(name: AssemblyNameReference) =
        name.name = readString()
        name.culture = readString()
    
    def readTypes() =
        initializeTypeDefinitions()
        // val mtypes = _metadata.`types`
        val type_count = 0 // mtypes.length - metadata.nestedTypes.length
        val types = TypeDefinitionCollection(module, type_count)

        // TODO
        types
    
    private def completeTypes() = ()

    private def initializeTypeDefinitions() =
        ()
        // if (metadata.types != null)
        //     ()
        // else
        //     initializeNestedTypes()
        //     initializeFields()
        //     initializeMethods()
            // TODO

    def hasNestedTypes(`type`: TypeDefinition) = false
        // initializeNestedTypes()
        // tryGetNestedTypeMapping(`type`) match
        //     case Some(mapping) => mapping.length > 0
        //     case _ => false
    
    def readNestedTypes(`type`: TypeDefinition): MemberDefinitionCollection[TypeDefinition] = null
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
    
    private def initializeNestedTypes() =
        () // TODO

    private def addNestedMapping(declaring: Int, nested: Int) =
        () // TODO


    // TODO
    private def initializeCustomAttributes() = { }

    private def readType(rid: Int) = 
        if (!moveTo(Table.typeDef, rid))
            null
        else
            val attributes = readUInt32()
            val name = readString()
            val namespace = readString()
            val `type` = TypeDefinition(namespace, name, attributes)
            `type`.token = MetadataToken(TokenType.typeDef, rid)
            `type`.scope = module
            `type`._module = module

            // metadata.addTypeDefinition(`type`)

            this._context = `type`

            `type`.baseType = getTypeDefOrRef(readMetadataToken(CodedIndex.typeDefOrRef))

            `type`.fields_range = readListRange(rid, Table.typeDef, Table.field)
            `type`.methods_range = readListRange(rid, Table.typeDef, Table.method)

            if (MetadataReader.isNested(attributes))
                `type`.declaringType = getNestedTypeDeclaringType(`type`)
            
            `type`


    private def getNestedTypeDeclaringType(`type`: TypeDefinition): TypeDefinition =
        null // TODO


    private def readListRange(current_index: Int, current: Table, target: Table) =
        val list:io.spicelabs.cilantro.Range = new Range(0, 0)

        val start = readTableIndex(target)
        if (start == 0)
            list
        else
            var next_index = 0
            val current_table = image.tableHeap(current)

            if (current_index == current_table.length)
                next_index = image.tableHeap(target).length + 1
            else
                val position = this.position
                this.position += (current_table.rowSize - image.getTableIndexSize(target))
                next_index = readTableIndex(target)
                this.position = position
            
            list.index = start
            list.length = next_index - start

            list

    def readTypeLayout(`type`: TypeDefinition) =
        initializeTypeLayouts()
        val class_layout = Row2[Short, Int](0, 0)

        // TODO
        class_layout
    
    private def initializeTypeLayouts() = { }

    def getTypeDefOrRef(token: MetadataToken) =
        lookupToken(token).asInstanceOf[TypeReference]
    
    def getTypeDefinition(rid: Int) =
        initializeTypeDefinitions()

        // TODO
        var `type`:TypeDefinition = null // = metadata.getTypeDefinition(rid)
        // if (`type` != null)
        //     `type`
        // else
        //     `type` = readTypeDefinition(rid)
        //     if (module.isWindowsMetadata())
        //         windowsRuntimeProjections.project(`type`)
        //     `type`
        `type`

    private def readTypeDefinition(rid: Int): TypeDefinition =
        if (!moveTo(Table.typeDef, rid))
            null
        else
            readType(rid)

    
    // TODO
    private def initializeTypeReferences() = { }

    def getTypeReference(scope: String, full_name: String): TypeReference =
        initializeTypeReferences()

        val length = 0 // TODO metadata.typeReferences.length

        var `type`: TypeReference = null
        boundary:
            for i <- 1 to length do // intentionally inclusive
                `type` = getTypeReference(i)
                if (`type`.fullName == full_name)
                    if (scope == null || scope.length == 0)
                        break()
                    if (`type`.scope.name == scope)
                        break()
                `type` = null
        `type`

    private def getTypeReference(rid: Int): TypeReference =
        initializeTypeReferences()

        val `type`:TypeReference = null // metadata.getTypeReference(rid)
        if (`type` != null)
            `type`
        else
            readTypeReference(rid)
    
    private def readTypeReference(rid: Int): TypeReference =
        if (!moveTo(Table.typeRef, rid))
            return null
        
        var declaring_type: TypeReference = null
        var scope: MetadataScope = null

        val scope_token = readMetadataToken(CodedIndex.resolutionScope)

        val name = readString()
        val namespace = readString()
        val `type` = TypeReference(namespace, name, module, null)

        // TODO
        // metadata.addTypeReference(`type`)
        
        if (scope_token.tokenType == TokenType.typeRef)
            if (scope_token.RID != rid)
                declaring_type = getTypeDefOrRef(scope_token)

                scope = if declaring_type != null then declaring_type.scope else module
            else
                scope = module
        else
            scope = getTypeReferenceScope(scope_token)
        `type`.scope = scope
        `type`.declaringType = declaring_type

        // TODO
        // MetadataSystem.tryProcessPrimitiveTypeReference(`type`)

        // if (`type`.module.isWindowsMetadata())
        //     windowsRuntimeProjections.project(`type`)

        `type`

    def getTypeReferenceScope(scope: MetadataToken): MetadataScope =
        if (scope.tokenType == TokenType.module)
            module
        else
            var scopes: Array[MetadataScope] = scope.tokenType match
                case TokenType.assemblyRef =>
                    initializeAssemblyReferences()
                    null // metadata.assemblyReferences
                case TokenType.moduleRef =>
                    initializeModuleReferences()
                    null // metadata.moduleReferences
                case _ => throw OperationNotSupportedException()

                var index = scope.RID - 1
                if (index < 0 || index >= scopes.length)
                    null
                else
                    scopes(index)

    def getTypeReferences(): Iterable[TypeReference] =
        initializeTypeReferences()

        val length = image.getTableLength(Table.typeRef)
        val type_references = Array.ofDim[TypeReference](length)

        for i <- 1 to length do // intentionally inclusive
            type_references(i - 1) = getTypeReference(i)
        
        type_references
    
    private def getTypeSpecification(rid: Int): TypeReference =
        if (!moveTo(Table.typeSpec, rid))
            return null
        val reader = readSignature(readBlobIndex())
        null
        // val `type` = reader.readTypeSignature();
        // if (`type`.token.RID == 0)
        //     `type`.token = MetadataToken(TokenType.typeSpec, rid)
        // `type`
    
    private def readSignature(signature: Int) =
        SignatureReader(signature, this)
    
    def hasInterfaces(`type`: TypeDefinition) =
        false
        // TODO
        // initializeInterfaces()
        // tryGetInterfaceMapping(`type`) match
        //     case Some(mapping) => true
        //     case None => false
        
    def readInterfaces(`type`: TypeDefinition): InterfaceImplementationCollection = null // TODO


    private def initializeInterfaces() = { } // TODO

    private def addInterfaceMapping(`type`: Int, interface: Row2[Int, MetadataToken]) = { } // TODO

    def readFields(`type`: TypeDefinition) = { } // TODO

    private def readField(field_rid: Int, fields: ArrayBuffer[FieldDefinition]) = { } // TODO

    private def initializeFields() = { } // TODO

    private def readFieldType(signature: Int): TypeReference =
        var reader = readSignature(signature)
        
        val field_sig:Byte = 0x6

        if (reader.readByte() != field_sig)
            throw OperationNotSupportedException()
        
        null
        // reader.readTypeSignature()
    
    def readFieldRVA(field: FieldDefinition) = 0 // TODO

    def getFieldInitializeValue(size: Int, rva: Int) =
        val bytes = image.getReaderAt(rva, size, (s, reader) => reader.readBytes(s))
        if bytes != null then bytes else Array.emptyByteArray

    def hasCustomAttributes(owner: CustomAttributeProvider) =
        initializeCustomAttributes()
        // TODO
        // val rangeOpt = metadata.tryGetCustomAttributeRanges(owner)
        // rangeOpt match
        //     case Some(ranges) => rangeSize(anges) > 0
        //     case _ => false
        
        false

    private def initializeFieldRVAs() = { } // TODO

    def readFieldLayout(field: FieldDefinition) = 0 // TODO

    private def initializeFieldLayouts() = { } // TODO

    def hasEvents(`type`: TypeDefinition) = false // TODO

    def readEvents(`type`: TypeDefinition): ArrayBuffer[EventDefinition] = null // TODO

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

    def readMethods(`type`: TypeDefinition): ArrayBuffer[MethodDefinition] = null // TODO

    private def readPointers[TMember <: MemberDefinition](ptr: Table, table: Table, range: Range,
        members: ArrayBuffer[TMember], reader: (Int, ArrayBuffer[TMember]) => Unit) =
        for i <- 0 until range.length do
            moveTo(ptr, range.index + i)
            val rid = readTableIndex(table)
            moveTo(table, rid)

            reader(rid, members)


    def readCustomAttributes(owner: CustomAttributeProvider): ArrayBuffer[CustomAttribute] =
        initializeCustomAttributes()
        val custom_attributes = ArrayBuffer.empty[CustomAttribute]

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
    
    private def initializeMethods() = { } // TODO

    private def readMethod(method_rid: Int, methods: ArrayBuffer[MethodDefinition]) = { } // TODO

    private def readParameters(method: MethodDefinition, param_range: Range) = { } // TODO

    private def readParameterPointers(method: MethodDefinition, range: Range) = { } // TODO

    private def readParameter(param_rid: Int, method: MethodDefinition) = { } // TODO

    // private def readMethodSignature(signature: Int, method: MethodSignature) = { } // TODO

    // def readPInvokeInfo(method: MethodDefinition): PInvokeInfo = null // TODO

    private def initializePInvokes() = { } // TODO

    def hasGenericParameters(provider: GenericParameterProvider) =
        initializeGenericParameters()
        false // TODO
    
    def readGenericParameters(provider: GenericParameterProvider): ArrayBuffer[GenericParameter] = null // TODO

    private def readGenericParametersRange(range: Range, provider: GenericParameterProvider, generic_parameters: GenericParameterCollection) = { }

    private def initializeGenericParameters() = { } // TODO

    private def initializeRanges(table: Table, get_next: () => MetadataToken): HashMap[MetadataToken, Array[Range]] = null // TODO

    def hasGenericConstraints(generic_parameter: GenericParameter) = false // TODO

    def readGenericConstraints(generic_parameter: GenericParameter): GenericParameterConstraintCollection = null // TODO

    private def initializeGenericConstraints() = { } // TODO

    private def addGenericConstraintMapping(generic_parameter: Int, constraint: Row2[Int, MetadataToken]) = { } // TODO

    def hasOverrides(method: MethodDefinition) = false // TODO

    def readOverrides(method: MethodDefinition): ArrayBuffer[MethodReference] = null // TODO

    private def initializeOverrides() = { } // TODO

    private def addOverrideMapping(method_rid: Int, `override`: MetadataToken) = { } // TODO

    // def readMethodBody(method: MethodDefinition): MethodBody = null // TODO
    
    def readCodeSize(method: MethodDefinition): Int = 0

    // def readCallSize(token: MetadataToken): CallSite = null // TODO

    // def readVariables(local_var_token: MetadataToken, method: MethodDefinition): VariableDefinitionCollection = null // TODO

    def lookupToken(token: MetadataToken): MetadataTokenProvider =
        var rid = token.RID
        if (rid == 0)
            return null

        if (metadata_reader != null)
            return metadata_reader.lookupToken(token)

        val position = this.position
        val context = this._context

        val element: MetadataTokenProvider = token.tokenType match
            case TokenType.typeDef => getTypeDefinition(rid)
            case TokenType.typeRef => getTypeReference(rid)
            case TokenType.typeSpec => getTypeSpecification(rid)
            // case TokenType.field => getFieldDefinition(rid)
            // case TokenType.method => getMethodDefinition(rid)
            // case TokenType.memberRef => getMemberReference(rid)
            // case TokenType.methodSpec => getMethodSpecification(rid)
            case _ => null

        this.position = position
        this._context = context
        element

    def getFieldDefinition(rid: Int): FieldDefinition = null // TODO

    private def lookupField(rid: Int): FieldDefinition = null // TODO

    def getMethodDefinition(rid: Int): MethodDefinition = null // TODO

    private def lookupMethod(rid: Int): MethodDefinition = null // TODO

    // private def getMethodSpecification(rid: Int): MethodSpecification = null // TODO

    // private def readMethodSpecSignature(signature: Int, method: MethodReference): MethodSpecfication = null


    private def getMemberReference(rid: Int): MemberReference =
        if (!moveTo(Table.memberRef, rid))
            return null
        
        val token = readMetadataToken(CodedIndex.memberRefParent)
        val name = readString()
        val signature = readBlobIndex()

        val member = token.tokenType match
            case TokenType.typeDef | TokenType.typeRef | TokenType.typeSpec =>
                readTypeMemberReference(token, name, signature)
            case TokenType.method =>
                readMethodMemberReference(token, name, signature)
            case _ => throw OperationNotSupportedException()
        
        member.token = MetadataToken(TokenType.memberRef, rid)
        member
    
    private def readTypeMemberReference(`type`: MetadataToken, name: String, signature: Int) =
        val declaring_type = getTypeDefOrRef(`type`)
        if (!declaring_type.isArray)
            this._context = declaring_type
        
        val member = readMemberReferenceSignature(signature, declaring_type)
        member.name = name

        member

    private def readMemberReferenceSignature(signature: Int, declaring_type: TypeReference): MemberReference =
        val reader = readSignature(signature)

        val field_sig:Byte = 0x6

        if (reader.buffer(reader.position) == field_sig)
            reader.position += 1
            val field = FieldReference()
            // TODO
            field.asInstanceOf[MemberReference]
        else
            val method = MethodReference()
            // TODO
            method.asInstanceOf[MemberReference]

    private def readMethodMemberReference(token: MetadataToken, name: String, signature: Int) =
        val method = getMethodDefinition(token.RID)
        // TODO
        // this._context = method

        // val member = readMemberReferenceSignature(signature, method.declaringType)

        // member
        null

    private def initializeConstants() = { } // TODO

    def readConstantSignature(token: MetadataToken): TypeReference =
        if (token.tokenType != TokenType.signature)
            throw OperationNotSupportedException()
        
        if (token.RID == 0)
            return null
        
        if (!moveTo(Table.standAloneSig, token.RID))
            return null
        
        readFieldType(readBlobIndex())

    // TODO
    // def readConstant(owner: ConstantProvider): Any = null

    private def readConstantValue(etype: ElementType, signature: Int): Any =
        etype match
            case ElementType.`class` | ElementType.`object` => null
            case ElementType.string => readConstantString(signature)
            case _ => readConstantPrimitive(etype, signature)


    private def readConstantString(signature: Int) =
        val (blob, index, count) = getBlobView(signature)
        val actualCount = if ((count & 1) == 1) then count - 1 else count

        String(blob, index, actualCount, "UTF-16")

    private def readConstantPrimitive(`type`: ElementType, signature: Int): Any =
        null // TODO

    def getMemberReferences(): Iterable[MemberReference] =
        // TODO
        Array[MemberReference]()

    def getCustomAttributes(): Iterable[CustomAttribute] =
        // TODO
        Array[CustomAttribute]()

    def readCustomAttributeBlob(signature: Int) =
        readBlob(signature)

    // TODO
    def readCustomAttributesSignature(attribute: CustomAttribute): Unit =
        ()

    private def initializeMarshalInfos() = { } // TODO

    // def hasMarshalInfo(owner: MarshalInfoProvider): Boolean = false // TODO

    // def readMarshalInfo(owner: MarshalInfoProvider): MarshalInfo = null

    private def initializeSecurityDeclarations() = { }

    def hasSecurityDeclarations(owner: SecurityDeclarationProvider): Boolean = false // TODO

    def readSecurityDeclarations(owner: SecurityDeclarationProvider): ArrayBuffer[SecurityDeclaration] = null // TODO

    private def readSecurityDeclarationRange(range: Range, security_declaration: ArrayBuffer[SecurityDeclaration]) = { } // TODO

    def readSecurityDeclarationBlob(signature: Int): Array[Byte] =
        readBlob(signature)

    def readSecurityDeclarationSignature(declaration: SecurityDeclaration) = { } // TODO

    private def readXmlSecurityDeclaration(signature: Int, declaration: SecurityDeclaration) = { } // TODO


}

object MetadataReader {
    def isNested(attributes: TypeAttributes): Boolean =
        isNested(attributes.value)
    def isNested (attributes: Int): Boolean = 
        attributes & TypeAttributes.visibilityMask.value match
            case TypeAttributes.nestedAssembly.value |
                TypeAttributes.nestedFamANDAssem.value |
                TypeAttributes.nestedFamily.value |
                TypeAttributes.nestedFamORAssem.value |
                TypeAttributes.nestedPrivate.value |
                TypeAttributes.nestedPublic.value => true
            case _ => false
    private def addMapping[TKey, TValue](cache: HashMap[TKey, ArrayBuffer[TValue]], key: TKey, value: TValue) =
        val mapped = cache.get(key) match
            // the C# code doesn't add the new collection into the cache. That seems wrong.
            case None => ArrayBuffer[TValue]()
            case Some(value) => value
        mapped.addOne(value)
        mapped

    private def getFieldTypeSize(`type`: TypeReference): Int =
        `type`.etype match
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

    private def getEvent(`type`: TypeDefinition, token: MetadataToken): EventDefinition =
        if (token.tokenType != TokenType.event)
            throw IllegalArgumentException()
        null // TODO
        // getMember(`type`._events, token)
    

    // TODO
    // private def getProperty(`type`: TypeDefinition, token: MetadataToken): PropertyDefinition =
    //     if (token.tokenType != TokenType.property)
    //         throw IllegalArgumentException()
    //     getMember(`type`.properties, token)
    
    private def getMember[TMember <: MemberDefinition](members: ArrayBuffer[TMember], token: MetadataToken) =
        members.find((m) => m.metadataToken == token) match
            case Some(member) => member
            case None => throw IllegalArgumentException()

    private def isDeleted(member: MemberDefinition) = false
//        member.isSpecialName && member.name == "_Deleted"

    private def addRange(ranges: HashMap[MetadataToken, Array[Range]], owner: MetadataToken, range: Range) = { } // TODO

    private def rangeSize(ranges: Array[Range]) =
        ranges.view.map((r) => r.length).fold(0)((a, b) => a + b)           
}

sealed class SignatureReader(blob: Int, private val _reader: MetadataReader)
extends ByteBuffer(_reader.image.blobHeap.data) {
    this.position = blob
    private val sig_length = readCompressedInt32()
    private val start = position

}