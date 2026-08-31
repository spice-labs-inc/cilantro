//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ModuleDefinition.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.cil.SymbolReaderProvider
import io.spicelabs.cilantro.cil.CustomDebugInformationProvider
import io.spicelabs.cilantro.cil.CustomDebugInformation
import java.io.FileInputStream
import io.spicelabs.cilantro.PE.Image
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.Failure
import io.spicelabs.cilantro.metadata.Table
import java.time.Instant
import java.time.Duration
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.PE.ImageReader
import io.spicelabs.cilantro.cil.ImageDebugHeader
import io.spicelabs.cilantro.cil.SymbolReader
import io.spicelabs.cilantro.cil.DefaultSymbolReaderProvider
import io.spicelabs.cilantro.metadata.Row2
import scala.collection.mutable.Stack
import io.spicelabs.cilantro.AssemblyNameReference.zeroVersion

type ModuleAttributes = Int
type ModuleCharacteristics = Int

enum ReadingMode(value: Int) {
    case immediate extends ReadingMode(1)
    case deferred extends  ReadingMode(2)
}

sealed class ReaderParameters(private var _readingMode: ReadingMode) {
    private var _assembly_resolver: Option[AssemblyResolver] = None
    private var _metadata_resolver: Option[MetadataResolverTrait] = None
    private var _metadata_importer_provider: Option[MetadataImporterProvider] = None
    private var _reflection_importer_provider: Option[ReflectionImporterProvider] = None
    private var _symbol_stream: Option[FileInputStream] = None
    private var _symbol_reader_provider: Option[SymbolReaderProvider] = None
    private var _read_symbols = false
    private var _throw_symbols_mismatch = false
    private var _projections = false
    private var _in_memory = false
    private var _read_write = false

    def readingMode = _readingMode
    def readingMode_(value: ReadingMode) = _readingMode = value

    def inMemory = _in_memory
    def inMemory_(value: Boolean) = _in_memory = value

    def assemblyResolver = _assembly_resolver
    def assemblyResolver_=(value: AssemblyResolver) = _assembly_resolver = Some(value)

    def metadataResolver = _metadata_resolver
    def metadataResolver_=(value: MetadataResolverTrait) = _metadata_resolver = Some(value)

    def metadataImporterProvider = _metadata_importer_provider
    def metadataImporterProvider_=(value: MetadataImporterProvider) = _metadata_importer_provider = Some(value)

    def reflectionImporterProvider = _reflection_importer_provider
    def reflectionImporterProvider_=(value: ReflectionImporterProvider) = _reflection_importer_provider = Some(value)

    def symbolStream = _symbol_stream
    def symbolStream_=(value: FileInputStream) = _symbol_stream = Some(value)

    def symbolReaderProvider = _symbol_reader_provider
    def symbolReaderProvider_=(value: SymbolReaderProvider) = _symbol_reader_provider = Some(value)

    def readSymbols = _read_symbols
    def readSymbols_=(value: Boolean) = _read_symbols = value

    def throwIfSymbolsAreNotMatching = _throw_symbols_mismatch
    def throwIfSymbolsAreNotMatching_=(value: Boolean) = _throw_symbols_mismatch = value

    def readWrite = _read_write
    def readWrite_=(value: Boolean) = _read_write = value

    def applyWindowsRuntimeProjections = _projections
    def applyWindowsRuntimeProjections_=(value: Boolean) = _projections = value

    def this() = this(ReadingMode.deferred)        
}

sealed class ModuleParameters() {
    private var _kind = ModuleKind.dll
    private var _runtime = parseRuntime(ModuleParameters.getCurrentRuntime())
    private var _timestamp: Option[Int] = None
    private var _architecture: TargetArchitecture = TargetArchitecture.i386
    private var _assembly_resolver: Option[AssemblyResolver] = None
    private var _metadata_resolver: Option[MetadataResolverTrait] = None
    private var _metadata_importer_provider: Option[MetadataImporterProvider] = None
    private var _reflection_importer_provider: Option[ReflectionImporterProvider] = None

    def kind = _kind
    def kind_(value: ModuleKind) = _kind = value

    def runtime = _runtime
    def runtime_(value: TargetRuntime) = _runtime = value

