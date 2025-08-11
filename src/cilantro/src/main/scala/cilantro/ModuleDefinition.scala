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
import java.lang.annotation.Target
import io.spicelabs.cilantro.PE.Image
import io.spicelabs.cilantro.MetadataToken.zero
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import io.spicelabs.cilantro.metadata.Table
import java.time.Instant
import java.time.temporal.TemporalUnit
import java.time.Duration
import java.io.Reader
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
    private var _assembly_resolver: AssemblyResolver = null
    private var _metadata_resolver: MetadataResolverTrait = null
    private var _metadata_importer_provider: MetadataImporterProvider = null
    private var _reflection_importer_provider: ReflectionImporterProvider = null
    private var _symbol_stream: FileInputStream = null
    private var _symbol_reader_provider: SymbolReaderProvider = null
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
    def assemblyResolver_=(value: AssemblyResolver) = _assembly_resolver = value

    def metadataResolver = _metadata_resolver
    def metadataResolver_=(value: MetadataResolverTrait) = _metadata_resolver = value

    def metadataImporterProvider = _metadata_importer_provider
    def metadataImporterProvider_=(value: MetadataImporterProvider) = _metadata_importer_provider = value

    def reflectionImporterProvider = _reflection_importer_provider
    def reflectionImporterProvider_=(value: ReflectionImporterProvider) = _reflection_importer_provider = value

    def symbolStream = _symbol_stream
    def symbolStream_=(value: FileInputStream) = _symbol_stream = value

    def symbolReaderProvider = _symbol_reader_provider
    def symbolReaderProvider_=(value: SymbolReaderProvider) = _symbol_reader_provider = value

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
    private var _assembly_resolver: AssemblyResolver = null
    private var _metadata_resolver: MetadataResolverTrait = null
    private var _metadata_importer_provider: MetadataImporterProvider = null
    private var _reflection_importer_provider: ReflectionImporterProvider = null

    def kind = _kind
    def kind_(value: ModuleKind) = _kind = value

    def runtime = _runtime
    def runtime_(value: TargetRuntime) = _runtime = value

    def timestamp = _timestamp
    def timestamp_(value: Option[Int]) = _timestamp = value

    def architecture = _architecture
    def architecture_(value: TargetArchitecture) = _architecture = value

    def assemblyResolver = _assembly_resolver
    def assemblyResolver_(value:AssemblyResolver) = _assembly_resolver = value
    
    def metadataResolver = _metadata_resolver
    def metadataResolver_(value: MetadataResolverTrait) = _metadata_resolver = value

    def reflectionImporterProvider = _reflection_importer_provider
    def reflectionImporterProvider_(value: ReflectionImporterProvider) = _reflection_importer_provider = value
}

object ModuleParameters {
    // in the C# version, this takes it from the current runtime. Mocking this is probably good enough
    def getCurrentRuntime() = "v4.0.30319"

}

sealed class WriterParameters() { // TODO

}

sealed class ModuleDefinition() extends ModuleReference(null, MetadataToken(TokenType.module, 1)) with CustomAttributeProvider with CustomDebugInformationProvider with AutoCloseable {
    var image: Image = null
    // var metadataSystem: MetadataSystem = MetadataSystem() // TODO
    var readingMode: ReadingMode = ReadingMode.immediate
    var symbolReaderProvider: SymbolReaderProvider = null

    var symbol_reader: SymbolReader = null
    var assembly_resolver: Disposable[AssemblyResolver] = null
    var metadata_resolver: MetadataResolverTrait = null
    // var type_system: TypeSystem // TODO
    var reader: MetadataReader = null // TODO
    var file_name: String = null
    var runtime_version: String = null
    var kind: ModuleKind = ModuleKind.dll

    // var _projections: WindowsRuntimeProjections // TODO
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

    var _assembly: AssemblyDefinition = null
    // var entry_point: MethodDefinition // TODO
    private var _entry_point_set = false

    var reflection_importer: ReflectionImporter = null
    var metadata_importer: MetadataImporter = null

    private var _custom_attributes: ArrayBuffer[CustomAttribute] = null
    private var _references: ArrayBuffer[AssemblyNameReference] = null
    private var _modules: ArrayBuffer[ModuleReference] = null
    // private var _resources: ArrayBuffer[Resource] = null // TODO
    // private var exported_types: ArrayBuffer[ExportedType] = null // TODO
    private var _types: TypeDefinitionCollection = null // TODO

    var custom_infos: ArrayBuffer[CustomDebugInformation] = null
    // var _documents: ArrayBuffer[Document] = null // TODO

