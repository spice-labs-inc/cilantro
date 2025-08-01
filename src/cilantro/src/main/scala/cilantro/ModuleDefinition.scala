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
import scala.collection.mutable.ListBuffer
import io.spicelabs.cilantro.metadata.Table
import java.time.Instant
import java.time.temporal.TemporalUnit
import java.time.Duration

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
    def assemblyResolver_(value: AssemblyResolver) = _assembly_resolver = value

    def metadataResolver = _metadata_resolver
    def metadataResolver_(value: MetadataResolverTrait) = _metadata_resolver = value

    def metadataImporterProvider = _metadata_importer_provider
    def metadataImporterProvider_(value: MetadataImporterProvider) = _metadata_importer_provider = value

    def reflectionImporterProvider = _reflection_importer_provider
    def reflectionImporterProvider_(value: ReflectionImporterProvider) = _reflection_importer_provider = value

    def symbolStream = _symbol_stream
    def symbolStream_(value: FileInputStream) = _symbol_stream = value

    def symbolReaderProvider = _symbol_reader_provider
    def symbolReaderProvider_(value: SymbolReaderProvider) = _symbol_reader_provider = value

    def readSymbols = _read_symbols
    def readSymbols_(value: Boolean) = _read_symbols = value

    def throwIfSymbolsAreNotMatching = _throw_symbols_mismatch
    def throwIfSymbolsAreNotMatching_(value: Boolean) = _throw_symbols_mismatch = value

    def readWrite = _read_write
    def readWrite_(value: Boolean) = _read_write = value

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
    var isSymbolReaderProvider: SymbolReaderProvider = null

    // var symbol_reader: SymbolReader // TODO
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

    private var _custom_attributes: ListBuffer[CustomAttribute] = null
    private var _references: ListBuffer[AssemblyNameReference] = null
    private var _modules: ListBuffer[ModuleReference] = null
    // private var _resources: ListBuffer[Resource] = null // TODO
    // private var exported_types: ListBuffer[ExportedType] = null // TODO
    // private var _types: TypeDefinitionCollection = null // TODO

    var custom_infos: ListBuffer[CustomDebugInformation] = null
    // var _documents: ListBuffer[Document] = null // TODO

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
    def mvid_(value: UUID) = _mvid = value

    def hasImage = image != null

    // def hasSymbols = symbol_reader != null // TODO

    // def symbolReader = symbol_reader // TODO

    override def metadataScopeType = MetadataScopeType.moduleDefinition

    def assembly = _assembly

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
                _references = ListBuffer.empty[AssemblyNameReference]
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
                _modules = ListBuffer.empty[ModuleReference]
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
    //             _resources = ListBuffer.empty[Resource]
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


    // def hasTypes =
    //     if (_types != null)
    //         _types.length > 0
    //     else
    //         hasImage && image.hasTable(Table.typeDef)
    
    // def types =
    //     if (_types != null)
    //         _types
    //     else {
    //         if (hasImage)
    //             _types = read(this, (_, reader) => reader.readTypes ())
    //         else
    //             _types = TypeDefinitionCollection(this)
    //         _types
    //     }

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
    //             _exported_types = ListBuffer.empty[ExportedType]
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
            custom_infos = ListBuffer.empty[CustomDebugInformation]
        custom_infos

    // def hasDocuments =
    //     _documents != null && _documents.length > 0

    // def documents =
    //     if (_documents == null)
    //         _documents = ListBuffer.empty[Document]
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
            // TODO
        // if (symbol_reader != null)
        //     symbol_reader.close()
        if (assembly_resolver != null)
            assembly_resolver.dispose()


    private val module_lock = Object()


    def syncRoot = module_lock;

    def read[TItem](item: TItem, readr: (TItem, MetadataReader) => Unit) =
        module_lock.synchronized {
            val position = reader.position
//            val context = reader.context // TODO
            
            readr(item, reader)
            reader.position = position
//            reader.context = context // TODO
        }

    def read[TItem, TRet](item: TItem, read: (TItem, MetadataReader) => TRet): TRet =
        module_lock.synchronized {
            val position = reader.position
//            val context = reader.context // TODO

            val ret = read(item, reader)
            reader.position = position
//            reader.context = context // TODO
            ret
        }

    def read[TItem, TRet <: Any](variable: TRet, item: TItem, read: (TItem, MetadataReader) => TRet): TRet =
        module_lock.synchronized {
            if (variable != null)
                variable
            else {
                val position = reader.position
    //            val context = reader.context // TODO

                val ret = read(item, reader)
                reader.position = position
    //            reader.context = context // TODO
                ret
            }         
        }

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
        module.name_(name)
        module.kind = parameters.kind
        module.timestamp = parameters.timestamp match
            case Some(t) => t
            case _ => getTimeStamp()
        module._runtime = parameters.runtime
        module._architecture = parameters.architecture
        module.mvid_(UUID.randomUUID)
        module.attributes_(ModuleAttributesConstants.ilOnly.value)
        module.characteristics(0x8540)


        module
}


def checkName(name: String): String =
    if (name == null || name.length() == 0)
        throw IllegalArgumentException("name")
    name

def checkType[T](`type`: T): T =
    if (`type` == null)
        throw IllegalArgumentException("type")
    `type`

def checkParameters(parameters: Any) =
    if (parameters == null)
        throw IllegalArgumentException("parameters")

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
    
    