    def timestamp = _timestamp
    def timestamp_(value: Option[Int]) = _timestamp = value

    def architecture = _architecture
    def architecture_(value: TargetArchitecture) = _architecture = value

    def assemblyResolver = _assembly_resolver
    def assemblyResolver_(value:AssemblyResolver) = _assembly_resolver = Some(value)
    
    def metadataResolver = _metadata_resolver
    def metadataResolver_(value: MetadataResolverTrait) = _metadata_resolver = Some(value)

    def reflectionImporterProvider = _reflection_importer_provider
    def reflectionImporterProvider_(value: ReflectionImporterProvider) = _reflection_importer_provider = Some(value)
}

object ModuleParameters {
    // in the C# version, this takes it from the current runtime. Mocking this is probably good enough
    def getCurrentRuntime() = "v4.0.30319"

}

sealed class WriterParameters() { // TODO

}

sealed class ModuleDefinition() extends ModuleReference("", Some(MetadataToken(TokenType.module, 1))) with CustomAttributeProvider with CustomDebugInformationProvider with AutoCloseable {
    var image: Option[Image] = None
    var metadataSystem: MetadataSystem = MetadataSystem()
    var readingMode: ReadingMode = ReadingMode.immediate
    var symbolReaderProvider: Option[SymbolReaderProvider] = None

    var symbol_reader: Option[SymbolReader] = None
    var assembly_resolver: Option[Disposable[AssemblyResolver]] = None
    var metadata_resolver: Option[MetadataResolverTrait] = None
    private var type_system: Option[TypeSystem] = None
    var reader: Option[MetadataReader] = None
    var file_name: String = ""
    var runtime_version: String = ""
    var kind: ModuleKind = ModuleKind.dll

    var _projections: Option[WindowsRuntimeProjections] = None
    private var _metadata_kind: MetadataKind = MetadataKind.ecma335
    private var _runtime: TargetRuntime = TargetRuntime.net_4_0
    private var _architecture: TargetArchitecture = TargetArchitecture.i386
    private var _attributes: ModuleAttributes = 0
    private var _characteristics: ModuleCharacteristics = 0
    private var _mvid: UUID = UUID.randomUUID()


    var linker_version: Char = 8
    var subsystem_major: Char = 4
    var subsystem_minor: Char = 0
    var timestamp: Int = 0

    var _assembly: Option[AssemblyDefinition] = None
    var entry_point: Option[MethodDefinition] = None
    private var _entry_point_set = false

    var reflection_importer: Option[ReflectionImporter] = None
    var metadata_importer: Option[MetadataImporter] = None

    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None
    private var _references: Option[ArrayBuffer[AssemblyNameReference]] = None
    private var _modules: Option[ArrayBuffer[ModuleReference]] = None
    private var _resources: Option[ArrayBuffer[Resource]] = None
    private var _exported_types: Option[ArrayBuffer[ExportedType]] = None
    private var _types: Option[TypeDefinitionCollection] = None // TODO

    var custom_infos: Option[ArrayBuffer[CustomDebugInformation]] = None
    // var _documents: ArrayBuffer[Document] = null // TODO

    var metadata_builder: Option[MetadataBuilder] = None

    def isMain = kind != ModuleKind.netModule

    def moduleKind = kind
    def moduleKind_(value: ModuleKind) = kind = value

    def metadataKind = _metadata_kind
    def metadataKind_(value: MetadataKind) = _metadata_kind = value

    def projections = _projections
    def projections_(value: WindowsRuntimeProjections) = _projections = Some(value)

    def runtime = _runtime
    def runtime_=(value: TargetRuntime) = {
        _runtime = value
        runtime_version = _runtime.runtimeVersionString()


    }
    def runtimeVersion = runtime_version
    def runtimeVersion_(value: String) = {
        runtime_version = value
        _runtime = TargetRuntime.parseRuntime(runtime_version)

    }
    def targetArchitecture = _architecture
    def targetArchitecture_(value: TargetArchitecture) = _architecture = value

    def attributes = _attributes
    def attributes_(value: ModuleAttributes) = _attributes

    def characteristics = _characteristics
    def characteristics(value: ModuleCharacteristics) = _characteristics = value

