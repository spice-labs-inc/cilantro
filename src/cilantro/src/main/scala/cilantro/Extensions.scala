//
// Author:
//   Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

package io.spicelabs.cilantro

object AnyExtension {
    extension (a: Any) {
        def as[T >: Null]: T = if a.isInstanceOf[T] then a.asInstanceOf[T] else null
    }
}