    var metadata_builder: MetadataBuilder = null

    def isMain = kind != ModuleKind.netModule

    def moduleKind = kind
    def moduleKind_(value: ModuleKind) = kind = value

    def metadataKind = _metadata_kind
    def metadataKind_(value: MetadataKind) = _metadata_kind = value

    // TODO
    // def projections = _projections
    // def projections_(value: WindowsRuntimeProjections) = _projections = value

    def runtimeVersion = runtime_version
    def runtimeVersion_(value: String) = runtime_version = value

    def targetArchitecture = _architecture
    def targetArchitecture_(value: TargetArchitecture) = _architecture = value

    def attributes = _attributes
    def attributes_(value: ModuleAttributes) = _attributes

    def characteristics = _characteristics
    def characteristics(value: ModuleCharacteristics) = _characteristics = value

    def fileName = file_name

    def mvid = _mvid
    def mvid_=(value: UUID) = _mvid = value

    def hasImage = image != null

    // def hasSymbols = symbol_reader != null // TODO

    // def symbolReader = symbol_reader // TODO

    override def metadataScopeType = MetadataScopeType.moduleDefinition

    def assembly = _assembly
    def assembly_=(value: AssemblyDefinition) = _assembly = value

    def reflectionImporter =
        // TODO
        // if (reflection_importer == null)
        //     reflection_importer = DefaultReflectionImporer(this)
        reflection_importer
    
    def metadataImporter =
        // TODO
        // if (metadata_importer == null)
        //     metadata_importer = DefaultMetadataImporter(this)
        metadata_importer
    
    def assemblyResolver =
        // TODO
        // if (assembly_resolver == null)
        //     assembly_resolver = Disposable.owned(DefaultAssemblyResolver())
        assembly_resolver

    def metadataResolver =
        // TODO
        // if (metadata_resolver == null)
        //     metadata_resolver = MetadataResolver(assemblyResolver)
        metadata_resolver

    // TODO
    // def typeSystem =
    //     if (type_system == null)
    //         type_system = TypeSystem.createTypesystem(this)
    //     type_system

    def hasAssemblyReferences =
        if (_references != null)
            _references.length > 0
        else
            hasImage && image.hasTable(Table.assemblyRef)

    def assemblyReferences =
        if (_references != null)
            _references
        else {
            if (hasImage)
                _references = read(this, (_, reader) => reader.readAssemblyReferences())
            else
                _references = ArrayBuffer.empty[AssemblyNameReference]
            _references
        }
    
    def hasModuleReferences =
        if (_modules != null)
            _modules.length > 0
        else
            hasImage && image.hasTable(Table.moduleRef)

    
    def moduleReferences =
        if (_modules != null)
            _modules
        else {
            if (hasImage)
                _modules = read(this, (_, reader) => reader.readModuleReferences())
            else
                _modules = ArrayBuffer.empty[ModuleReference]
            _modules
        }

    // TODO
    // def hasResources =
    //     if (_resources !- null)
    //         _resources.length > 0
    //     else {
    //         if (hasImage)
    //             image.hasTable(Table.manifestResource) || read(this, (_, reader) => reader.hasFileResource())
    //         else
    //             false
    //     }

    // def resources =
    //     if (_resources != null)
    //         _resources
    //     else {
    //         if (hasImage)
    //             _resources = read(this, (_, reader) => reader.readResources())
    //         else 
    //             _resources = ArrayBuffer.empty[Resource]
    //         _resources
    //     }

    def hasCustomAttributes =
        if (_custom_attributes != null)
            _custom_attributes.length > 0
        else getHasCustomAttributes(this)

