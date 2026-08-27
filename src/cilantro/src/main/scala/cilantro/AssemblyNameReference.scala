//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/AssemblyNameReference.cs

package io.spicelabs.cilantro

import java.util.Locale
import io.spicelabs.cilantro.AssemblyNameReference.getAttributes
import io.spicelabs.cilantro.AssemblyNameReference.setAttributes
import java.security.MessageDigest

class AssemblyNameReference(private var _name: String, private var _version: CSVersion, var _token: MetadataToken = MetadataToken (TokenType.assemblyRef)) extends MetadataScope {
    def this() = {
        this("", AssemblyNameReference.zeroVersion)
    
    }
    private var _culture = ""
    private var _attributes = 0
    private var _public_key = Array.emptyByteArray
    private var _public_key_token: Option[Array[Byte]] = None
    private var _hash_algorithm = AssemblyHashAlgorithm.none

    private var _hash = Array.emptyByteArray
    private var _full_name: Option[String] = None

    def name = _name
    def name_=(value: String) = _name = value

    def culture = _culture
    def culture_=(value: String) = _culture = value

    def version = _version
    def version_=(value: CSVersion) = _version = checkVersion(value)

    def attributes = _attributes
    def attributes_=(value: Int) = _attributes = value

    def hasPublicKey = getAttributes(_attributes, AssemblyAttributes.publicKey.value)
    def hasPublicKey_=(value: Boolean) = _attributes = setAttributes(_attributes, AssemblyAttributes.publicKey.value, value)

    def isSideBySideCompatible = getAttributes(_attributes, AssemblyAttributes.sideBySideCompatible.value)
    def isSideBySideCompatible_=(value: Boolean) = _attributes = setAttributes(_attributes, AssemblyAttributes.sideBySideCompatible.value, value)

    def isRetargetable = getAttributes(_attributes, AssemblyAttributes.retargetable.value)
    def isRetargetable_=(value: Boolean) = _attributes = setAttributes(_attributes, AssemblyAttributes.retargetable.value, value)

    def isWindowsRuntime = getAttributes(_attributes, AssemblyAttributes.windowsRuntime.value)
    def isWindowsRuntime_=(value: Boolean) = _attributes = setAttributes(_attributes, AssemblyAttributes.windowsRuntime.value, value)

    def publicKey = _public_key
    def publicKey_=(value: Array[Byte]) = {
        _public_key = value
        hasPublicKey = publicKey.length > 0
        _public_key_token = None
        _full_name = None

    }
    def publicKeyToken = {
        if (_public_key_token.isEmpty && _public_key.length > 0) {
            val hash = hashPublicKey()
            val local_public_key_token = Array.ofDim[Byte](8)

            // set public key token to the last 8 bytes of the hash reverse
            for dst <- 0 until 8 do {
                val src = 15 - dst
                local_public_key_token(dst) = hash(src)
            }
            _public_key_token = Some(local_public_key_token)
        }
        _public_key_token.getOrElse(Array.emptyByteArray)
    }
    def publicKeyToken_=(value: Array[Byte]) = {
        _public_key_token = Some(value)
        _full_name = None
 
    }
    private def hashPublicKey() = {
        val hasher = _hash_algorithm match {
            case AssemblyHashAlgorithm.md5 => MessageDigest.getInstance("MD5")
            // default to SHA-1
            case _ => MessageDigest.getInstance("SHA-1")
        }
        hasher.digest(_public_key)

    }
    override def metadataScopeType: MetadataScopeType = MetadataScopeType.assemblyNameReference

    def fullName = {
        _full_name match {
            case Some(f) => f
            case None =>
                val sep = ", "
                val builder = StringBuilder()
                builder.append(_name)
                builder.append(sep)
                builder.append("Version=")
                builder.append(_version.toString())
                builder.append(sep)
                builder.append("Culture=")
                builder.append(if _culture.length() == 0 then "neutral" else culture)
                builder.append(sep)
                builder.append("PublicKeyToken=")
                val pk_token = publicKeyToken
                if (pk_token.length == 0) {
                    builder.append("null")
                }
                else {
                    for b <- pk_token do {
                        builder.append(f"${(b.toInt & 0xff)}%02x")

                    }
                }
                if (isRetargetable) {
                    builder.append(sep).append("Retargetable=Yes")

                }
                val full = builder.mkString
                _full_name = Some(full)
                full
        }
    }
    def hashAlgorithm = _hash_algorithm
    def hashAlgorithm_=(value: AssemblyHashAlgorithm) = _hash_algorithm = value

    def hash: Array[Byte] = _hash
    def hash_=(value: Array[Byte]) = _hash = value

    def metadataToken: Option[MetadataToken] = Some(_token)
    def metadataToken_=(value: MetadataToken) = _token = value

    override def toString(): String = this.fullName

    override def equals(that: Any): Boolean = {
        if (!that.isInstanceOf[AssemblyNameReference]) {
            return false
        }
        val other = that.asInstanceOf[AssemblyNameReference]
        if (name != other.name) {
            return false
        }
        if (!version.equals(other.version)) {
            return false
        }
        if (culture != other.culture) {
            return false
        }
        if (publicKeyToken.length > 0 && !publicKeyToken.sameElements(other.publicKeyToken)) {
            return false
        }
        true
    }
}

object AssemblyNameReference {
    def apply(name: String, version: CSVersion): AssemblyNameReference = {
        var assem = new AssemblyNameReference(checkName(name), version)
        assem._hash_algorithm = AssemblyHashAlgorithm.none
        assem._token = MetadataToken(TokenType.assemblyRef)

        assem
    
    }
    def apply() = {
        var assem = new AssemblyNameReference("", zeroVersion)
        assem._token = MetadataToken(TokenType.assemblyRef)

        assem    

    }
    def parse(fullName: String): AssemblyNameReference = {
        val name = new AssemblyNameReference()

        if (fullName.length() == 0) {
            throw IllegalArgumentException("Name can not be empty")
        
        }
        val tokens = fullName.split(",")

        for tk <- tokens do {
            val token = tk.trim()
            if (tk == tokens(0)) {
                name._name = token
            }
            else {
                val parts = token.split("\\=")
                if (parts.length != 2) {
                    throw IllegalArgumentException("Malformed name")
                
                }
                parts(0).toLowerCase(Locale.ROOT) match {
                    case "version" => name._version =
                        CSVersion.parse(parts(1)) match {
                            case Some(vers) => vers
                            case None => zeroVersion
                        
                        }
                    case "culture" => name._culture = if (parts(1) == "neutral") then "" else parts(1)
                    case "publickeytoken" =>
                        name._public_key_token = Some(fromHexString(parts(1)))
                    case _ => ()


                }
            }
        }
        name
    
    }
    val zeroVersion = CSVersion(0, 0, 0, 0)

    def getAttributes(value:Int, attributes: Int) = {
        (value & attributes) != 0

    }
    def setAttributes(value: Int, attributes: Int, set: Boolean) = {
        if (set) {
            value | attributes
        }
        value & ~attributes
    }
}


def checkVersion(version: CSVersion) : CSVersion = {
    version
}