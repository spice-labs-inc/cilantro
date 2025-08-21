//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ExportedType.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.MemberDefinition.getMaskedAttributes
import io.spicelabs.cilantro.MemberDefinition.setMaskedAttributes
import io.spicelabs.cilantro.MemberDefinition.getAttributes
import io.spicelabs.cilantro.MemberDefinition.setAttributes
import javax.naming.OperationNotSupportedException

sealed class ExportedType(namespace: String, __name: String, module: ModuleDefinition, __scope: MetadataScope) extends MetadataTokenProvider {
    private var _namespace: String = namespace
    private var _name: String = __name
    private var _attributes = 0
    private var _scope: MetadataScope = __scope
    private var _module: ModuleDefinition = module
    private var _identifier = 0
    private var _declaring_type: ExportedType = null
    var _token: MetadataToken = null
    var _reentrancyGuard = false

    def nameSpace = _namespace
    def nameSpace_=(value: String) = _namespace = value

    def name = _name
    def name_=(value: String) = _name = value

    def attributes = _attributes
    def attributes_=(value: Int) = _attributes = value

    def scope: MetadataScope = if _declaring_type != null then _declaring_type.scope else _scope
    def scope_=(value: MetadataScope): Unit =
        if (_declaring_type != null)
            _declaring_type.scope = value
        else
            _scope = value
    
    def declaringType = _declaring_type
    def declaringType_=(value: ExportedType) = _declaring_type = value

    override def metadataToken: MetadataToken = _token
    override def metadataToken_=(value: MetadataToken): Unit = _token = value

    def identifier = _identifier
    def identifier_=(value: Int) = _identifier = value

    def isNotPublic = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value)
    def isNotPublic_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.notPublic.value, value)

    def isPublic = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value)
    def isPublic_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.public.value, value)

    def isNestedPublic = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value)
    def isNestedPublic_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPublic.value, value)

    def isNestedPrivate = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value)
    def isNestedPrivate_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedPrivate.value, value)

    def isNestedFamily = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value)
    def isNestedFamily_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamily.value, value)

    def isNestedAssembly = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value)
    def isNestedAssembly_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedAssembly.value, value)

    def isNestedFamilyAndAssembly = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value)
    def isNestedFamilyAndAssembly_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamANDAssem.value, value)

    def isNestedFamilyOrAssembly = getMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value)
    def isNestedFamilyOrAssembly_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.visibilityMask.value, TypeAttributes.nestedFamORAssem.value, value)

    def isAutoLayout = getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value)
    def isAutoLayout_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.autoLayout.value, value)

    def isSequentialLayout = getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value)
    def isSequentialLayout_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.sequentialLayout.value, value)

    def isExplicitLayout = getMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value)
    def isExplicitLayout_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.layoutMask.value, TypeAttributes.explicitLayout.value, value)

    def isClass = getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value)
    def isClass_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.`class`.value, value)

    def isInterface = getMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value)
    def isInterface_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.classSemanticMask.value, TypeAttributes.interface.value, value)

    def isAbstract = getAttributes(_attributes, TypeAttributes.`abstract`.value)
    def isAbstract_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.`abstract`.value, value)

    def isSealed = getAttributes(_attributes, TypeAttributes.`sealed`.value)
    def isSealed_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.`sealed`.value, value)

    def isSpecialName = getAttributes(_attributes, TypeAttributes.specialName.value)
    def isSpecialName_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.specialName.value, value)

    def isImport = getAttributes(_attributes, TypeAttributes.`import`.value)
    def isImport_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.`import`.value, value)

    def isSerializable = getAttributes(_attributes, TypeAttributes.serializable.value)
    def isSerializable_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.serializable.value, value)

    def isAnsiClass = getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value)
    def isAnsiClass_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.ansiClass.value, value)

    def isUnicodeClass = getMaskedAttributes(_attributes, TypeAttributes.stringFormatMask.value, TypeAttributes.unicodeClass.value)
    def isUnicodeClass_=(value: Boolean) = _attributes = setMaskedAttributes(_attributes, TypeAttributes.unicodeClass.value, TypeAttributes.ansiClass.value, value)

    def isBeforeFieldInit = getAttributes(_attributes, TypeAttributes.beforeFieldInit.value)
    def isBeforeFieldInit_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.beforeFieldInit.value, value)

    def isRuntimeSpecialName = getAttributes(_attributes, TypeAttributes.rtSpecialName.value)
    def isRuntimeSpecialName_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.rtSpecialName.value, value)

    def hasSecurity = getAttributes(_attributes, TypeAttributes.hasSecurity.value)
    def hasSecurity_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.hasSecurity.value, value)

    def isForwarder = getAttributes(_attributes, TypeAttributes.forwarder.value)
    def isForwarder_=(value: Boolean) = _attributes = setAttributes(_attributes, TypeAttributes.forwarder.value, value)

    def fullName:String =
        val fullname = if (nameSpace == null || nameSpace.length() == 0) then _name else nameSpace + '.' + nameSpace

        if (_declaring_type != null)
            _declaring_type.fullName + "/" + fullname
        else
            fullname
    
    override def toString(): String = fullName

    def resolve(): TypeDefinition =
        if (_reentrancyGuard)
            throw OperationNotSupportedException(s"Circularity when resolving exported type: '$this'")
        _reentrancyGuard = true
        try
            _module.resolve(createReference())
        finally
            _reentrancyGuard = false

    def createReference(): TypeReference =
        val typeref = TypeReference(nameSpace, _name, _module, scope)
        typeref.declaringType = if _declaring_type != null then _declaring_type.createReference() else null
        typeref

}
