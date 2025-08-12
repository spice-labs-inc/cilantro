//
// Author:
//   Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

package io.spicelabs.cilantro

class CSVersion(private val _major: Int, private val _minor: Int, private val _build:Int = -1, private val _revision: Int = -1) extends Ordered[CSVersion]{

    def major = _major
    def minor = _minor
    def build = _build
    def revision = _revision

    override def compare(that: CSVersion): Int =
        if (this._major != that._major)
            if (this._major > that._major)
                return 1
            else
                return -1
        
        if (this._minor != that._minor)
            if (this._minor > that._minor)
                return 1
            else
                return -1

        if (this._build != that._build)
            if (this._build > that._build)
                return 1
            else
                return -1

        if (this._revision != that._revision)
            if (this._revision > that._revision)
                return 1
            else
                return -1

        0
    
    override def equals(that: Any): Boolean =
        that match
            case v : CSVersion => v._major == _major && v._minor == _minor && v._build == _build && v._revision == _revision
            case _ => false
    

    override def hashCode(): Int =
        var acc = 0
        acc |= (_major & 0xf) << 28
        acc |= (_minor & 0xff) << 20
        acc |= (_build & 0xff) << 12
        acc |= (_revision & 0xfff)
        acc
    
    override def toString(): String =
        if (_build == -1)
            toString(2)
        else if (_revision == -1)
            toString(3)
        else toString(4)

    def toString(fieldCount: Int) =
        val sb = StringBuilder()
        fieldCount match
            case 0 => ""
            case 1 => _major.toString()
            case 2 =>
                sb.append(_major)
                sb.append('.')
                sb.append(_minor)
                sb.toString()
            case 3 =>
                if (_build < 0)
                    throw IllegalArgumentException("build")
                sb.append(_major)
                sb.append('.')
                sb.append(_minor)
                sb.append('.')
                sb.append(_build)
                sb.toString()
            case 4 =>
                if (_build < 0)
                    throw IllegalArgumentException("build")
                if (_revision < 0)
                    throw IllegalArgumentException("revision")
                sb.append(_major)
                sb.append('.')
                sb.append(_minor)
                sb.append('.')
                sb.append(_build)
                sb.append('.')
                sb.append(_revision)
                sb.toString()
            case _ => throw IllegalArgumentException("fieldCount")
        
}

object CSVersion {
    def parse(str: String): Option[CSVersion] =
        val parts = str.split("\\.")
        if (parts.length < 2 || parts.length > 4)
            None
        else
            val (maj, min, b, r) = parts.length match
                case 2 => (Integer.parseInt(parts(0)), Integer.parseInt(parts(1)), -1, -1)
                case 3 => (Integer.parseInt(parts(0)), Integer.parseInt(parts(1)), Integer.parseInt(parts(2)), -1)
                case 4 => (Integer.parseInt(parts(0)), Integer.parseInt(parts(1)), Integer.parseInt(parts(2)), Integer.parseInt(parts(3)))
                case _ => throw IllegalArgumentException("str")
            Some(new CSVersion(maj, min, b, r))            



}
