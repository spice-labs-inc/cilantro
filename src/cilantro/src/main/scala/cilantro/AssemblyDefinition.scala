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

import scala.collection.mutable.ListBuffer

class AssemblyDefinition(private var _assemblyName: AssemblyNameDefinition, parameters: ModuleParameters) extends CustomAttributeProvider with AutoCloseable {
    private var _main_module:ModuleDefinition = null
    private var _modules: ListBuffer[ModuleDefinition] = null
    private var _custom_attributes: ListBuffer[CustomAttribute] = null
    // TODO
    // private var _security_declarations: ListBuffer[SecurityDeclaration] = null

    def name = _assemblyName
    def name_(value: AssemblyNameDefinition) = _assemblyName = value

    def fullName = if _assemblyName != null then _assemblyName.fullName else ""

    def metadataToken = MetadataToken(TokenType.assembly, 1)
    def metadataToken_(value: MetadataToken) = ()

    def modules =
        if (_modules != null)
            _modules
        if (_main_module.hasImage)
            _modules = _main_module.read(_modules, this, (_, reader) => reader.readModules())
            _modules
        _modules = ListBuffer.empty[ModuleDefinition]
        _modules

    def mainModule = _main_module

    // TODO
    // def entryPoint = _main_module.entryPoint
    // def entryPoint_(value: MethodDefinition) = _main_module.entryPoint_(value)

    def hasCustomAttributes =
    if (_custom_attributes != null)
        _custom_attributes.length > 0
    else getHasCustomAttributes(_main_module)

    def customAttributes =
        if (_custom_attributes != null)
            _custom_attributes
        else
            _custom_attributes = getCustomAttributes(_custom_attributes, _main_module)
            _custom_attributes

    override def close(): Unit =
        if (_modules == null)
            _main_module.close()
        else
            _modules.foreach((m) => m.close())  

    override def toString(): String = fullName  
}

object AssemblyDefinition {
    def createAssembly(assemblyName: AssemblyNameDefinition, moduleName: String, kind: ModuleKind): AssemblyDefinition =
        val mp = ModuleParameters()
        mp.kind_(kind)
        createAssembly(assemblyName, moduleName, mp)

    def createAssembly(assemblyName: AssemblyNameDefinition, moduleName: String, parameters: ModuleParameters): AssemblyDefinition =
        if (assemblyName == null)
            throw IllegalArgumentException("assemblyName")
        if (moduleName == null)
            throw IllegalArgumentException("moduleName")
        checkParameters(parameters)
        if (parameters.kind == ModuleKind.netModule)
            throw IllegalArgumentException("kind")
        
        var assembly = ModuleDefinition.createModule(moduleName, parameters).assembly
        assembly.name_(assemblyName)

        assembly
}