    def fileName = file_name

    def mvid = _mvid
    def mvid_=(value: UUID) = _mvid = value

    def hasImage = image.isDefined

    // def hasSymbols = symbol_reader != null // TODO

    // def symbolReader = symbol_reader // TODO

    override def metadataScopeType = MetadataScopeType.moduleDefinition

    def assembly = _assembly
    def assembly_=(value: AssemblyDefinition) = _assembly = Some(value)

    def reflectionImporter = {
        // TODO
        // if (reflection_importer == null)
        //     reflection_importer = DefaultReflectionImporer(this)
        reflection_importer
    
    }
    def metadataImporter = {
        // TODO
        // if (metadata_importer == null)
        //     metadata_importer = DefaultMetadataImporter(this)
        metadata_importer
    
    }
    def assemblyResolver = {
        // TODO
        // if (assembly_resolver == null)
        //     assembly_resolver = Disposable.owned(DefaultAssemblyResolver())
        assembly_resolver

    }
    def metadataResolver = {
        // TODO
        // if (metadata_resolver == null)
        //     metadata_resolver = MetadataResolver(assemblyResolver)
        metadata_resolver

    }
    def typeSystem = {
        type_system match {
            case Some(ts) => ts
            case None =>
                val ts = TypeSystem.createTypeSystem(this)
                type_system = Some(ts)
                ts
        }

    }
    def hasAssemblyReferences = {
        if (_references.isDefined) {
            _references.exists(_.length > 0)
        }
        else {
            hasImage && image.exists(_.hasTable(Table.assemblyRef))

        }
    }
    def assemblyReferences = {
        _references match {
            case Some(r) => r
            case None =>
                val loaded = if (hasImage) read(ArrayBuffer.empty[AssemblyNameReference], this, (_, reader) => reader.readAssemblyReferences())
                             else ArrayBuffer.empty[AssemblyNameReference]
                _references = Some(loaded)
                loaded
        }
    
    }
    def hasModuleReferences = {
        if (_modules.isDefined) {
            _modules.exists(_.length > 0)
        }
        else {
            hasImage && image.exists(_.hasTable(Table.moduleRef))

    
        }
    }
    def moduleReferences = {
        _modules match {
            case Some(m) => m
            case None =>
                val loaded = if (hasImage) read(ArrayBuffer.empty[ModuleReference], this, (_, reader) => reader.readModuleReferences())
                             else ArrayBuffer.empty[ModuleReference]
                _modules = Some(loaded)
                loaded
        }

    }
    def hasResources = {
        if (_resources.isDefined) {
            _resources.exists(_.length > 0)
        }
        else {
            if (hasImage) {
                image.exists(_.hasTable(Table.manifestResource)) || read(this, (_, reader) => reader.hasFileResource())
            }
            else {
                false
            }
        }

    }
    def resources = {
        _resources match {
            case Some(r) => r
            case None =>
                val loaded = if (hasImage) read(ArrayBuffer.empty[Resource], this, (_, reader) => reader.readResources())
                             else ArrayBuffer.empty[Resource]
                _resources = Some(loaded)
                loaded
        }

    }
    def hasCustomAttributes = {
        if (_custom_attributes.isDefined) {
            _custom_attributes.exists(_.length > 0)
        }
        else getHasCustomAttributes(Some(this))

    }
    def customAttributes = {
        _custom_attributes match {
            case Some(v) => v
            case None =>
                val loaded = getCustomAttributes(ArrayBuffer.empty[CustomAttribute], Some(this))
                _custom_attributes = Some(loaded)
                loaded
            }
    }
    def hasTypes = {
        if (_types.isDefined) {
            _types.exists(_.length > 0)
        }
        else {
            hasImage && image.exists(_.hasTable(Table.typeDef))
    
        }
    }
    def types = {
        _types match {
            case Some(t) => t
            case None =>
                val loaded = if (hasImage) read(TypeDefinitionCollection(this), this, (_, reader) => reader.readTypes()) else TypeDefinitionCollection(this)
                _types = Some(loaded)
                loaded
        }

    }
    def hasExportedTypes = {
        if (_exported_types.isDefined) {
            _exported_types.exists(_.length > 0)
        }
        else {
            hasImage && image.exists(_.hasTable(Table.exportedType))

        }
    }
    def exportedTypes = {
        _exported_types match {
            case Some(e) => e
            case None =>
                val loaded = if (hasImage) read (ArrayBuffer.empty[ExportedType], this, (_, reader) => reader.readExportedTypes())
                             else ArrayBuffer.empty[ExportedType]
                _exported_types = Some(loaded)
                loaded
        }
    
    }
    def entryPoint: Option[MethodDefinition] = {
        if (!_entry_point_set) {
            entry_point = if (hasImage) reader.flatMap(r => r.readEntryPoint()) else None
            _entry_point_set = true
        }
        entry_point
    }

