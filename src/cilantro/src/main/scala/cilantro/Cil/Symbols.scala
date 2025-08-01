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
import scala.collection.mutable.ListBuffer

sealed class ImageDebugDirectory {
    val size = 28
    var characteristics: Int = 0
    var timeDataStamp: Int = 0
    var majorVersion: Short = 0
    var minorVersion: Short = 0
    var `type`: ImageDebugType = ImageDebugType.codeView
    var sizeOfData: Int = 0
    var addressOfRawData: Int = 0
    var pointerToRawData: Int = 0
}

enum ImageDebugType(value: Int) {
    case codeView extends ImageDebugType(2)
    case deterministic extends ImageDebugType(16)
    case embeddedPortablePdb extends ImageDebugType(17)
    case pdbCheckSum extends ImageDebugType(19)
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
  
}

trait SymbolReaderProvider {
    def getSymbolReader(module: ModuleDefinition, fileName: String) : SymbolReader
    def getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream) : SymbolReader
}

abstract class DebugInformation() extends CustomDebugInformationProvider
{
    private var _token: MetadataToken = MetadataToken(TokenType.assembly) // not initialized in C# code
    private val _custom_infos: ListBuffer[CustomDebugInformation] = ListBuffer.empty[CustomDebugInformation]

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
    def customDebugInformations: ListBuffer[CustomDebugInformation]
}
