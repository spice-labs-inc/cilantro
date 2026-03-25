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
import java.nio.{ByteBuffer => NioByteBuffer}

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

    /**
     * Creates a symbol reader from a ByteBuffer containing Portable PDB data.
     *
     * @param module the module to associate with the symbol reader
     * @param symbolBuffer the ByteBuffer containing PDB data
     * @return a SymbolReader for accessing debug information
     * @throws IllegalArgumentException if module or symbolBuffer is null
     */
    def getSymbolReader(module: ModuleDefinition, symbolBuffer: NioByteBuffer): SymbolReader =
        checkModule(module)
        if (symbolBuffer == null)
            throw new IllegalArgumentException("symbolBuffer")

        val (image, heapOffset) = ImageReader.readPortablePdb(symbolBuffer, "in-memory pdb")
        PortablePdbReader(image, module)

    private def getSymbolReader(module: ModuleDefinition, symbolStream: Disposable[FileInputStream], fileName: String): SymbolReader =
        val (image, heapOffset) = ImageReader.readPortablePdb(symbolStream, fileName)
        PortablePdbReader(image, module)
}

import scala.util.boundary, boundary.break

class PortablePdbReader(private val image: Image, private val module: ModuleDefinition) extends SymbolReader {

    /**
     * Processes the debug header to verify that this PDB matches the module.
     *
     * The debug header contains entries with the PDB ID. This method compares
     * the PDB ID from the debug directory with the ID stored in the PDB heap.
     *
     * @param header the debug header from the module
     * @return true if the PDB matches the module (IDs match), false otherwise
     */
    def processDebugHeader(header: ImageDebugHeader): Boolean = boundary {
        if (header == null || !header.hasEntries) {
            break(false)
        }

        // Get the PDB ID from the PDB heap
        val pdbId = image.pdbHeap match {
            case null => break(false)
            case heap => heap.id
        }

        // Look for a CodeView entry in the debug header
        header.entties.foreach { entry =>
            entry.directory.`type` match {
                case ImageDebugType.codeView | ImageDebugType.embeddedPortablePdb =>
                    // The PDB ID should match the data in the debug entry
                    // The first 4 bytes are typically a signature, followed by the GUID (16 bytes)
                    val entryData = entry.data
                    if (entryData.length >= 20) {
                        // Extract the GUID from the debug entry (usually at offset 4)
                        val guidFromEntry = entryData.slice(4, 20)
                        // Compare with the PDB heap ID (first 20 bytes)
                        val pdbIdPrefix = pdbId.take(20)
                        if (java.util.Arrays.equals(guidFromEntry, pdbIdPrefix)) {
                            break(true)
                        }
                    }
                case _ => // Skip other debug types
            }
        }

        false
    }

    /**
     * Closes the Portable PDB reader and releases resources.
     * This closes the underlying image that was loaded for the PDB.
     */
    override def close(): Unit = {
        if (image != null) {
            image.close()
        }
    }
}