    def entryPoint_(value: MethodDefinition) = {
        entry_point = Some(value)
        _entry_point_set = true
    }

    def hasCustomDebugInformations = {
        custom_infos.exists(_.length > 0)

    }
    def customDebugInformations = {
        custom_infos match {
            case Some(i) => i
            case None =>
                val loaded = ArrayBuffer.empty[CustomDebugInformation]
                custom_infos = Some(loaded)
                loaded
        }

    // def hasDocuments =
    //     _documents != null && _documents.length > 0

    // def documents =
    //     if (_documents == null)
    //         _documents = ArrayBuffer.empty[Document]
    //     _documents

    

    }
    def this(image: Image) = {
        this()
        this.image = Some(image)
        this.kind = image.kind
        this.runtime_version = image.runtimeVersion.getOrElse(this.runtime_version)
        this._architecture =  image.architecture
        this._attributes = image.attributes
        this._characteristics = image.dllCharacteristics
        this.linker_version = image.linkerVersion
        this.subsystem_major = image.subSystemMajor
        this.subsystem_minor = image.subSystemMinor
        this.file_name = image.fileName.getOrElse("")
        this.timestamp = image.timeStamp
        this.reader = Some(MetadataReader(this))


    }
    def close(): Unit = {
        image.foreach(_.close())
        symbol_reader.foreach(_.close())
        assembly_resolver.foreach(_.dispose())
    }
    def hasTypeReference(fullName: String): Boolean = {
        hasTypeReference("", fullName)
    
    }
    def hasTypeReference(scope: String, fullName: String): Boolean = {
        checkFullName(fullName)

        if (!hasImage) {
            false
        }
        else     {
            getTypeReference(scope, fullName).isDefined

        }
    }
    def tryGetTypeReference(fullName: String): Option[TypeReference] = {
        tryGetTypeReference("", fullName)
    
    }
    def tryGetTypeReference(scope: String, fullName: String): Option[TypeReference] = {
        checkFullName(fullName)
        if (hasImage) {
            getTypeReference(scope, fullName)
        }
        else {
            None

        }
    }
    def getTypeReference(scope: String, fullName: String): Option[TypeReference] = {
        val r = Row2(scope, fullName)
        read(r, (row, reader) => reader.getTypeReference(row.col1, row.col2))


    }
    def getTypeReferences(): Iterable[TypeReference] = {
        if (!hasImage) {
            Array.empty[TypeReference]
        
        }
        read(this, (_, reader) => reader.getTypeReferences())

    }
    def getMemberReferences(): Iterable[MemberReference] = {
        if (!hasImage) {
            Array.empty[MemberReference]
        
        }
        read(this, (_, reader) => getMemberReferences())
    
    }
    def getCustomAttributes(): Iterable[CustomAttribute] = {
        if (!hasImage) {
            Array.empty[CustomAttribute]
        
        }
        read(this, (_, reader) => reader.getCustomAttributes())

    }
    def getType(fullName: String, runtimeName: Boolean): Option[TypeReference] = {
        // TODO
        if runtimeName then None else getType(fullName)

    }
    def getType(fullName: String): Option[TypeDefinition] = {
        checkFullName(fullName)

        val position = fullName.indexOf('/')
        if (position > 0) {
            getNestedType(fullName)

        }
        else {
            types.find(_.fullName == fullName)
        }

    }
    def getTypes(): Iterable[TypeDefinition] = {
        ModuleDefinition.getTypes(types)
    
    }
    def getNestedType(fullName: String): Option[TypeDefinition] = {
        val names = fullName.split('/')
        var thetype = getType(names(0))
        for i <- 1 until names.length do {
            thetype = thetype.flatMap(_.getNestedType(names(i)))
        }
        thetype
    }
    
