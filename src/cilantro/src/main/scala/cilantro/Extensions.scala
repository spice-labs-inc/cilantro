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
        inline def as[T]: Option[T] = if a.isInstanceOf[T] then Some(a.asInstanceOf[T]) else None
    }
}