    def customAttributes =
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, this)
            _custom_attributes


    def hasTypes =
        if (_types != null)
            _types.length > 0
        else
            hasImage && image.hasTable(Table.typeDef)
    
    def types =
        if (_types != null)
            _types
        else {
            if (hasImage)
                _types = read(this, (_, reader) => reader.readTypes ())
            else
                _types = TypeDefinitionCollection(this)
            _types
        }

    // def hasExportedTypes =
    //     if (_exported_types != null)
    //         _exported_types.length > 0
    //     else
    //         hasImage && image.hasTable(Table.exportedType)

    // def exportedTypes =
    //     if (_exported_types != null)
    //         _exported_types
    //     else {
    //         if (hasImage)
    //             _exported_types = read (this, (_, reader) => reader.readExportedTypes())
    //         else
    //             _exported_types = ArrayBuffer.empty[ExportedType]
    //         _exported_types
    //     }

    // def entryPoint =
    //     if (_entry_point_set)
    //         _entry_point
    //     else {
    //         if (hasImage)
    //             _entry_point = read(this, (_, reader) => reader.readEntryPoint())
    //         else
    //             _entry_point = null
    //         _entry_point_set = true
    //         _entry_point
    // def entryPoint_(value: MethodDefinition) =
    //     _entry_point = value
    //     _entry_point_set = true

    def hasCustomDebugInformations =
        custom_infos != null && custom_infos.length > 0

    def customDebugInformations =
        if (custom_infos == null)
            custom_infos = ArrayBuffer.empty[CustomDebugInformation]
        custom_infos

    // def hasDocuments =
    //     _documents != null && _documents.length > 0

    // def documents =
    //     if (_documents == null)
    //         _documents = ArrayBuffer.empty[Document]
    //     _documents

    

    def this(image: Image) =
        this()
        this.image = image
        this.kind = image.kind
        this.runtime_version = image.runtimeVersion
        this._architecture =  image.architecture
        this._attributes = image.attributes
        this._characteristics = image.dllCharacteristics
        this.linker_version = image.linkerVersion
        this.subsystem_major = image.subSystemMajor
        this.subsystem_minor = image.subSystemMinor
        this.file_name = image.fileName
        this.timestamp = image.timeStamp
        // TODO