    def resolve(field: FieldReference): FieldDefinition = {
        this.metadata_resolver.map(_.resolve(field)).getOrElse(throw OperationNotSupportedException())
    
    }
    def resolve(method: MethodReference): MethodDefinition = {
        this.metadata_resolver.map(_.resolve(method)).getOrElse(throw OperationNotSupportedException())

    }
    def resolve(`type`: TypeReference): TypeDefinition = {
        this.metadata_resolver.map(_.resolve(`type`)).getOrElse(throw OperationNotSupportedException())
    

    // there is a chunk of code to import references that are based on .NET types, this
    // probably doesn't fit here. The ones that work on TypeReferences should be done

    }
    def importReference(`type`: TypeReference): Option[TypeReference] = {
        importReference(`type`, None)
    
    }
    def importReference(`type`: TypeReference, context: Option[GenericParameterProvider]): Option[TypeReference] = {
        checkType(`type`)

        if (`type`.module.contains(this)) {
            return Some(`type`)
        
        }
        checkContext(context, this)

        None
        // TODO
        // metadataImporter.importReference(`type`, context)
    
    }
    def importReference(field: FieldReference): Option[FieldReference] = {
        importReference(field, None)
    
    }
    def importReference(field: FieldReference, context: Option[GenericParameterProvider]): Option[FieldReference] = {
        checkField(field)

        None
        // TODO
        // if (field.module == this)
        //     return field
        
        // checkContext(context, this)
        // metadataImporter.importReference(field, context)

    }
    def importReference(method: MethodReference): Option[MethodReference] = {
        importReference(method, None)
    
    }
    def importReference(method: MethodReference, context: Option[GenericParameterProvider]): Option[MethodReference] = {
        checkMethod(method)

        if (method.module.contains(this)) {
            return Some(method)
        
        }
        checkContext(context, this)

        None
        // TODO
        // metadataImporter.importReference(method, context)


    }
    def lookupToken(token: Int): Option[MetadataTokenProvider] = {
        lookupToken(MetadataToken(token))
    
    }
    def lookupToken(token: MetadataToken): Option[MetadataTokenProvider] = {
        read(token, (t, reader) => reader.lookupToken(t))
    

    }
    def immediateRead() = {
        if (hasImage) {
            readingMode = ReadingMode.immediate
            val moduleReader = ImmediateModuleReader(image.get)
            moduleReader.readModule(this, true)
    



        }
    }
    private val module_lock = Object()


    def syncRoot = module_lock;

    def read[TItem](item: TItem, readr: (TItem, MetadataReader) => Unit): Unit = {
        module_lock.synchronized {
            reader.foreach { r =>
                val position = r.position
                val context = r._context

                readr(item, r)
                r.position = position
                r._context = context
            }
        }

    }
    def read[TItem, TRet](item: TItem, readr: (TItem, MetadataReader) => TRet): TRet = {
        module_lock.synchronized {
            reader.map { r =>
                val position = r.position
                val context = r._context

                val ret = readr(item, r)
                r.position = position
                r._context = context
                ret
            }.getOrElse(throw OperationNotSupportedException("module has no reader"))
        }

    }
    def read[TItem, TRet <: Any](variable: TRet, item: TItem, readr: (TItem, MetadataReader) => TRet): TRet = {
        module_lock.synchronized {
            if (reader.isEmpty) {
                variable
            }
            else {
                reader.map { r =>
                    val position = r.position
                    val context = r._context

                    val ret = readr(item, r)
                    r.position = position
                    r._context = context
                    ret
                }.getOrElse(variable)
            }         
        }

    }
    def hasDebugHeader = image.exists(_.debugHeader.isDefined)

    def getDebugHeader() = image.flatMap(_.debugHeader).getOrElse(ImageDebugHeader())

