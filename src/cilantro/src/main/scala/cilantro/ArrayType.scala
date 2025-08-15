//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/ArrayType.cs

package io.spicelabs.cilantro

import io.spicelabs.cilantro.metadata.ElementType
import scala.collection.mutable.ArrayBuffer
import javax.naming.OperationNotSupportedException

class ArrayDimension(private var _lowerBound: Option[Int] = None, private var _upperBound: Option[Int] = None) {
    def lowerBound = _lowerBound
    def lowerBound_=(value: Option[Int]) = _lowerBound = value

    def upperBound = _upperBound
    def upperBound_=(value: Option[Int]) = _upperBound = value

    def isSized = _lowerBound.isDefined || _upperBound.isDefined

    override def toString(): String =
        if !isSized then "" else s"$_lowerBound ... $_upperBound"

}

sealed class ArrayType(`type`: TypeReference, _rank: Int = 1) extends TypeSpecification(`type`) {
    this.etype = ElementType.array

    private var _dimensions: ArrayBuffer[ArrayDimension] = null

    if (_rank > 1)
        _dimensions = ArrayBuffer[ArrayDimension]()
        for i <- 0 until rank do
            _dimensions.addOne(ArrayDimension())


    def dimensions =
        if (_dimensions != null)
            _dimensions
        else
            _dimensions = ArrayBuffer[ArrayDimension](ArrayDimension())
            _dimensions
    
    def rank =
        if _dimensions == null then 1 else _dimensions.length
    
    def isVector =
        if (_dimensions == null)
            true
        else if (_dimensions.length > 1)
            false
        else
            _dimensions(0).isSized
    
    override def isValueType = false
    override def isValueType_=(value: Boolean) = throw OperationNotSupportedException()

    override def name = super.name + suffix
    
    override def fullName = super.fullName + suffix

    private def suffix =
        if (isVector)
            "[]"
        else
            var suff = StringBuilder()
            suff.append("[")
            for i <- 0 until dimensions.length do
                if (i > 0)
                    suff.append(",")
                suff.append(dimensions(i).toString())
            suff.append("]")
            suff.toString()

    override def isArray = true

}
