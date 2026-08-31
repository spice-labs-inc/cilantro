// MethodBody — a decoded ECMA-335 method body.
//
// Phase 2 scope: instructions, header values (maxStackSize, initLocals,
// codeSize). `locals` is always None in this phase — the local-variable
// signature token is recorded by the decoder but its blob is read by the
// signature reader, which lands in Phase 3 ("EH & integration") together
// with exception handlers and the RVA wiring.
//
// Invariant (asserted by the decoder): the sum of the decoded instruction
// sizes equals codeSize; any body that violates it is a Failure, never a
// partial body.

package io.spicelabs.cilantro.cil

sealed class MethodBody(
    val instructions: Vector[Instruction],
    val maxStackSize: Int,
    val initLocals: Boolean,
    val codeSize: Int,
    val locals: Option[Vector[VariableDefinition]],
    val exceptionHandlers: Vector[ExceptionHandler]
)