    def readSymbols() : Unit = {
        if (file_name.length() == 0) {
            throw IllegalArgumentException()
        
        }
        val provider = DefaultSymbolReaderProvider(true)
        provider.getSymbolReader(this, file_name).foreach(sr => readSymbols(sr, true))

    }
    def readSymbols(reader: SymbolReader): Unit = {
        readSymbols(reader, true)

    }
    def readSymbols(reader: SymbolReader, throwIfSymbolsAreNotMatching: Boolean): Unit = {
        symbol_reader = Some(reader)

        if (!reader.processDebugHeader(getDebugHeader())) {
            symbol_reader = None
            if (throwIfSymbolsAreNotMatching) {
                throw new IllegalArgumentException("symbols")
            }
            return ()

        }
        if (hasImage && readingMode == ReadingMode.immediate) {
            image.foreach { img =>
                val immediate_reader = ImmediateModuleReader(img)
                immediate_reader.readSymbols(this)
            }
    
        }
    }
    def tryGetAssemblyNameReference(name_reference: AssemblyNameReference): Option[AssemblyNameReference] = {
        val references = assemblyReferences
        references.find((anr) => anr.equals(name_reference))

    }
    def tryGetCoreLibraryReference(): Option[AssemblyNameReference] = {
        val references = assemblyReferences

        references.find(ModuleDefinition.isCoreLibrary)

    }
    def isCoreLibrary(): Boolean = {
        if (assembly.isEmpty) {
            return false
        
        }
        if (!ModuleDefinition.isCoreLibrary(assembly.flatMap(_.name).getOrElse(throw OperationNotSupportedException()))) {
            return false
        
        }
        if (hasImage && read(this, (m, reader) => reader.image.getTableLength(Table.assemblyRef) > 0)) {
            return false
        }
        true
    
    }
    def isWindowsMetadata = this.metadataKind != MetadataKind.ecma335
}

object ModuleDefinition {
    def createModule(name: String, kind: ModuleKind): ModuleDefinition = {
        val mp = ModuleParameters()
        mp.kind_(kind)
        createModule(name, mp)
    
    }
    def createModule(name: String, parameters: ModuleParameters): ModuleDefinition = {
        checkName(name)
        checkParameters(parameters)
    
        val module = ModuleDefinition()
        module.name = name
        module.kind = parameters.kind
        module.timestamp = parameters.timestamp match {
            case Some(t) => t
            case _ => getTimeStamp()
        }
        module._runtime = parameters.runtime
        module._architecture = parameters.architecture
        module.mvid = UUID.randomUUID
        module.attributes_(ModuleAttributesConstants.ilOnly.value)
        module.characteristics(0x8540)


        module

    }
    def createAssemblyName(name: String): AssemblyNameDefinition = {
        var xname = name
        if (name.endsWith(".dll") || name.endsWith(".exe")) {
            xname = name.substring(0, name.length() - 4)
        
        }
        AssemblyNameDefinition(name, zeroVersion)

    }
    def readModule(fileName: String): Try[ModuleDefinition] = {
        readModule(fileName, ReaderParameters(ReadingMode.deferred))

    }
    def readModule(fileName: String, parameters: ReaderParameters): Try[ModuleDefinition] = {
        if (parameters.inMemory) {
            Failure(OperationNotSupportedException("in memory reading not supported"))
        }
        else {
            // The image owns the stream (Disposable.owned): lazy reads
            // (types, fields, methods, readBody) map new views over the
            // file channel after readModule returns, so the stream must
            // stay open for the module's lifetime. module.close() (or
            // image.close()) disposes it.
            Try {
                val stream = getFileStream(fileName)
                readModule(Disposable.owned(stream), fileName, parameters)
            }.flatten
        }
    
    }
    def readModule(stream: FileInputStream): Try[ModuleDefinition] = {
        val rp = ReaderParameters()
        rp.readingMode_(ReadingMode.deferred)
        readModule(stream, rp)
    
    }
    def readModule(stream: FileInputStream, parameters: ReaderParameters): Try[ModuleDefinition] = {
        Try(checkStream(stream)).flatMap(_ =>
            readModule(Disposable.notOwned(stream), "no file name", parameters))

    }
    def readModule(stream: Disposable[FileInputStream], fileName: String, parameters: ReaderParameters): Try[ModuleDefinition] = {
        Try(checkParameters(parameters)).flatMap(_ =>
            Try(ModuleReader.createModule(ImageReader.readImage(stream, fileName), parameters)))

    }
    def getFileStream(fileName: String) =         {
        FileInputStream(checkFileName(fileName))

    }
    private class IterateIt() {
        private var st = Stack[TypeDefinition]()

