//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TypeReference.cs

package io.spicelabs.cilantro

import scala.collection.immutable.TreeMap
import scala.collection.mutable.ArrayBuffer
import scala.collection.immutable.TreeSet
import scala.collection.mutable.HashSet
import io.spicelabs.cilantro.AnyExtension.as
import javax.naming.OperationNotSupportedException
import io.spicelabs.cilantro.WindowsRuntimeProjections.getCoreLibrary

sealed class TypeDefinitionProjection(`type`: TypeDefinition, val treatment: Int,
    val redirectedMethods: ArrayBuffer[MethodDefinition], val redirectedInterfaces: ArrayBuffer[(InterfaceImplementation, InterfaceImplementation)]) {

    val attributes = `type`.attributes
    val name = `type`.name
}
sealed class TypeReferenceProjection(`type`: TypeReference, val treatment: Int) {
    val name = `type`.name
    val namespace = `type`.nameSpace
    val scope = `type`._scope
}


sealed class MethodDefinitionProjection(method: MethodDefinition, val treatment: Int) {
    val attributes = method.attributes
    val implAttributes = method.implAttributes
    val name = method.name
}

sealed class FieldDefinitionProjection(field: FieldDefinition, val treatment: Int) {
    val attributes = field.attributes
}

sealed class CustomAttributeValueProjection(val targets: Char, val treatment: Int) {

}

private case class ProjectionInfo(val winRTNamespace: String, val clrNamespace: String, val clrName: String, val clrAssembly: String, val attribute: Boolean = false)
{

}

sealed class WindowsRuntimeProjections(val module: ModuleDefinition) {
    private var corlib_version = CSVersion(255, 255, 255, 255)
    private var virtualReferences: ArrayBuffer[AssemblyNameReference] = null

    def addVirtualReferences(references: Seq[AssemblyNameReference]): Unit = {
        val corlib = getCoreLibrary(references)
        corlib_version = corlib.version
        corlib.version = WindowsRuntimeProjections.version;
        if (virtualReferences == null)
            virtualReferences = ArrayBuffer()
        virtualReferences.addAll((references))
    }

    def removeVirtualReferences(references: ArrayBuffer[AssemblyNameReference]): Unit = {
        val corlib = getCoreLibrary(references.toSeq)
        corlib.version = corlib_version
        virtualReferences.foreach(ref => references.subtractOne(ref))
    }

    private def getAssemblyReference(name: String): AssemblyNameReference = {
        if (virtualReferences != null) {
            return virtualReferences.find(r => r.name == name) match {
                case Some(r) => r
                case None => throw Exception()
            }
        } 
        throw Exception()
    }
}

object WindowsRuntimeProjections {
    val version = CSVersion(4, 0, 0, 0)
    val contract_pk_token = Array[Byte](
        0xB0.byteValue, 0x3F, 0x5F, 0x7F, 0x11, 0xD5.byteValue, 0x0A, 0x3A
    )

    // oh java. Your lack of unsigned types was such an oversight.
    val contract_pk = Array[Byte](
        0x00, 0x24, 0x00, 0x00, 0x04, 0x80.byteValue(), 0x00, 0x00, 0x94.byteValue(), 0x00, 0x00, 0x00, 0x06, 0x02, 0x00, 0x00,
        0x00, 0x24, 0x00, 0x00, 0x52, 0x53, 0x41, 0x31, 0x00, 0x04, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00,
        0x07, 0xD1.byteValue(), 0xFA.byteValue(), 0x57, 0xC4.byteValue(), 0xAE.byteValue(), 0xD9.byteValue(), 0xF0.byteValue(),
        0xA3.byteValue(), 0x2E, 0x84.byteValue(), 0xAA.byteValue(), 0x0F, 0xAE.byteValue(), 0xFD.byteValue(), 0x0D,
        0xE9.byteValue(), 0xE8.byteValue(), 0xFD.byteValue(), 0x6A, 0xEC.byteValue(), 0x8F.byteValue(), 0x87.byteValue(),
        0xFB.byteValue(), 0x03, 0x76, 0x6C, 0x83.byteValue(), 0x4C, 0x99.byteValue(), 0x92.byteValue(), 0x1E,
        0xB2.byteValue(), 0x3B, 0xE7.byteValue(), 0x9A.byteValue(), 0xD9.byteValue(), 0xD5.byteValue(), 0xDC.byteValue(),
        0xC1.byteValue(), 0xDD.byteValue(), 0x9A.byteValue(), 0xD2.byteValue(), 0x36, 0x13, 0x21, 0x02, 0x90.byteValue(),
        0x0B, 0x72, 0x3C, 0xF9.byteValue(), 0x80.byteValue(), 0x95.byteValue(), 0x7F, 0xC4.byteValue(), 0xE1.byteValue(),
        0x77, 0x10, 0x8F.byteValue(), 0xC6.byteValue(), 0x07, 0x77, 0x4F,
        0x29, 0xE8.byteValue(), 0x32, 0x0E, 0x92.byteValue(), 0xEA.byteValue(), 0x05, 0xEC.byteValue(), 0xE4.byteValue(),
        0xE8.byteValue(), 0x21, 0xC0.byteValue(), 0xA5.byteValue(), 0xEF.byteValue(), 0xE8.byteValue(), 0xF1.byteValue(),
        0x64, 0x5C, 0x4C, 0x0C, 0x93.byteValue(), 0xC1.byteValue(), 0xAB.byteValue(), 0x99.byteValue(), 0x28, 0x5D, 0x62,
        0x2C, 0xAA.byteValue(), 0x65, 0x2C, 0x1D, 0xFA.byteValue(), 0xD6.byteValue(), 0x3D, 0x74, 0x5D, 0x6F, 0x2D,
        0xE5.byteValue(), 0xF1.byteValue(), 0x7E, 0x5E, 0xAF.byteValue(), 0x0F, 0xC4.byteValue(), 0x96.byteValue(), 0x3D,
        0x26, 0x1C, 0x8A.byteValue(), 0x12, 0x43, 0x65, 0x18, 0x20, 0x6D, 0xC0.byteValue(), 0x93.byteValue(), 0x34, 0x4D, 0x5A,
        0xD2.byteValue(), 0x93.byteValue())
    
