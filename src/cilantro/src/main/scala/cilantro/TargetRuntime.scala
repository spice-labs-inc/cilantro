//
// Author:
//   Jb Evain (jbevain@gmail.com) and Steve Hawley (sdh@spicelabs.io)
//
// Copyright (c) 2008 - 2015 Jb Evain
// Copyright (c) 2008 - 2011 Novell, Inc.
// Copyright (c) 2025 Spice Labs, Inc.
//
// Licensed under the MIT/X11 license.

// Derived from https://github.com/jbevain/cecil/blob/3136847ea620fb9b4a3ff96bc4f573148e8bd2e4/Mono.Cecil/TargetRuntime.cs

package io.spicelabs.cilantro

enum TargetRuntime {
  case net_1_0, net_1_1, net_2_0, net_4_0
  def runtimeVersionString() =
    this match
      case TargetRuntime.net_1_0 => "v1.0.3705"
      case TargetRuntime.net_1_1 => "v1.1.4322"
      case TargetRuntime.net_2_0 => "v2.0.50727"
      case _ => "v4.0.30319"
}

object TargetRuntime {
  def parseRuntime(self: String) =
    if (self == null || self.length() == 0)
      TargetRuntime.net_4_0
    self.charAt(1) match
      case '1' =>
        if self.charAt(3) == '0' then TargetRuntime.net_1_0 else TargetRuntime.net_1_1
      case '2' => TargetRuntime.net_2_0
      case _ => TargetRuntime.net_4_0
    
}