        def this(items: Seq[TypeDefinition]) = {
            this()
            pushReverse(items)
        
        }
        def this(item: TypeDefinition) = {
            this()
            st.push(item)

        }
        def tryNext(): Option[TypeDefinition] = {
            if (!st.isEmpty) {
                val next = st.pop()
                if (next.hasNestedTypes) {
                    for nt <- next.nestedTypes.reverse do {
                        st.push(nt)
                    }
                }
                return Some(next)
            }
            else {
                return None
        
            }
        }
        private def pushReverse(items: Seq[TypeDefinition]) = {
            for i <- items.length - 1 to 0 by -1 do {
                st.push(items(i))
            }
        }
    }

    def getTypes(types: ArrayBuffer[TypeDefinition]): Iterable[TypeDefinition] = {
        val iterator = IterateIt(types.to(Seq))
        LazyList.unfold(iterator) { state => 
            state.tryNext() match {
                case None => None
                case Some(value) => Some((value, state))
            
            }
        }
    
    }
    private def isCoreLibrary(reference: AssemblyNameReference): Boolean = {
        val name = reference.name
        name == mscorlib || name == system_runtime || name == system_private_corelib || name == netstandard

    }
    val mscorlib = "mscorlib"
    val system_runtime = "System.Runtime"
    val system_private_corelib = "System.Private.CoreLib"
    val netstandard = "netstandard"
}


def checkName(name: String): String = {
    if (name.length() == 0) {
        throw IllegalArgumentException("name")
    }
    name

}
def checkFileName(fileName: String): String = {
    if (fileName.length() == 0) {
        throw IllegalArgumentException("fileName")
    }
    fileName

}
def checkStream(stream: FileInputStream): FileInputStream = {
    stream

}
def checkModule(module: ModuleDefinition) = {
    module

}
def checkFullName(fullName: String) = {
    if (fullName.length() == 0) {
        throw IllegalArgumentException("fullName")
    }
    fullName

}
def checkType[T](`type`: T): T = {
    `type`

}
def checkField(field: Object) = {
    field

}
def checkMethod(method: Object) = {
    method

}
def checkParameters(parameters: Any) = {
    Option(parameters) match {
        case None => throw IllegalArgumentException("parameters")
        case Some(p) => p
    }
}
def checkContext(context: Option[GenericParameterProvider], module: ModuleDefinition): Unit = {
    if (context.exists(_.module.exists(_ != module))) {
        throw new IllegalArgumentException()

    }
}
def getTimeStamp() = {
    val now = Instant.now
    val `then` = Instant.parse("1970-01-01")
    val delta = Duration.between(`then`, now)
    delta.toSeconds().toInt


}
def fromHexString(s: String): Array[Byte] = {
    val size = s.length / 2
    var arr = Array.ofDim[Byte](size)
    for i <- 0 until s.length / 2 do {
        val pair = s.substring(2 * i, 2 * i + 2)
        val iVal = Integer.parseInt(pair, 16)
        arr(i) = iVal.toByte
    }
    arr

}
def parseRuntime(self: String) = {
    if (self.isEmpty()) {
        TargetRuntime.net_4_0
    
    }
    self.charAt(0) match {
        case '1' =>
            if self.charAt(3) == '0' then TargetRuntime.net_1_0 else TargetRuntime.net_1_1
        case '2' => TargetRuntime.net_2_0
        case _ => TargetRuntime.net_4_0

    }
}
def runtimeVersionString(runtime: TargetRuntime) = {
    runtime match {
        case TargetRuntime.net_1_0 => "v1.0.3705"
        case TargetRuntime.net_1_1 => "v1.1.4322"
        case TargetRuntime.net_2_0 => "v2.0.50727"
        case _ => "v4.0.30319"
    
    }
}
def mixinRead(o: Any): Unit = ()