//        this.reader = MetadataReader(this)


    def close(): Unit =
        if (image != null)
            image.close()
        if (symbol_reader != null)
            symbol_reader.close()
        if (assembly_resolver != null)
            assembly_resolver.dispose()

    def hasTypeReference(fullName: String): Boolean =
        hasTypeReference("", fullName)
    
    def hasTypeReference(scope: String, fullName: String): Boolean =
        checkFullName(fullName)

        if (!hasImage)
            false
        else    
            getTypeReference(scope, fullName) != null

    def tryGetTypeReference(fullName: String): Option[TypeReference] =
        tryGetTypeReference("", fullName)
    
    def tryGetTypeReference(scope: String, fullName: String): Option[TypeReference] =
        checkFullName(fullName)
        if (hasImage)
            val `type` = getTypeReference(scope, fullName)
            if (`type` != null)
                Some(`type`)
            else
                None
        else
            None

    def getTypeReference(scope: String, fullName: String): TypeReference =
        val r = Row2(scope, fullName)
        read(r, (row, reader) => reader.getTypeReference(row.col1, row.col2))


    def getTypeReferences(): Iterable[TypeReference] =
        if (!hasImage)
            Array.empty[TypeReference]
        
        read(this, (_, reader) => reader.getTypeReferences())

    def getMemberReferences(): Iterable[MemberReference] =
        if (!hasImage)
            Array.empty[MemberReference]
        
        read(this, (_, reader) => getMemberReferences())
    
    def getCustomAttributes(): Iterable[CustomAttribute] =
        if (!hasImage)
            Array.empty[CustomAttribute]
        
        read(this, (_, reader) => reader.getCustomAttributes())

    def getType(fullName: String, runtimeName: Boolean): TypeReference =
        // TODO
        if runtimeName then null else getType(fullName)

    def getType(fullName: String): TypeDefinition =
        checkFullName(fullName)

        val position = fullName.indexOf('/')
        if (position > 0)
            getNestedType(fullName)
        
        // TODO
        null
    
    // TODO
    def getTypes(): Iterable[TypeDefinition] =
        ModuleDefinition.getTypes(types)
    
    def getNestedType (fullName: String): TypeDefinition = null
        // TODO
        // val names = fullName.split('/')
        // var `type` = getType(names(0))

        // if (`type` == null)
        //     return null
        
        // for i <- 1 to names.length do
        //     val nested_type = `type`.getNestedType(names(i))
        //     if (nested_type == null)
        //         return null
        //     `type` = nested_type
        // `type`
    
    def resolve(field: FieldReference): FieldDefinition =
        null // TODO
    
    def resolve(method: MethodReference): MethodDefinition =
        null // TODO

    def resolve(`type`: TypeReference): TypeDefinition =
        null // TODO
    

    // there is a chunk of code to import references that are based on .NET types, this
    // probably doesn't fit here. The ones that work on TypeReferences should be done

    def importReference(`type`: TypeReference): TypeReference =
        importReference(`type`, null)
    
    def importReference(`type`: TypeReference, context: GenericParameterProvider): TypeReference =
        checkType(`type`)

        if (`type`.module == this)
            return `type`
        
        checkContext(context, this)

        null
        // TODO
        // metadataImporter.importReference(`type`, context)
    
    def importReference(field: FieldReference): FieldReference =
        importReference(field, null)
    
    def importReference(field: FieldReference, context: GenericParameterProvider): FieldReference =
        checkField(field)

        null
        // TODO
        // if (field.module == this)
        //     return field
        
        // checkContext(context, this)
        // metadataImporter.importReference(field, context)

    def importReference(method: MethodReference): MethodReference =
        importReference(method, null)
    
    def importReference(method: MethodReference, context: GenericParameterProvider): MethodReference =
        checkMethod(method)

        if (method.module == this)
            return method
        
        checkContext(context, this)

        null
        // TODO
        // metadataImporter.importReference(method, context)


    def lookupToken(token: Int): MetadataTokenProvider =
        lookupToken(MetadataToken(token))
    
    def lookupToken(token: MetadataToken): MetadataTokenProvider =
        read(token, (t, reader) => reader.lookupToken(t))
    

    def immediateRead() =
        if (hasImage)
            readingMode = ReadingMode.immediate
            val moduleReader = ImmediateModuleReader(image)
            moduleReader.readModule(this, true)
    



    private val module_lock = Object()


    def syncRoot = module_lock;

    def read[TItem](item: TItem, readr: (TItem, MetadataReader) => Unit): Unit =
        module_lock.synchronized {
            val position = reader.position
            val context = reader._context
            
            readr(item, reader)
            reader.position = position
            reader._context = context
        }

    def read[TItem, TRet](item: TItem, readr: (TItem, MetadataReader) => TRet): TRet =
        module_lock.synchronized {
            val position = reader.position
            val context = reader._context

            val ret = readr(item, reader)
            reader.position = position
            reader._context = context
            ret
        }

    def read[TItem, TRet <: Any](variable: TRet, item: TItem, readr: (TItem, MetadataReader) => TRet): TRet =
        module_lock.synchronized {
            if (variable != null)
                variable
            else {
                val position = reader.position
                val context = reader._context

                val ret = readr(item, reader)
                reader.position = position
                reader._context = context
                ret
            }         
        }

    def hasDebugHeader = image != null && image.debugHeader != null

    def getDebugHeader() = if image.debugHeader != null then image.debugHeader else ImageDebugHeader()

    def readSymbols() : Unit =
        if (file_name == null || file_name.length() == 0)
            throw IllegalArgumentException()
        
        val provider = DefaultSymbolReaderProvider(true)
        readSymbols(provider.getSymbolReader(this, file_name), true)

    def readSymbols(reader: SymbolReader): Unit =
        readSymbols(reader, true)

    def readSymbols(reader: SymbolReader, throwIfSymbolsAreNotMatching: Boolean): Unit =
        if (reader == null)
            throw IllegalArgumentException("reader")
        
        symbol_reader = reader

        if (!symbol_reader.processDebugHeader(getDebugHeader()))
            symbol_reader = null
            if (throwIfSymbolsAreNotMatching)
                throw new IllegalArgumentException("symbols")
            return ()

        if (hasImage && readingMode == ReadingMode.immediate)
            val immediate_reader = ImmediateModuleReader(image)
            immediate_reader.readSymbols(this)
}

object ModuleDefinition {
    def createModule(name: String, kind: ModuleKind): ModuleDefinition =
        val mp = ModuleParameters()
        mp.kind_(kind)
        createModule(name, mp)
    
    def createModule(name: String, parameters: ModuleParameters): ModuleDefinition =
        checkName(name)
        checkParameters(parameters)
    
        val module = ModuleDefinition()
        module.name = name
        module.kind = parameters.kind
        module.timestamp = parameters.timestamp match
            case Some(t) => t
            case _ => getTimeStamp()
        module._runtime = parameters.runtime
        module._architecture = parameters.architecture
        module.mvid = UUID.randomUUID
        module.attributes_(ModuleAttributesConstants.ilOnly.value)
        module.characteristics(0x8540)


        module

    def createAssemblyName(name: String): AssemblyNameDefinition =
        var xname = name
        if (name.endsWith(".dll") || name.endsWith(".exe"))
            xname = name.substring(0, name.length() - 4)
        
        AssemblyNameDefinition(name, zeroVersion)

    def readModule(fileName: String): ModuleDefinition =
        readModule(fileName, ReaderParameters(ReadingMode.deferred))

