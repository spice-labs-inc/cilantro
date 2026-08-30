// Hand-authored IL fixture compiled with ilasm (mono) inside the pinned
// docker image. The golden dumper's output for this fixture is verified
// against hand-decoded bytes in C1-03. Kept intentionally small and
// deterministic: no timestamps, no debug info, no absolute paths.

.assembly extern mscorlib
{
  .publickeytoken = (B7 7A 5C 56 19 34 E0 89)
  .ver 4:0:0:0
}
.assembly ilasm_fixture
{
  .ver 1:0:0:0
}
.module ilasm_fixture.dll

.class public auto ansi beforefieldinit Fixture
       extends [mscorlib]System.Object
{
  // Hand-decode targets for C1-03:
  //   ldarg.1 = 0x03, ldarg.2 = 0x04, add = 0x58, ret = 0x2A
  .method public hidebysig instance int32 Add(int32 a, int32 b) cil managed
  {
    .maxstack 2
    ldarg.1
    ldarg.2
    add
    ret
  }

  // vararg method signature (calling convention 0x5); body trivial.
  .method public hidebysig static vararg int32 Varargs(int32 a) cil managed
  {
    .maxstack 2
    ldarg.0
    ldc.i4.7
    add
    ret
  }

  // arglist opcode (0xFE 0x00), pop (0x26), ret (0x2A).
  .method public hidebysig static void ArgListCall() cil managed
  {
    .maxstack 1
    arglist
    pop
    ret
  }

  // switch with 8 targets (opcode 0x45).
  .method public hidebysig static int32 SwitchTest(int32 x) cil managed
  {
    .maxstack 1
    ldarg.0
    switch (L0, L1, L2, L3, L4, L5, L6, L7)
    L0: ldc.i4.0
        ret
    L1: ldc.i4.1
        ret
    L2: ldc.i4.2
        ret
    L3: ldc.i4.3
        ret
    L4: ldc.i4.4
        ret
    L5: ldc.i4.5
        ret
    L6: ldc.i4.6
        ret
    L7: ldc.i4.7
        ret
  }

  // try/catch with one handler.
  .method public hidebysig static int32 TryCatch(int32 x) cil managed
  {
    .maxstack 2
    .locals init (int32 V_0)
    .try
    {
      ldarg.0
      ldc.i4.0
      beq LblOK
      newobj instance void [mscorlib]System.Exception::.ctor()
      throw
      LblOK: ldc.i4.1
             ret
    }
    catch [mscorlib]System.Exception
    {
      pop
      ldc.i4.m1
      ret
    }
  }

  // modreq/modopt in parameters.
  .method public hidebysig static void ModReqOpt(
      int32 modreq([mscorlib]System.Runtime.CompilerServices.IsLong) x,
      int32 modopt([mscorlib]System.Runtime.CompilerServices.IsConst) y) cil managed
  {
    .maxstack 1
    ret
  }

  // Small-format EH: a single catch clause.
  .method public hidebysig static int32 EhSmall(int32 x) cil managed
  {
    .maxstack 2
    .try
    {
      ldarg.0
      brfalse LOK
      newobj instance void [mscorlib]System.Exception::.ctor()
      throw
      LOK: leave LEnd
    }
    catch [mscorlib]System.Exception
    {
      pop
      ldc.i4.2
      ret
    }
    LEnd: ldc.i4.1
    ret
  }

  // Fat-format EH: two catches, a filter handler and a finally in one
  // method (four clauses force the fat layout).
  .method public hidebysig static int32 EhFat(int32 x) cil managed
  {
    .maxstack 2
    .try
    {
      .try
      {
        ldarg.0
        brtrue LThrow
        leave LOK
        LThrow: newobj instance void [mscorlib]System.Exception::.ctor()
        throw
        LOK: leave LDone
      }
      catch [mscorlib]System.ArgumentException
      {
        pop
        leave LDone
      }
      catch [mscorlib]System.Exception
      {
        pop
        leave LDone
      }
      filter
      {
        pop
        ldc.i4.0
      }
      {
        pop
        leave LDone
      }
      LDone: leave LFin
    }
    finally
    {
      pop
      endfinally
    }
    LFin: ldc.i4.7
    ret
  }

  // The try starts at instruction offset 0 and the handler runs to the
  // end of the body.
  .method public hidebysig static int32 EhFullBody() cil managed
  {
    .maxstack 1
    .try
    {
      ldc.i4.1
      leave LRet
    }
    catch [mscorlib]System.Exception
    {
      pop
      ldc.i4.2
      ret
    }
    LRet: ret
  }

  // The finally handler contains only endfinally.
  .method public hidebysig static void EhEmptyTry() cil managed
  {
    .maxstack 1
    .try
    {
      leave LRet
    }
    finally
    {
      endfinally
    }
    LRet: ret
  }
}

.class public auto ansi beforefieldinit GenericBox`1<T>
       extends [mscorlib]System.Object
{
  .method public hidebysig instance !0 Identity(!0 x) cil managed
  {
    .maxstack 1
    ldarg.1
    ret
  }
}

.class public sealed auto ansi beforefieldinit Days
       extends [mscorlib]System.Enum
{
  .field public static literal valuetype Days Monday = int8(1)
  .field public static literal valuetype Days Tuesday = int8(2)
  .field public static literal valuetype Days Wednesday = int8(4)
}

.class public auto ansi beforefieldinit Attributed
       extends [mscorlib]System.Object
{
  // Custom attribute: ObsoleteAttribute("Hello")
  .custom instance void [mscorlib]System.ObsoleteAttribute::.ctor(string)
           = ( 01 00 05 48 65 6C 6C 6F 00 00 )
  .method public hidebysig specialname rtspecialname instance void .ctor() cil managed
  {
    .maxstack 1
    ldarg.0
    call instance void [mscorlib]System.Object::.ctor()
    ret
  }
}