    lazy val projections =
        TreeMap(
            "AttributeTargets" -> ProjectionInfo ("Windows.Foundation.Metadata", "System", "AttributeTargets", "System.Runtime"),
            "AttributeUsageAttribute" -> ProjectionInfo ("Windows.Foundation.Metadata", "System", "AttributeUsageAttribute", "System.Runtime", true),
            "Color" -> ProjectionInfo ("Windows.UI", "Windows.UI", "Color", "System.Runtime.WindowsRuntime"),
            "CornerRadius" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "CornerRadius", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "DateTime" -> ProjectionInfo ("Windows.Foundation", "System", "DateTimeOffset", "System.Runtime"),
            "Duration" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "Duration", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "DurationType" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "DurationType", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "EventHandler`1" -> ProjectionInfo ("Windows.Foundation", "System", "EventHandler`1", "System.Runtime"),
            "EventRegistrationToken" -> ProjectionInfo ("Windows.Foundation", "System.Runtime.InteropServices.WindowsRuntime", "EventRegistrationToken", "System.Runtime.InteropServices.WindowsRuntime"),
            "GeneratorPosition" -> ProjectionInfo ("Windows.UI.Xaml.Controls.Primitives", "Windows.UI.Xaml.Controls.Primitives", "GeneratorPosition", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "GridLength" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "GridLength", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "GridUnitType" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "GridUnitType", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "HResult" -> ProjectionInfo ("Windows.Foundation", "System", "Exception", "System.Runtime"),
            "IBindableIterable" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections", "IEnumerable", "System.Runtime"),
            "IBindableVector" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections", "IList", "System.Runtime"),
            "IClosable" -> ProjectionInfo ("Windows.Foundation", "System", "IDisposable", "System.Runtime"),
            "ICommand" -> ProjectionInfo ("Windows.UI.Xaml.Input", "System.Windows.Input", "ICommand", "System.ObjectModel"),
            "IIterable`1" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "IEnumerable`1", "System.Runtime"),
            "IKeyValuePair`2" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "KeyValuePair`2", "System.Runtime"),
            "IMapView`2" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "IReadOnlyDictionary`2", "System.Runtime"),
            "IMap`2" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "IDictionary`2", "System.Runtime"),
            "INotifyCollectionChanged" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections.Specialized", "INotifyCollectionChanged", "System.ObjectModel"),
            "INotifyPropertyChanged" -> ProjectionInfo ("Windows.UI.Xaml.Data", "System.ComponentModel", "INotifyPropertyChanged", "System.ObjectModel"),
            "IReference`1" -> ProjectionInfo ("Windows.Foundation", "System", "Nullable`1", "System.Runtime"),
            "IVectorView`1" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "IReadOnlyList`1", "System.Runtime"),
            "IVector`1" -> ProjectionInfo ("Windows.Foundation.Collections", "System.Collections.Generic", "IList`1", "System.Runtime"),
            "KeyTime" -> ProjectionInfo ("Windows.UI.Xaml.Media.Animation", "Windows.UI.Xaml.Media.Animation", "KeyTime", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "Matrix" -> ProjectionInfo ("Windows.UI.Xaml.Media", "Windows.UI.Xaml.Media", "Matrix", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "Matrix3D" -> ProjectionInfo ("Windows.UI.Xaml.Media.Media3D", "Windows.UI.Xaml.Media.Media3D", "Matrix3D", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "Matrix3x2" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Matrix3x2", "System.Numerics.Vectors"),
            "Matrix4x4" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Matrix4x4", "System.Numerics.Vectors"),
            "NotifyCollectionChangedAction" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections.Specialized", "NotifyCollectionChangedAction", "System.ObjectModel"),
            "NotifyCollectionChangedEventArgs" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections.Specialized", "NotifyCollectionChangedEventArgs", "System.ObjectModel"),
            "NotifyCollectionChangedEventHandler" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System.Collections.Specialized", "NotifyCollectionChangedEventHandler", "System.ObjectModel"),
            "Plane" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Plane", "System.Numerics.Vectors"),
            "Point" -> ProjectionInfo ("Windows.Foundation", "Windows.Foundation", "Point", "System.Runtime.WindowsRuntime"),
            "PropertyChangedEventArgs" -> ProjectionInfo ("Windows.UI.Xaml.Data", "System.ComponentModel", "PropertyChangedEventArgs", "System.ObjectModel"),
            "PropertyChangedEventHandler" -> ProjectionInfo ("Windows.UI.Xaml.Data", "System.ComponentModel", "PropertyChangedEventHandler", "System.ObjectModel"),
            "Quaternion" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Quaternion", "System.Numerics.Vectors"),
            "Rect" -> ProjectionInfo ("Windows.Foundation", "Windows.Foundation", "Rect", "System.Runtime.WindowsRuntime"),
            "RepeatBehavior" -> ProjectionInfo ("Windows.UI.Xaml.Media.Animation", "Windows.UI.Xaml.Media.Animation", "RepeatBehavior", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "RepeatBehaviorType" -> ProjectionInfo ("Windows.UI.Xaml.Media.Animation", "Windows.UI.Xaml.Media.Animation", "RepeatBehaviorType", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "Size" -> ProjectionInfo ("Windows.Foundation", "Windows.Foundation", "Size", "System.Runtime.WindowsRuntime"),
            "Thickness" -> ProjectionInfo ("Windows.UI.Xaml", "Windows.UI.Xaml", "Thickness", "System.Runtime.WindowsRuntime.UI.Xaml"),
            "TimeSpan" -> ProjectionInfo ("Windows.Foundation", "System", "TimeSpan", "System.Runtime"),
            "TypeName" -> ProjectionInfo ("Windows.UI.Xaml.Interop", "System", "Type", "System.Runtime"),
            "Uri" -> ProjectionInfo ("Windows.Foundation", "System", "Uri", "System.Runtime"),
            "Vector2" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Vector2", "System.Numerics.Vectors"),
            "Vector3" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Vector3", "System.Numerics.Vectors"),
            "Vector4" -> ProjectionInfo ("Windows.Foundation.Numerics", "System.Numerics", "Vector4", "System.Numerics.Vectors"),            
        )

    def project(`type`: TypeDefinition): Unit = {
        var treatment:Int = TypeDefinitionTreatment.none.value
        var metadata_kind = `type`.module.metadataKind
        var redirectedMethods: ArrayBuffer[MethodDefinition] = null
        var redirectedInterfaces: ArrayBuffer[(InterfaceImplementation, InterfaceImplementation)] = null

        if (`type`.isWindowsRuntime) {
            if (metadata_kind == MetadataKind.windowsMetadata) {
                treatment = getWellKnownTypeDefinitionTreatment(`type`)
                if (treatment != TypeDefinitionTreatment.none.value) {
                    applyProjection(`type`, TypeDefinitionProjection(`type`, treatment, redirectedMethods, redirectedInterfaces))
                    return
                }

                val base_type = `type`.baseType
                if (base_type != null && isAttribute(base_type)) {
                    treatment = TypeDefinitionTreatment.normalAttribute.value
                } else {
                    val (treat, meths, intfs) = generateRedirectionInformation(`type`)
                    treatment = treat
                    redirectedMethods = meths
                    redirectedInterfaces = intfs
                }
            } else if (metadata_kind == MetadataKind.managedWindowsMetadata && needsWindowsRuntimePrefix(`type`)) {
                treatment = TypeDefinitionTreatment.prefixWindowsRuntimeName.value
            }

            if (treatment == TypeDefinitionTreatment.prefixWindowsRuntimeName.value || treatment == TypeDefinitionTreatment.normalType.value) {
                if (!`type`.isInterface && hasAttribute(`type`.customAttributes.toSeq, "Windows.UI.Xaml", "TreatAbstractComposableClassAttribute"))
                    treatment = treatment | TypeDefinitionTreatment.`abstract`.value 
            }
        } else if (metadata_kind == MetadataKind.managedWindowsMetadata && isClrImplementationType(`type`)) {
            treatment = TypeDefinitionTreatment.unmangleWindowsRuntimeName.value
        }

        if (treatment != TypeDefinitionTreatment.none.value)
            applyProjection(`type`, TypeDefinitionProjection(`type`, treatment, redirectedMethods, redirectedInterfaces))
    }

    private def getWellKnownTypeDefinitionTreatment(`type`: TypeDefinition): Int = {
        projections.get(`type`.name) match
            case Some(info) => {
                val treatment = if info.attribute then TypeDefinitionTreatment.redirectToClrAttribute.value else TypeDefinitionTreatment.redirectToClrType.value
                `type`.nameSpace match {
                    case info.clrNamespace => treatment
                    case info.winRTNamespace => treatment | TypeDefinitionTreatment.internal.value
                    case _ => TypeDefinitionTreatment.none.value
                }
            }
            case None => TypeDefinitionTreatment.none.value        
    }

    private def generateRedirectionInformation(`type`: TypeDefinition): (Int, ArrayBuffer[MethodDefinition], ArrayBuffer[(InterfaceImplementation, InterfaceImplementation)]) = {
        val implementsProjectedInterface = `type`.interfaces.exists(intf => isRedirectedType(intf.interfaceType))

        if (!implementsProjectedInterface)
            return (TypeDefinitionTreatment.normalType.value, null, null)

        val allImplementedInterfaces: HashSet[TypeReference] = HashSet()
        val redirectedMethods: ArrayBuffer[MethodDefinition] = ArrayBuffer()
        val redirectedInterfaces: ArrayBuffer[(InterfaceImplementation, InterfaceImplementation)] = ArrayBuffer()

        for `interface` <- `type`.interfaces do {
            val interfaceType = `interface`.interfaceType
            if (isRedirectedType(interfaceType)) {
                allImplementedInterfaces.addOne(interfaceType)
                collectImplementedInterfaces(interfaceType, allImplementedInterfaces)
            }
        }

        for implementedInterface <- `type`.interfaces do {
            val interfaceType = implementedInterface.interfaceType
            if (isRedirectedType(implementedInterface.interfaceType)) {
                val etype = interfaceType.getElementType()
                var unprojectedType = TypeReference(etype.nameSpace, etype.name, etype.module, etype.scope)
                unprojectedType.declaringType = etype.declaringType
                unprojectedType.projection = etype.projection

                removeProjection(unprojectedType)

                val genericInstanceType = interfaceType.as[GenericInstanceType]
                if (genericInstanceType != null) {
                    val genericUnprojectedType = GenericInstanceType(unprojectedType)
                    for genericArgument <- genericInstanceType.genericArguments do {
                        genericUnprojectedType.genericArguments.addOne(genericArgument)
                    }
                    unprojectedType = genericUnprojectedType
                }

                val unprojectedInterface = InterfaceImplementation(unprojectedType)
                redirectedInterfaces.addOne((implementedInterface -> unprojectedInterface))
            }
        }

        if (`type`.isInterface) {
            allImplementedInterfaces.foreach(inf => redirectInterfaceMethods(inf, redirectedMethods))
        }
        return (TypeDefinitionTreatment.redirectImplementedMethods.value, redirectedMethods, redirectedInterfaces)
    }

    private def collectImplementedInterfaces(`type`: TypeReference, results: HashSet[TypeReference]): Unit = {
        val typeResolver = TypeResolver.`for`(`type`)
        val typeDef = `type`.resolve()

        for implementedInterface <- typeDef.interfaces do {
            val interfaceType = typeResolver.resolve(implementedInterface.interfaceType)
            results.add(interfaceType)
            collectImplementedInterfaces(interfaceType, results)
        }
    }

    private def redirectInterfaceMethods(interfaceType: TypeReference, redirectedMethods: ArrayBuffer[MethodDefinition]): Unit = {
        val typeResolver = TypeResolver.`for`(interfaceType)
        val typeDef = interfaceType.resolve()

        for method <- typeDef.methods do {
            val redirectedMethod = MethodDefinition(method.name, (MethodAttributes.public.value | MethodAttributes.virtual.value
                | MethodAttributes.`final`.value | MethodAttributes.newSlot.value).toChar, typeResolver.resolve(method.returnType))
            redirectedMethod.implAttributes = MethodImplAttributes.runtime.value.toChar

            redirectedMethod.parameters.addAll(method.parameters.map(parameter => ParameterDefinition(parameter.name, parameter.attributes, typeResolver.resolve(parameter.parameterType))))

            redirectedMethod.overrides.addOne(typeResolver.resolve(method))
            redirectedMethods.addOne(redirectedMethod)
        }
    }

    private def isRedirectedType(`type`: TypeReference): Boolean = {
        val typeRefProjection = `type`.getElementType().projection.as[TypeReferenceProjection]
        typeRefProjection != null && typeRefProjection.treatment == TypeReferenceTreatment.userProjectionInfo.value
    }

    private def needsWindowsRuntimePrefix(`type`: TypeDefinition): Boolean = {
        if ((`type`.attributes & (TypeAttributes.visibilityMask.value | TypeAttributes.interface.value)) != TypeAttributes.public.value) {
            return false
        }

        val base_type = `type`.baseType
        if (base_type == null || base_type.metadataToken.tokenType != TokenType.typeRef) {
            return false
        }

        if (base_type.nameSpace == "System") {
            base_type.name match {
                case "Attribute" | "MulticastDelegate" | "ValueType" => false
                case _ => true
            }
        } else {
            true
        }
    }

    def isClrImplementationType(`type`: TypeDefinition): Boolean = {
        if ((`type`.attributes & (TypeAttributes.visibilityMask.value | TypeAttributes.specialName.value)) != TypeAttributes.specialName.value) {
            return false
        }
        `type`.name.startsWith("<CLR>")
    }

    def applyProjection(`type`: TypeDefinition, projection: TypeDefinitionProjection): Unit = {
        if (projection == null)
            return
        
        val treatment = projection.treatment

        treatment & TypeDefinitionTreatment.kindMask.value match {
            case TypeDefinitionTreatment.normalType.value =>
                `type`.attributes = `type`.attributes | TypeAttributes.windowsRuntime.value | TypeAttributes.`import`.value
            case TypeDefinitionTreatment.normalAttribute.value =>
                `type`.attributes = `type`.attributes | TypeAttributes.windowsRuntime.value | TypeAttributes.`sealed`.value
            case TypeDefinitionTreatment.unmangleWindowsRuntimeName.value => {
                `type`.attributes = `type`.attributes & ~TypeAttributes.specialName.value | TypeAttributes.public.value
                `type`.name = `type`.name.substring("<CLR>".length())
            }
            case TypeDefinitionTreatment.prefixWindowsRuntimeName.value => {
                `type`.attributes = `type`.attributes & ~TypeAttributes.public.value | TypeAttributes.`import`.value
                `type`.name = "<WinRT>" + `type`.name
            }
            case TypeDefinitionTreatment.redirectToClrType.value =>
                `type`.attributes = `type`.attributes & ~TypeAttributes.public.value | TypeAttributes.`import`.value
            case TypeDefinitionTreatment.redirectToClrAttribute.value =>
                `type`.attributes = `type`.attributes & ~TypeAttributes.public.value
            case TypeDefinitionTreatment.redirectImplementedMethods.value => {
                `type`.attributes = `type`.attributes | TypeAttributes.windowsRuntime.value | TypeAttributes.`import`.value
                for redirectedInterfacePair <- projection.redirectedInterfaces do {
                    `type`.interfaces.addOne(redirectedInterfacePair._2)

                    for customAttribute <- redirectedInterfacePair._1.customAttributes do {
                        redirectedInterfacePair._2.customAttributes.addOne(customAttribute)
                    }

                    redirectedInterfacePair._1.customAttributes.clear()

                    for method <- `type`.methods do {
                        for `override` <- method.overrides do {
                            if (`override`.declaringType.equals(redirectedInterfacePair._1.interfaceType)) {
                                `override`.declaringType = redirectedInterfacePair._2.interfaceType
                            }
                        }
                    }
                }

                `type`.methods.addAll(projection.redirectedMethods)
            }
            case _ => ()
        }

        if ((treatment & TypeDefinitionTreatment.`abstract`.value) != 0) {
            `type`.attributes = `type`.attributes | TypeAttributes.`abstract`.value
        }

        if ((treatment & TypeDefinitionTreatment.internal.value) != 0) {
            `type`.attributes = `type`.attributes & ~TypeAttributes.public.value
        }

        `type`.windowsRuntimeProjectionTD = projection
    }

    def removeProjection(`type`: TypeDefinition): TypeDefinitionProjection = {
        if (!`type`.isWindowsRuntimeProjection) {
            return null
        }

        val projection = `type`.windowsRuntimeProjectionTD
        `type`.windowsRuntimeProjection = null

        `type`.attributes = projection.attributes
        `type`.name = projection.name

        if (projection.treatment == TypeDefinitionTreatment.redirectImplementedMethods.value) {
            for method <- projection.redirectedMethods do {
                val index = `type`.methods.indexOf(method)
                `type`.methods.remove(index)
            }
            for redirectedInterfacePair <- projection.redirectedInterfaces do {
                for method <- projection.redirectedMethods do {
                    for `override` <- method.overrides do {
                        if (`override`.declaringType.equals(redirectedInterfacePair._2.interfaceType)) {
                            `override`.declaringType = redirectedInterfacePair._1.interfaceType
                        }
                    }
                }
            }
        }
        return projection
    }

    def project(`type`: TypeReference): Unit = {
        val info = projections.get(`type`.name)
        val treatment = if (info.isDefined && info.get.winRTNamespace == `type`.nameSpace) {
            TypeReferenceTreatment.userProjectionInfo
        } else {
            getSpecialTypeReferenceTreatment(`type`)
        }
        if (treatment != TypeReferenceTreatment.none)
            applyProjection(`type`, TypeReferenceProjection(`type`, treatment.value))
    }

    private def getSpecialTypeReferenceTreatment(`type`: TypeReference): TypeReferenceTreatment = {
        if (`type`.nameSpace == "System") {
            if (`type`.name == "MulticastDelegate")
                return TypeReferenceTreatment.systemDelegate
            if (`type`.name == "Attribute")
                return TypeReferenceTreatment.systemAttribute
        }
        TypeReferenceTreatment.none
    }

    private def isAttribute(`type`: TypeReference): Boolean = {
        if `type`.metadataToken != TokenType.typeRef then false else `type`.name == "Attribute" && `type`.nameSpace == "System"
    }

    private def isEnum(`type`: TypeReference): Boolean = {
        if `type`.metadataToken != TokenType.typeRef then false else `type`.name == "Enum" && `type`.nameSpace == "System"
    }

    def applyProjection(`type`: TypeReference, projection: TypeReferenceProjection): Unit = {
        if (projection == null)
            return ()
        projection.treatment match
            case TypeReferenceTreatment.systemDelegate.value | TypeReferenceTreatment.systemAttribute.value =>
                `type`.scope = `type`.module.projections.getAssemblyReference("System.Runtime")
            case TypeReferenceTreatment.userProjectionInfo.value => {
                projections.get(`type`.name) match
                    case Some(info) => {
                        `type`.name = info.clrName
                        `type`.nameSpace = info.clrNamespace
                        `type`.scope = `type`.module.projections.getAssemblyReference(info.clrAssembly)
                    }
                    case None => { }
            }
            case _ => { }
        `type`.windowsRuntimeProjection = projection
    }

    def removeProjection(`type`: TypeReference): TypeReferenceProjection = {
        if (!`type`.isWindowsRuntimeProjection)
            return null
        
        val projection = `type`.windowsRuntimeProjection
        `type`.windowsRuntimeProjection = null

        `type`.name = projection.name
        `type`.nameSpace = projection.namespace
        `type`.scope = projection.scope

        projection
    }

    def project(method: MethodDefinition): Unit = {
        var treatment = MethodDefinitionTreatment.none.value
        var other = false
        val declaring_type = method.declaringTypeTD

        if (declaring_type.isWindowsRuntimeProjection) {
            if (isClrImplementationType(declaring_type)) {
                treatment = MethodDefinitionTreatment.none.value
            } else if (declaring_type.isNested) {
                treatment = MethodDefinitionTreatment.none.value
            } else if (declaring_type.isInstanceOf) {
                treatment = MethodDefinitionTreatment.runtime.value | MethodDefinitionTreatment.internalCall.value
            } else if (declaring_type.module.metadataKind == MetadataKind.managedWindowsMetadata && !method.isPublic) {
                treatment = MethodDefinitionTreatment.none.value
            } else {
                other = true

                val base_type = declaring_type.baseType
                if (base_type != null && base_type.metadataToken == TokenType.typeRef) {
                    getSpecialTypeReferenceTreatment(base_type) match
                        case TypeReferenceTreatment.systemDelegate => {
                            treatment = MethodDefinitionTreatment.runtime.value | MethodDefinitionTreatment.public.value
                            other = false
                        }
                        case TypeReferenceTreatment.systemAttribute => {
                            treatment = MethodDefinitionTreatment.runtime.value | MethodDefinitionTreatment.internalCall.value
                            other = false
                        }
                        case _ => { }                    
                }
            }
        }

        if (other) {
            var seen_redirected = false
            var seen_non_redirected = false
            for `override` <- method.overrides do {
                if (`override`.metadataToken.tokenType == TokenType.memberRef && implementsRedirectedInterface(`override`)) {
                    seen_redirected = true
                } else {
                    seen_non_redirected = true
                }
            }

            if (seen_redirected && !seen_non_redirected) {
                treatment = MethodDefinitionTreatment.runtime.value | MethodDefinitionTreatment.internalCall.value | MethodDefinitionTreatment.`private`.value
                other = false
            }

            if (other) {
                treatment = treatment | getMethodDefinitionTreatmentFromCustomAttributes(method)
            }

            if (treatment != MethodDefinitionTreatment.none.value)
                applyProjection(method, MethodDefinitionProjection(method, treatment))
        }
    }

    private def getMethodDefinitionTreatmentFromCustomAttributes(method: MethodDefinition): Int = {
        var treatment = MethodDefinitionTreatment.none.value
        for attribute <- method.customAttributes do {
            val `type` = attribute.attributeType
            if (`type`.nameSpace == "Windows.UI.Xaml") {
                if (`type`.name == "TreatAsPublicMethodAttribute") {
                    treatment = treatment | MethodDefinitionTreatment.public.value
                } else if (`type`.name == "TreatAsAbstractMethodAttribute") {
                    treatment = treatment | MethodDefinitionTreatment.`abstract`.value
                }
            }
        }
        treatment
    }

    def applyProjection(method: MethodDefinition, projection: MethodDefinitionProjection): Unit = {
        if (projection == null)
            return
        
        var treatment = projection.treatment

        if ((treatment & MethodDefinitionTreatment.`abstract`.value) != 0)
            method.attributes = (method.attributes | MethodAttributes.`abstract`.value).toChar
        
        if ((treatment & MethodDefinitionTreatment.`private`.value) != 0)
            method.attributes = ((method.attributes & ~MethodAttributes.memberAccessMask.value) | MethodAttributes.`private`.value).toChar

        if ((treatment & MethodDefinitionTreatment.public.value) != 0)
            method.attributes = ((method.attributes & ~MethodAttributes.memberAccessMask.value) | MethodAttributes.public.value).toChar

        if ((treatment & MethodDefinitionTreatment.runtime.value) != 0)
            method.implAttributes = (method.implAttributes | MethodImplAttributes.runtime.value).toChar
        
        if ((treatment & MethodDefinitionTreatment.internalCall.value) != 0)
            method.implAttributes = (method.implAttributes | MethodImplAttributes.internalCall.value).toChar
        

        method.windowsRuntimeProjection = projection
    }

    def removeProjection(method: MethodDefinition): MethodDefinitionProjection = {
        if (!method.isWindowsRuntimeProjection)
            return null

        val projection = method.windowsRuntimeProjection
        method.windowsRuntimeProjection = null

        method.attributes = projection.attributes
        method.implAttributes = projection.implAttributes
        method.name = projection.name
        projection
    }

    def project(field: FieldDefinition): Unit = {
        var treatment = FieldDefinitionTreatment.none.value
        val declaring_type = field.declaringType

        if (declaring_type.module.metadataKind == MetadataKind.windowsMetadata && field.isRuntimeSpecialName && field.name == "value__") {
            val base_type = declaring_type
            if (base_type != null && isEnum(base_type))
                treatment = FieldDefinitionTreatment.public.value
        }

        if (treatment != FieldDefinitionTreatment.none.value)
            applyProjection(field, FieldDefinitionProjection(field, treatment))
    }

    def applyProjection(field: FieldDefinition, projection: FieldDefinitionProjection): Unit = {
        if (projection == null)
            return
        
        if (projection.treatment == FieldDefinitionTreatment.public.value)
            field.attributes = ((field.attributes & ~FieldAttributes.fieldAccessMask.value) | FieldAttributes.public.value).toChar
        field.windowsRuntimeProjection = projection
    }

    def removeProjection(field: FieldDefinition): FieldDefinitionProjection = {
        if (!field.isWindowsRuntimeProjection)
            return null

        val projection = field.windowsRuntimeProjection
        field.windowsRuntimeProjection = null

        field.attributes = projection.attributes
        projection
    }

    private def implementsRedirectedInterface(member: MemberReference): Boolean = {
        val declaring_type = member.declaringType
        val `type` = declaring_type.metadataToken.tokenType match {
            case TokenType.typeRef => Some(declaring_type)
            case TokenType.typeSpec => {
                if (!declaring_type.isGenericInstance)
                    None
                val t = declaring_type.asInstanceOf[TypeSpecification].elementType
                if (t.metadataType != MetadataType.`class` || t.metadataToken.tokenType != TokenType.typeRef)
                    None
                t
            }
            case _ => None
        }
        val found = `type` match {
            case None => false
            case Some(ty) => {
                val projection = removeProjection(ty)
                projections.get(ty.name) match {
                    case None => {
                        applyProjection(ty, projection)
                        false
                    }
                    case Some(info) => {
                        applyProjection(ty, projection)
                        ty.nameSpace == info.winRTNamespace
                    }
                }
            }
        }
        found
    }

    def getAssemblyReferences(corlib: AssemblyNameReference): Array[AssemblyNameReference] = {
        val system_runtime = AssemblyNameReference("System.Runtime", version)
        val system_runtime_interopservices_windowsruntime = AssemblyNameReference("System.Runtime.InteropServices.WindowsRuntime", version)
        val system_objectmodel = AssemblyNameReference("System.ObjectModel", version)
        val system_runtime_windowsruntime = AssemblyNameReference("System.Runtime.WindowsRuntime", version)
        val system_runtime_windowsruntime_ui_xaml = AssemblyNameReference("System.Runtime.WindowsRuntime.UI.Xaml", version)
        val system_numerics_vectors = AssemblyNameReference("System.Numerics.Vectors", version)

        if (corlib.hasPublicKey) {
            system_runtime_windowsruntime.publicKey = corlib.publicKey
            system_runtime_windowsruntime_ui_xaml.publicKey = corlib.publicKey

            system_runtime.publicKey = WindowsRuntimeProjections.contract_pk
            system_runtime_interopservices_windowsruntime.publicKey = WindowsRuntimeProjections.contract_pk
            system_objectmodel.publicKey = WindowsRuntimeProjections.contract_pk
            system_numerics_vectors.publicKey = WindowsRuntimeProjections.contract_pk
        } else {
            system_runtime_windowsruntime.publicKeyToken = corlib.publicKeyToken
            system_runtime_windowsruntime_ui_xaml.publicKeyToken = corlib.publicKeyToken

            system_runtime.publicKeyToken = WindowsRuntimeProjections.contract_pk_token
            system_runtime_interopservices_windowsruntime.publicKeyToken = WindowsRuntimeProjections.contract_pk_token
            system_objectmodel.publicKeyToken = WindowsRuntimeProjections.contract_pk_token
            system_numerics_vectors.publicKeyToken = WindowsRuntimeProjections.contract_pk_token
        }
        return Array(system_runtime,
            system_runtime_interopservices_windowsruntime,
            system_objectmodel,
            system_runtime_windowsruntime,
            system_runtime_windowsruntime_ui_xaml,
            system_numerics_vectors)
    }

    def getCoreLibrary(references: Seq[AssemblyNameReference]): AssemblyNameReference = {
        references.find(r => r.name == "mscorlib") match {
            case Some(r) => r
            case None => throw OperationNotSupportedException("Missing mscorlib reference in AssemblyRef table.")
        }
    }

    def project(owner: CustomAttributeProvider, owner_attributes: ArrayBuffer[CustomAttribute], attribute: CustomAttribute): Unit = {
        if (!isWindowsAttributeUsageAttribute(owner, attribute))
            return ()
        
        var treatment = CustomAttributeValueTreatment.none
        val `type` = owner.asInstanceOf[TypeDefinition]

        if (`type`.nameSpace == "Windows.Foundation.Metadata") {
            if (`type`.name == "versionAttribute")
                treatment = CustomAttributeValueTreatment.versionAttribute
            else if (`type`.name == "DeprecatedAttribute")
                treatment = CustomAttributeValueTreatment.deprecatedAttribute
        }

        if (treatment == CustomAttributeValueTreatment.none) {
            var multiple = hasAttribute(owner_attributes.toSeq, "Windows.Foundation.Metadata", "AllowMultipleAttribute")
            treatment = if multiple then CustomAttributeValueTreatment.allowMultiple else CustomAttributeValueTreatment.allowSingle
        }

        if (treatment != CustomAttributeValueTreatment.none) {
            val attribute_targets = attribute.constructorArguments(0).value.asInstanceOf[Char]
            applyProjection(attribute, new CustomAttributeValueProjection(attribute_targets, treatment.value))
        }
    }

    def isWindowsAttributeUsageAttribute(owner: CustomAttributeProvider, attribute: CustomAttribute): Boolean = {
        if (owner.metadataToken.tokenType != TokenType.typeDef)
            return false
        
        val constructor = attribute.constructor

        if (constructor.metadataToken.tokenType != TokenType.memberRef)
            return false
        
        val declaring_type = constructor.declaringType

        if (declaring_type.metadataToken.tokenType != TokenType.typeRef)
            return false
        
        declaring_type.name == "AttributeUsageAttribute" && declaring_type.nameSpace == "System"
    }

    private def hasAttribute(attributes: Seq[CustomAttribute], namespace: String, name: String): Boolean = {
        attributes.find(at => at.attributeType.name == name && at.attributeType.nameSpace == namespace) != None
    }

    def applyProjection(attribute: CustomAttribute, projection: CustomAttributeValueProjection): Unit = {
        if (projection == null)
            return
        
        val (version_or_deprecated, multiple) = projection.treatment match {
            case CustomAttributeValueTreatment.allowSingle.value => (false, false)
            case CustomAttributeValueTreatment.allowMultiple.value => (false, true)
            case CustomAttributeValueTreatment.versionAttribute.value | CustomAttributeValueTreatment.deprecatedAttribute.value => (true, true)
            case _ => throw IllegalArgumentException()
        }

        var attribute_targets = attribute.constructorArguments(0).value.asInstanceOf[Char]
        if (version_or_deprecated)
            attribute_targets = (attribute_targets | AttributeTargets.constructor.value | AttributeTargets.property.value).toChar
        
        attribute.constructorArguments(0) = CustomAttributeArgument(attribute.constructorArguments(0).`type`, attribute_targets)
        attribute.properties.addOne(CustomAttributeNamedArgument("AllowMultiple", CustomAttributeArgument(attribute.module.typeSystem.boolean, multiple)))
        attribute._projection = projection
    }

    def removeProjection(attribute: CustomAttribute): CustomAttributeValueProjection = {
        if (attribute._projection == null)
            return null
        
        val projection = attribute._projection
        attribute._projection = null;
        attribute.constructorArguments(0) = CustomAttributeArgument(attribute.constructorArguments(0).`type`, projection.targets)
        attribute.properties.clear()

        projection
    }
}