    def readModule(fileName: String, parameters: ReaderParameters): ModuleDefinition =
        val stream = getFileStream(fileName)
        if (parameters.inMemory)
            throw OperationNotSupportedException("in memory reading not supported")
        
        try
            readModule(Disposable.owned(stream), fileName, parameters)
        catch
            case err: Exception =>
                stream.close()
                throw err
    
    def readModule(stream: FileInputStream): ModuleDefinition =
        val rp = ReaderParameters()
        rp.readingMode_(ReadingMode.deferred)
        readModule(stream, rp)
    
    def readModule(stream: FileInputStream, parameters: ReaderParameters): ModuleDefinition =
        checkStream(stream)
        
        readModule(Disposable.notOwned(stream), "no file name", parameters)

    def readModule(stream: Disposable[FileInputStream], fileName: String, parameters: ReaderParameters): ModuleDefinition =
        checkParameters(parameters)
        ModuleReader.createModule(ImageReader.readImage(stream, fileName), parameters)


    def getFileStream(fileName: String) =        
        FileInputStream(checkFileName(fileName))

    private class IterateIt() {
        private var st = Stack[TypeDefinition]()

        def this(items: Seq[TypeDefinition]) =
            this()
            pushReverse(items)
        
        def this(item: TypeDefinition) =
            this()
            st.push(item)

        def tryNext(): Option[TypeDefinition] =
            if (!st.isEmpty)
                val next = st.pop()
                if (next.hasNestedTypes)
                    for nt <- next.nestedTypes.reverse do
                        st.push(nt)
                return Some(next)
            else
                return None
        
        private def pushReverse(items: Seq[TypeDefinition]) =
            for i <- items.length - 1 to 0 do
                st.push(items(i))
    }

    def getTypes(types: ArrayBuffer[TypeDefinition]): Iterable[TypeDefinition] =
        val iterator = IterateIt(types.to(Seq))
        LazyList.unfold(iterator) { state => 
            state.tryNext() match
                case None => None
                case Some(value) => Some((value, state))
            
        }
}


def checkName(name: String): String =
    if (name == null || name.length() == 0)
        throw IllegalArgumentException("name")
    name

def checkFileName(fileName: String): String =
    if (fileName == null || fileName.length() == 0)
        throw IllegalArgumentException("fileName")
    fileName

def checkStream(stream: FileInputStream): FileInputStream =
    if (stream == null)
        throw IllegalArgumentException("stream")
    stream

def checkModule(module: ModuleDefinition) =
    if (module == null)
        throw new IllegalArgumentException("module")
    module

def checkFullName(fullName: String) =
    if (fullName == null || fullName.length() == 0)
        throw IllegalArgumentException("fullName")
    fullName

def checkType[T](`type`: T): T =
    if (`type` == null)
        throw IllegalArgumentException("type")
    `type`

def checkField(field: Object) =
    if (field == null)
        throw new IllegalArgumentException("field")
    field

def checkMethod(method: Object) =
    if (method == null)
        throw new IllegalArgumentException("method")
    method

def checkParameters(parameters: Any) =
    if (parameters == null)
        throw IllegalArgumentException("parameters")

def checkContext(context: GenericParameterProvider, module: ModuleDefinition): Unit =
    if (context != null && context.module != module)
        throw new IllegalArgumentException()

def getTimeStamp() =
    val now = Instant.now
    val `then` = Instant.parse("1970-01-01")
    val delta = Duration.between(`then`, now)
    delta.toSeconds().toInt


def fromHexString(s: String): Array[Byte] =
    val size = s.length / 2
    var arr = Array.ofDim[Byte](size)
    for i <- 0 to s.length / 2 do
        val pair = s.substring(2 * i, 2 * i + 2)
        val iVal = Integer.parseInt(pair, 16)
        arr(i) = iVal.toByte
    arr

def parseRuntime(self: String) =
    if (self == null || self.isEmpty())
        TargetRuntime.net_4_0
    
    self.charAt(0) match
        case '1' =>
            if self.charAt(3) == '0' then TargetRuntime.net_1_0 else TargetRuntime.net_1_1
        case '2' => TargetRuntime.net_2_0
        case _ => TargetRuntime.net_4_0

def runtimeVersionString(runtime: TargetRuntime) =
    runtime match
        case TargetRuntime.net_1_0 => "v1.0.3705"
        case TargetRuntime.net_1_1 => "v1.1.4322"
        case TargetRuntime.net_2_0 => "v2.0.50727"
        case _ => "v4.0.30319"
    
def mixinRead(o: Any): Unit = ()