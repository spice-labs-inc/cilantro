// ExceptionHandler — one SEH exception-handling clause of a method body.
//
// The instruction references are INDICES into the body's instruction
// vector (acyclic, like Operand.Branch). Following the oracle
// (Mono.Cecil 0.11.6):
//   - tryStart/handlerStart are Some(index) when the clause offset lands
//     on an instruction boundary and None when Cecil's GetInstruction
//     returns null (in-range non-boundary offsets);
//   - tryEnd/handlerEnd point at the FIRST instruction after the range;
//     None when the range runs to the end of the body (offset ==
//     codeSize) or lands on a non-boundary (Cecil-null);
//   - catchType is resolved through the type-def-or-ref table when
//     possible; filterStart is Some(index) or None (Cecil tolerates a
//     filter offset that does not resolve — mono ilasm emits catch
//     clauses that way, see ADR-0005).
//
// Out-of-code offsets and negative lengths are Failures at decode time,
// never emitted as handlers (plan 03).

package io.spicelabs.cilantro.cil

import io.spicelabs.cilantro.TypeReference

enum ExceptionHandlerType {
  case Catch, Filter, Finally, Fault
}

sealed class ExceptionHandler(
    val handlerType: ExceptionHandlerType,
    val tryStart: Option[Int],
    val tryEnd: Option[Int],
    val handlerStart: Option[Int],
    val handlerEnd: Option[Int],
    val catchType: Option[TypeReference],
    val filterStart: Option[Int]
) {
  override def toString: String = {
    val tryStartText = tryStart.map(_.toString).getOrElse("-")
    val handlerStartText = handlerStart.map(_.toString).getOrElse("-")
    s"$handlerType try[$tryStartText..${tryEnd.getOrElse(-1)}) handler[$handlerStartText..${handlerEnd.getOrElse(-1)})"
  }
}
