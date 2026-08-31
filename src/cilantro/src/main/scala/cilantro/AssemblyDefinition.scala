//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/AssemblyDefinition.cs

package io.spicelabs.cilantro

import scala.collection.mutable.ArrayBuffer
import java.io.FileInputStream
import scala.util.Try
import scala.util.Success
import scala.util.Failure

class AssemblyDefinition(private var _assemblyName: Option[AssemblyNameDefinition], parameters: ModuleParameters) extends CustomAttributeProvider with SecurityDeclarationProvider with AutoCloseable {
    private var _main_module: Option[ModuleDefinition] = None
    private var _modules: Option[ArrayBuffer[ModuleDefinition]] = None
    private var _custom_attributes: Option[ArrayBuffer[CustomAttribute]] = None
    private var _security_declarations: Option[ArrayBuffer[SecurityDeclaration]] = None

    def name: Option[AssemblyNameDefinition] = _assemblyName
    def name_(value: AssemblyNameDefinition) = _assemblyName = Some(value)

    def fullName = _assemblyName.map(_.fullName).getOrElse("")

    def metadataToken: Option[MetadataToken] = Some(MetadataToken(TokenType.assembly, 1))
    def metadataToken_=(value: MetadataToken) = ()

    def modules: ArrayBuffer[ModuleDefinition] = {
        _modules match {
            case Some(m) => m
            case None =>
                val loaded = _main_module match {
                    case Some(main) if main.hasImage =>
                        main.read(ArrayBuffer.empty[ModuleDefinition], this, (_, reader) => reader.readModules())
                    case _ => ArrayBuffer.empty[ModuleDefinition]
                }
                _modules = Some(loaded)
                loaded
        }
    }
    def mainModule: Option[ModuleDefinition] = _main_module
    def mainModule_=(value: ModuleDefinition) = _main_module = Some(value)

    def entryPoint = _main_module.flatMap(_.entryPoint)
    def entryPoint_(value: MethodDefinition) = _main_module.foreach(_.entryPoint_(value))

    def hasCustomAttributes =
    _custom_attributes match {
        case Some(attrs) => attrs.length > 0
        case None => _main_module.exists(m => getHasCustomAttributes(Some(m)))
    }

    def customAttributes = {
        _custom_attributes match {
            case Some(attrs) => attrs
            case None =>
                val loaded = _main_module.map(main => getCustomAttributes(ArrayBuffer.empty[CustomAttribute], Some(main))).getOrElse(ArrayBuffer.empty[CustomAttribute])
                _custom_attributes = Some(loaded)
                loaded
        }
    }
    def hasSecurityDeclarations = {
        _security_declarations match {
            case Some(decls) => decls.length > 0
            case None => _main_module.exists(m => this.getHasSecurityDeclarations(Some(m)))
        }
    }
    def securityDeclarations = {
        _security_declarations match {
            case Some(decls) => decls
            case None =>
                val loaded = _main_module.map(main => this.getSecurityDeclarations(ArrayBuffer.empty[SecurityDeclaration], Some(main))).getOrElse(ArrayBuffer.empty[SecurityDeclaration])
                _security_declarations = Some(loaded)
                loaded
        }
    }

    def this() = {
        this(Some(AssemblyNameDefinition()), ModuleParameters())

    }
    override def close(): Unit = {
        _modules match {
            case None => _main_module.foreach(_.close())
            case Some(ms) => ms.foreach(_.close())
        }
    }
    override def toString(): String = fullName
}

object AssemblyDefinition {
    def createAssembly(assemblyName: AssemblyNameDefinition, moduleName: String, kind: ModuleKind): AssemblyDefinition = {
        val mp = ModuleParameters()
        mp.kind_(kind)
        createAssembly(assemblyName, moduleName, mp)

    }
    def createAssembly(assemblyName: AssemblyNameDefinition, moduleName: String, parameters: ModuleParameters): AssemblyDefinition = {
        checkParameters(parameters)
        if (parameters.kind == ModuleKind.netModule) {
            throw IllegalArgumentException("kind")

        }
        ModuleDefinition.createModule(moduleName, parameters).assembly match {
            case Some(assembly) =>
                assembly.name_(assemblyName)
                assembly
            case None => throw IllegalArgumentException("module has no assembly")
        }


    }
    def readAssembly(fileName: String): Try[AssemblyDefinition] = {
        ModuleDefinition.readModule(fileName).flatMap(readAssembly)

    }
    def readAssembly(fileName: String, parameters: ReaderParameters): Try[AssemblyDefinition] = {
        ModuleDefinition.readModule(fileName, parameters).flatMap(readAssembly)

    }
    def readAssembly(stream: FileInputStream): Try[AssemblyDefinition] = {
        ModuleDefinition.readModule(stream).flatMap(readAssembly)

    }
    def readAssembly(stream: FileInputStream, parameters: ReaderParameters): Try[AssemblyDefinition] = {
        ModuleDefinition.readModule(stream, parameters).flatMap(readAssembly)

    }
    def readAssembly(module: ModuleDefinition): Try[AssemblyDefinition] = {
        module.assembly match {
            case Some(assembly) => Success(assembly)
            case None => Failure(IllegalArgumentException("module has no assembly"))
        }
    }
}
