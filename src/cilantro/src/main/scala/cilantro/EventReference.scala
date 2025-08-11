//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/EventReference.cs

package io.spicelabs.cilantro

abstract class EventReference(name: String, _eventType: TypeReference) extends MemberReference(name) {
    checkType(eventType)
    private var event_type = _eventType

    def eventType = event_type
    def eventType_=(value: TypeReference) = event_type = value

    override def fullName = event_type.fullName + " " + memberFullName()

    override def resolveDefinition() =
        resolve()
    
}
