//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil.Cil/PortablePdb.cs

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.*
import io.spicelabs.cilantro.PE.*
import java.io.FileInputStream

sealed class PortablePdbReaderProvider extends SymbolReaderProvider {

    def getSymbolReader(module: ModuleDefinition, fileName: String): SymbolReader =
        checkModule(module)
        checkFileName(fileName)

        val file = new FileInputStream(fileName)
        getSymbolReader(module, Disposable.owned(file), fileName)
        
    def getSymbolReader(module: ModuleDefinition, symbolStream: FileInputStream): SymbolReader =
        checkModule(module)
        checkStream(symbolStream)

        getSymbolReader(module, Disposable.notOwned(symbolStream), "unknown filename")
    
    private def getSymbolReader(module: ModuleDefinition, symbolStream: Disposable[FileInputStream], fileName: String): SymbolReader =
        val (image, heapOffset) = ImageReader.readPortablePdb(symbolStream, fileName)
        PortablePdbReader(image, module)
}

class PortablePdbReader( private val image: Image, private val module: ModuleDefinition) extends SymbolReader {

    def processDebugHeader(header: ImageDebugHeader): Boolean = false // TODO
    override def close(): Unit = { } // TODO
}
