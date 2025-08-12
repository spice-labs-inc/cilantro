//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Cil/Symbols.cs

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.*
import java.io.FileInputStream
import scala.collection.mutable.ArrayBuffer
import scala.util.boundary, boundary.break
import io.spicelabs.cilantro.PE.BinaryStreamReader

sealed class ImageDebugDirectory {
    var characteristics: Int = 0
    var timeDataStamp: Int = 0
    var majorVersion: Short = 0
    var minorVersion: Short = 0
    var `type`: ImageDebugType = ImageDebugType.codeView
    var sizeOfData: Int = 0
    var addressOfRawData: Int = 0
    var pointerToRawData: Int = 0
}

object ImageDebugDirectory {
    val size = 28
}

enum ImageDebugType(val value: Int) {
    case codeView extends ImageDebugType(2)
    case deterministic extends ImageDebugType(16)
    case embeddedPortablePdb extends ImageDebugType(17)
    case pdbCheckSum extends ImageDebugType(19)
}

object ImageDebugType {
    def fromOrdinalValue(value: Int) =
        ImageDebugType.values.find(x => {x.value == value}) match
        case Some(result) => result
        case None => throw IllegalArgumentException(s"value $value not found in ImageDebugType")  
}

sealed class ImageDebugHeader(private val _entries: Array[ImageDebugHeaderEntry]) {

    def hasEntries = _entries.isEmpty

    def entties = _entries

    def this() =
        this(Array.ofDim[ImageDebugHeaderEntry](0))
    
    def this(entry: ImageDebugHeaderEntry) =
        this(Array(entry))

}

sealed class ImageDebugHeaderEntry(private val _directory: ImageDebugDirectory, private val _data: Array[Byte]) {

}


trait SymbolReader extends AutoCloseable { // TODO
    // def getWriterProvider(): SymbolWriterProvider
    def processDebugHeader(header: ImageDebugHeader): Boolean
    // def read(method: MethodDefinition): methodDebugInformation
    // def read(provider: CustomDebugInformationProvider): ArrayBuffer[CustomDebugInformation]
}

trait SymbolReaderProvider {
    def getSymbolReader(module: ModuleDefinition, fileName: String) : SymbolReader
    def getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream) : SymbolReader
}

class DefaultSymbolReaderProvider(private val throwIfNoSymbol: Boolean) extends SymbolReaderProvider {
    def this() = this(true)
    override def getSymbolReader(module: ModuleDefinition, fileName: String): SymbolReader =
        if (module.image.hasDebugTables())
            return null
        
        // TODO
        // if (module.hasDebugHeader)
        //     val header = module.getDebugHeader()
        //     val entry = header.getEmbeddedPortablePdbEntry()
        //     if (entry != null)
        //         return EmbeddedPortablePdfReaderProvider().getSymbolReader(module, fileName)
        
        // val pdb_file_name = getPdbFileName(fileName)
        // if (Files.exists(pdb_file_name))
        //     if (isPortablePdb(pdb_file_name))
        //         PortablePdbReaderProvider().getSymbolReader(module, fileName)
        //     try
        //         SymbolProvider.getReaderProvider(SymbolKind.nativePdb).getSymbolReader(module, fileName)
        //     catch
        //         case _ =>
            
        // val mdb_file_name = getMdbFileName(fileName)
        // if (Files.exists(mdb_file_name))
        //     try 
        //         SymbolProvider.getReaderProvider(SymbolKind.mdb).getSymbolReader(module, fileName)
            
        //     catch
        //         case _ =>

        if (throwIfNoSymbol)
            throw IllegalArgumentException(s"No symbol found for $fileName")
        
        null

    def getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream) : SymbolReader =
        if (module.image.hasDebugTables())
            return null

        // TODO        
        // if (module.hasDebugHeader)
        //     val header = module.getDebugHeader()
        //     val entry = header.getEmbeddedPortablePdbEntry()
        //     if (entry != null)
        //         return EmbeddedPortablePdbReaderProvider().getSymbolReader(module, "")
            
        // checkStream(symbolStream)

        // val position = symbolStream.getChannel().position()
        // val portablePdbHeader = 0x424a5342

        // val reader = BinaryStreamReader(symbolStream)
        // val intHeader = reader.readInt32()

        // symbolStream.getChannel().position(position)

        // if (intHeader == portablePdbHeader)
        //     PortablePdbReaderProvider().getSymbolReader(module, symbolStream)
        
        // val nativePdbHeader = "Microsoft C/C++ MSF 7.00"
        // val bytesHeader = reader.readBytes(nativePdbHeader.length())
        // symbolStream.getChannel().position(position)
        // var isNativePdb = true

        // boundary {
        //     for i <- 1 until bytesHeader.length do
        //         if (bytesHeader(i) != nativePdbHeader.charAt(i).toByte)
        //             isNativePdb = false
        //             break()
                
        // }

        // if (isNativePdb)
        //     try 
        //         SymbolProvider.getReaderProvider(SymbolKind.nativePdb).getSymbolReader(module, symbolStream)
        //     catch
        //         case _ => null
        
        // val mdbHeader = 0x45e82623fd7fa614L

        // val longHeader = reader.readInt64()
        // symbolStream.getChannel().position(position)

        // if (longHeader == mdbHeader)
        //     try 
        //         SymbolProvider.getReaderProvider(SymbolKind.mdb).getSymbolReader(module, symbolStream)
        //     catch
        //         case _ => null
        
        if (throwIfNoSymbol)
            throw IllegalArgumentException("No symbol found in stream")
        
        null        


}

abstract class DebugInformation() extends CustomDebugInformationProvider
{
    private var _token: MetadataToken = MetadataToken(TokenType.assembly) // not initialized in C# code
    private val _custom_infos: ArrayBuffer[CustomDebugInformation] = ArrayBuffer.empty[CustomDebugInformation]

    def metadataToken = _token
    def metadataToken_(value: MetadataToken) = _token = value

    def hasCustomDebugInformation = !_custom_infos.isEmpty
    
    def customDebugInformation = _custom_infos
}


abstract class CustomDebugInformation() extends DebugInformation
{

}


trait CustomDebugInformationProvider extends MetadataTokenProvider {
    def hasCustomDebugInformations: Boolean
    def customDebugInformations: ArrayBuffer[CustomDebugInformation]
}
