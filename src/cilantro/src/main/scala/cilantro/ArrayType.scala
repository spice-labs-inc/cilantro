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

    override def toString(): String = {
        if !isSized then "" else _lowerBound.map(_.toString).getOrElse("") + "..." + _upperBound.map(_.toString).getOrElse("")

    }
}

sealed class ArrayType(`type`: TypeReference, _rank: Int = 1) extends TypeSpecification(`type`) {
    this.etype = ElementType.array

    private var _dimensions: Option[ArrayBuffer[ArrayDimension]] = None

    if (_rank > 1) {
        val dims = ArrayBuffer[ArrayDimension]()
        for i <- 0 until rank do {
            dims.addOne(ArrayDimension())


        }
        _dimensions = Some(dims)
    }
    def dimensions = {
        _dimensions match {
            case Some(d) => d
            case None =>
                val d = ArrayBuffer[ArrayDimension](ArrayDimension())
                _dimensions = Some(d)
                d
    
        }
    }
    def rank = {
        _dimensions.map(_.length).getOrElse(1)
    
    }
    def isVector = {
        _dimensions match {
            case None => true
            case Some(d) if d.length > 1 => false
            case Some(d) => d(0).isSized
    
        }
    }
    override def isValueType = false
    override def isValueType_=(value: Boolean) = throw OperationNotSupportedException()

    override def name = super.name + suffix
    
    override def fullName = super.fullName + suffix

    private def suffix = {
        if (isVector) {
            "[]"
        }
        else {
            var suff = StringBuilder()
            suff.append("[")
            for i <- 0 until dimensions.length do {
                if (i > 0) {
                    suff.append(",")
                }
                suff.append(dimensions(i).toString())
            }
            suff.append("]")
            suff.toString()

        }
    }
    override def isArray = true

}
