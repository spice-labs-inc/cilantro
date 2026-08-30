// Deterministic golden generation with Mono.Cecil 0.11.6 (the oracle).
//
// Tier 1: full-model dump (assembly/module/type/member/signature/custom
// attribute), canonicalized and free of timestamps, absolute paths, floats
// or culture-dependent formatting.
//
// Tier 2: per-method-body instruction dump: offset, opcode name, operand
// type, RAW operand bytes (read from the original file via the PE section
// table), and the resolved operand (token -> canonical member name).
//
// Trustworthiness guards (corpus plan doc 01):
//   - raw bytes are dumped alongside the interpretation,
//   - the helper itself is verified against a hand-authored ilasm fixture
//     whose bytes are hand-decoded in the test suite (C1-03),
//   - never-execute: only Mono.Cecil / System.Reflection.Metadata reads;
//     reads only: no reflective load/invoke, no dispatch of code
//     (banned-API scan test C1-04 enforces this at the source level).
//
// Determinism: collections are walked in metadata order (stable for a
// pinned file), JSON is emitted in a fixed key order via Utf8JsonWriter,
// all numbers use InvariantCulture, and paths are relative.

using System.Globalization;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text;
using System.Text.Json;
using Mono.Cecil;
using Mono.Cecil.Cil;
using CecilAssemblyDefinition = Mono.Cecil.AssemblyDefinition;
using CecilTypeReference = Mono.Cecil.TypeReference;
using CecilMethodReference = Mono.Cecil.MethodReference;
using CecilFieldReference = Mono.Cecil.FieldReference;
using CecilMethodDefinition = Mono.Cecil.MethodDefinition;
using CecilTypeDefinition = Mono.Cecil.TypeDefinition;
using CecilModuleDefinition = Mono.Cecil.ModuleDefinition;
using CecilCustomAttribute = Mono.Cecil.CustomAttribute;

namespace GoldenDumper;

public static class GoldenWriter
{
    public sealed class DumpOptions
    {
        public string AssemblyPath { get; init; }
        public string Tier1Path { get; init; }
        public string Tier2Path { get; init; }
        public bool SkipBodies { get; init; } // mixed-mode: metadata only
        public string AssemblyLabel { get; init; }
    }

    internal sealed class NullResolver : Mono.Cecil.IAssemblyResolver
    {
        public CecilAssemblyDefinition Resolve(AssemblyNameReference name) => null;
        public CecilAssemblyDefinition Resolve(AssemblyNameReference name, ReaderParameters parameters) => null;
        public void Dispose() { }
    }

    public static void Dump(DumpOptions options)
    {
        // Cecil read-only defaults: no symbol reading, no code execution,
        // and no assembly resolution (metadata-only dump).
        var readerParams = new ReaderParameters
        {
            ReadSymbols = false,
            InMemory = false,
            AssemblyResolver = new NullResolver(),
        };
        using var assembly = CecilAssemblyDefinition.ReadAssembly(options.AssemblyPath, readerParams);
        var module = assembly.MainModule;
        Directory.CreateDirectory(Path.GetDirectoryName(options.Tier1Path)!);

        WriteTier1(options, assembly, module);
        if (!options.SkipBodies)
        {
            WriteTier2(options, assembly, module);
        }
    }

    // ---------------------------------------------------------------- Tier 1

    private static void WriteTier1(DumpOptions options, CecilAssemblyDefinition assembly, CecilModuleDefinition module)
    {
        using var stream = new System.IO.Compression.GZipStream(File.Create(options.Tier1Path), System.IO.Compression.CompressionLevel.SmallestSize);
        using var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = false });
        writer.WriteStartObject();
        writer.WriteString("format", "cilantro-tier1");
        writer.WriteNumber("version", 1);
        writer.WriteString("assemblyLabel", options.AssemblyLabel);

        writer.WritePropertyName("assembly");
        writer.WriteStartObject();
        writer.WriteString("name", assembly.Name.Name);
        writer.WriteString("version", assembly.Name.Version.ToString());
        writer.WriteString("culture", assembly.Name.Culture);
        if (assembly.Name.PublicKeyToken != null && assembly.Name.PublicKeyToken.Length > 0)
        {
            writer.WriteString("publicKeyToken", Convert.ToHexString(assembly.Name.PublicKeyToken).ToLowerInvariant());
        }
        writer.WriteEndObject();

        writer.WritePropertyName("module");
        writer.WriteStartObject();
        writer.WriteString("name", module.Name);
        writer.WriteString("kind", module.Kind.ToString());
        writer.WriteString("architecture", module.Architecture.ToString());
        writer.WriteString("runtimeVersion", module.RuntimeVersion);
        writer.WriteString("mvid", module.Mvid.ToString("D"));
        writer.WriteEndObject();

        writer.WritePropertyName("assemblyReferences");
        writer.WriteStartArray();
        foreach (var reference in module.AssemblyReferences)
        {
            writer.WriteStartObject();
            writer.WriteString("name", reference.Name);
            writer.WriteString("version", reference.Version.ToString());
            if (reference.PublicKeyToken != null && reference.PublicKeyToken.Length > 0)
            {
                writer.WriteString("publicKeyToken", Convert.ToHexString(reference.PublicKeyToken).ToLowerInvariant());
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("moduleReferences");
        writer.WriteStartArray();
        foreach (var reference in module.ModuleReferences)
        {
            writer.WriteStringValue(reference.Name);
        }
        writer.WriteEndArray();

        writer.WritePropertyName("types");
        writer.WriteStartArray();
        foreach (var type in module.Types)
        {
            WriteType(writer, type);
        }
        writer.WriteEndArray();

        writer.WriteEndObject();
    }

    private static void WriteType(Utf8JsonWriter writer, CecilTypeDefinition type)
    {
        writer.WriteStartObject();
        writer.WriteString("fullName", type.FullName);
        writer.WriteString("attributes", ((uint)type.Attributes).ToString("x", CultureInfo.InvariantCulture));
        writer.WriteString("baseType", type.BaseType == null ? null : CanonicalTypeName(type.BaseType));
        writer.WritePropertyName("interfaces");
        writer.WriteStartArray();
        foreach (var iface in type.Interfaces)
        {
            writer.WriteStringValue(CanonicalTypeName(iface.InterfaceType));
        }
        writer.WriteEndArray();

        writer.WritePropertyName("genericParameters");
        writer.WriteStartArray();
        foreach (var gp in type.GenericParameters)
        {
            writer.WriteStartObject();
            writer.WriteString("name", gp.Name);
            writer.WriteString("attributes", ((uint)gp.Attributes).ToString("x", CultureInfo.InvariantCulture));
            writer.WritePropertyName("constraints");
            writer.WriteStartArray();
            foreach (var constraint in gp.Constraints)
            {
                writer.WriteStringValue(CanonicalTypeName(constraint.ConstraintType));
            }
            writer.WriteEndArray();
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("customAttributes");
        writer.WriteStartArray();
        foreach (var attr in type.CustomAttributes)
        {
            WriteCustomAttribute(writer, attr);
        }
        writer.WriteEndArray();

        writer.WritePropertyName("fields");
        writer.WriteStartArray();
        foreach (var field in type.Fields)
        {
            writer.WriteStartObject();
            writer.WriteString("name", field.Name);
            writer.WriteString("type", CanonicalTypeName(field.FieldType));
            writer.WriteString("attributes", ((uint)field.Attributes).ToString("x", CultureInfo.InvariantCulture));
            if (field.HasConstant)
            {
                writer.WritePropertyName("constant");
                WriteConstant(writer, field.Constant);
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("methods");
        writer.WriteStartArray();
        foreach (var method in type.Methods)
        {
            WriteMethod(writer, method);
        }
        writer.WriteEndArray();

        writer.WritePropertyName("properties");
        writer.WriteStartArray();
        foreach (var property in type.Properties)
        {
            writer.WriteStartObject();
            writer.WriteString("name", property.Name);
            writer.WriteString("type", CanonicalTypeName(property.PropertyType));
            writer.WriteString("attributes", ((uint)property.Attributes).ToString("x", CultureInfo.InvariantCulture));
            if (property.GetMethod != null)
            {
                writer.WriteString("get", CanonicalMemberName(property.GetMethod));
            }
            if (property.SetMethod != null)
            {
                writer.WriteString("set", CanonicalMemberName(property.SetMethod));
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("events");
        writer.WriteStartArray();
        foreach (var @event in type.Events)
        {
            writer.WriteStartObject();
            writer.WriteString("name", @event.Name);
            writer.WriteString("type", CanonicalTypeName(@event.EventType));
            writer.WriteString("attributes", ((uint)@event.Attributes).ToString("x", CultureInfo.InvariantCulture));
            if (@event.AddMethod != null)
            {
                writer.WriteString("add", CanonicalMemberName(@event.AddMethod));
            }
            if (@event.RemoveMethod != null)
            {
                writer.WriteString("remove", CanonicalMemberName(@event.RemoveMethod));
            }
            if (@event.InvokeMethod != null)
            {
                writer.WriteString("invoke", CanonicalMemberName(@event.InvokeMethod));
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("nestedTypes");
        writer.WriteStartArray();
        foreach (var nested in type.NestedTypes)
        {
            WriteType(writer, nested);
        }
        writer.WriteEndArray();

        writer.WriteEndObject();
    }

    private static void WriteMethod(Utf8JsonWriter writer, CecilMethodDefinition method)
    {
        writer.WriteStartObject();
        writer.WriteString("name", method.Name);
        writer.WriteString("returnType", CanonicalTypeName(method.ReturnType));
        writer.WriteString("callingConvention", ((ushort)method.CallingConvention).ToString("x", CultureInfo.InvariantCulture));
        writer.WriteBoolean("hasThis", method.HasThis);
        writer.WriteBoolean("explicitThis", method.ExplicitThis);
        writer.WriteString("attributes", ((uint)method.Attributes).ToString("x", CultureInfo.InvariantCulture));
        writer.WriteString("implAttributes", ((ushort)method.ImplAttributes).ToString("x", CultureInfo.InvariantCulture));
        if (method.SemanticsAttributes != MethodSemanticsAttributes.None)
        {
            writer.WriteString("semantics", method.SemanticsAttributes.ToString());
        }

        writer.WritePropertyName("parameters");
        writer.WriteStartArray();
        foreach (var parameter in method.Parameters)
        {
            writer.WriteStartObject();
            writer.WriteString("name", parameter.Name);
            writer.WriteString("type", CanonicalTypeName(parameter.ParameterType));
            writer.WriteString("attributes", ((ushort)parameter.Attributes).ToString("x", CultureInfo.InvariantCulture));
            if (parameter.HasConstant)
            {
                writer.WritePropertyName("constant");
                WriteConstant(writer, parameter.Constant);
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("genericParameters");
        writer.WriteStartArray();
        foreach (var gp in method.GenericParameters)
        {
            writer.WriteStartObject();
            writer.WriteString("name", gp.Name);
            writer.WriteString("attributes", ((uint)gp.Attributes).ToString("x", CultureInfo.InvariantCulture));
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("customAttributes");
        writer.WriteStartArray();
        foreach (var attr in method.CustomAttributes)
        {
            WriteCustomAttribute(writer, attr);
        }
        writer.WriteEndArray();

        writer.WriteEndObject();
    }

    private static void WriteCustomAttribute(Utf8JsonWriter writer, CecilCustomAttribute attr)
    {
        writer.WriteStartObject();
        writer.WriteString("type", attr.AttributeType.FullName);
        writer.WritePropertyName("constructorArgs");
        writer.WriteStartArray();
        foreach (var arg in attr.ConstructorArguments)
        {
            WriteCustomAttributeArgument(writer, arg);
        }
        writer.WriteEndArray();
        writer.WritePropertyName("namedFields");
        writer.WriteStartArray();
        foreach (var named in attr.Fields)
        {
            WriteCustomAttributeNamed(writer, named);
        }
        writer.WriteEndArray();
        writer.WritePropertyName("namedProperties");
        writer.WriteStartArray();
        foreach (var named in attr.Properties)
        {
            WriteCustomAttributeNamed(writer, named);
        }
        writer.WriteEndArray();
        writer.WriteEndObject();
    }

    private static void WriteCustomAttributeNamed(Utf8JsonWriter writer, CustomAttributeNamedArgument named)
    {
        writer.WriteStartObject();
        writer.WriteString("name", named.Name);
        writer.WritePropertyName("argument");
        WriteCustomAttributeArgument(writer, named.Argument);
        writer.WriteEndObject();
    }

    private static void WriteCustomAttributeArgument(Utf8JsonWriter writer, CustomAttributeArgument arg)
    {
        writer.WriteStartObject();
        writer.WriteString("type", CanonicalTypeName(arg.Type));
        writer.WritePropertyName("value");
        WriteCustomAttributeValue(writer, arg.Value);
        writer.WriteEndObject();
    }

    private static void WriteCustomAttributeValue(Utf8JsonWriter writer, object value)
    {
        switch (value)
        {
            case null:
                writer.WriteStringValue("null");
                return;
            case string s:
                writer.WriteStringValue(s);
                return;
            case CecilTypeReference t:
                writer.WriteStringValue("typeof(" + CanonicalTypeName(t) + ")");
                return;
            case byte[] bytes:
                writer.WriteStringValue(Convert.ToHexString(bytes).ToLowerInvariant());
                return;
            case CustomAttributeArgument nested:
                writer.WriteStartObject();
                writer.WriteString("type", CanonicalTypeName(nested.Type));
                writer.WritePropertyName("value");
                WriteCustomAttributeValue(writer, nested.Value);
                writer.WriteEndObject();
                return;
            case CustomAttributeArgument[] array:
                writer.WriteStartArray();
                foreach (var item in array)
                {
                    WriteCustomAttributeArgument(writer, item);
                }
                writer.WriteEndArray();
                return;
            case IConvertible convertible:
                writer.WriteStringValue(convertible.ToString(CultureInfo.InvariantCulture));
                return;
            default:
                writer.WriteStringValue(value.ToString());
                return;
        }
    }

    private static void WriteConstant(Utf8JsonWriter writer, object constant)
    {
        if (constant == null)
        {
            writer.WriteStringValue("null");
            return;
        }
        writer.WriteStartObject();
        writer.WritePropertyName("value");
        WriteCustomAttributeValue(writer, constant);
        writer.WriteEndObject();
    }

    private static string CanonicalTypeName(CecilTypeReference type)
    {
        return type.FullName;
    }

    private static string CanonicalMemberName(CecilMethodReference method)
    {
        return method.FullName;
    }

    // ---------------------------------------------------------------- Tier 2

    private sealed class RawBytesMap
    {
        private readonly PEReader _peReader;
        private readonly MetadataReader _mdReader;
        private readonly List<(int VirtualAddress, int PointerToRawData, int VirtualSize)> _sections;
        private readonly byte[] _wholeFile;

        public RawBytesMap(string assemblyPath)
        {
            _wholeFile = File.ReadAllBytes(assemblyPath);
            _peReader = new PEReader(new MemoryStream(_wholeFile));
            _mdReader = _peReader.GetMetadataReader();
            _sections = new List<(int, int, int)>();
            foreach (var sectionHeader in _peReader.PEHeaders.SectionHeaders)
            {
                _sections.Add((sectionHeader.VirtualAddress, sectionHeader.PointerToRawData, sectionHeader.VirtualSize));
            }
        }

        public int GetMethodRva(int rid)
        {
            var handle = System.Reflection.Metadata.Ecma335.MetadataTokens.MethodDefinitionHandle(rid);
            var method = _mdReader.GetMethodDefinition(handle);
            return method.RelativeVirtualAddress;
        }

        public byte[] ReadRawBytes(int rva, int offset, int length)
        {
            foreach (var (virtualAddress, pointerToRawData, virtualSize) in _sections)
            {
                if (rva >= virtualAddress && rva < virtualAddress + virtualSize)
                {
                    // Cecil instruction offsets are relative to the IL
                    // stream, which starts after the method header
                    // (1 byte tiny, 12 bytes fat per ECMA-335 II.25.4).
                    int bodyOffset = pointerToRawData + (rva - virtualAddress);
                    int headerSize = _methodHeaderSize(bodyOffset);
                    int fileOffset = bodyOffset + headerSize + offset;
                    if (fileOffset < 0 || fileOffset + length > _wholeFile.Length)
                    {
                        return Array.Empty<byte>();
                    }
                    var result = new byte[length];
                    Array.Copy(_wholeFile, fileOffset, result, 0, length);
                    return result;
                }
            }
            return Array.Empty<byte>();
        }

        private int _methodHeaderSize(int bodyOffset)
        {
            if (bodyOffset >= _wholeFile.Length)
            {
                return 0;
            }
            int flags = _wholeFile[bodyOffset];
            return (flags & 0x03) == 0x03 ? 12 : 1;
        }
    }

    private static void WriteTier2(DumpOptions options, CecilAssemblyDefinition assembly, CecilModuleDefinition module)
    {
        using var stream = new System.IO.Compression.GZipStream(File.Create(options.Tier2Path), System.IO.Compression.CompressionLevel.SmallestSize);
        using var writer = new Utf8JsonWriter(stream, new JsonWriterOptions { Indented = false });
        writer.WriteStartObject();
        writer.WriteString("format", "cilantro-tier2");
        writer.WriteNumber("version", 1);
        writer.WriteString("assemblyLabel", options.AssemblyLabel);

        // RVA -> file offset mapping from the raw PE sections.
        var rawMap = new RawBytesMap(options.AssemblyPath);

        writer.WritePropertyName("bodies");
        writer.WriteStartArray();
        var methodsWithBodies = new List<(int Rid, CecilMethodDefinition Method)>();
        foreach (var type in module.Types)
        {
            CollectBodies(type, methodsWithBodies);
        }
        foreach (var (rid, method) in methodsWithBodies)
        {
            WriteBody(writer, method, rid, rawMap);
        }
        writer.WriteEndArray();
        writer.WriteEndObject();
    }

    private static void CollectBodies(CecilTypeDefinition type, List<(int Rid, CecilMethodDefinition Method)> into)
    {
        foreach (var method in type.Methods)
        {
            if (method.HasBody)
            {
                into.Add((method.MetadataToken.ToInt32() & 0x00ffffff, method));
            }
        }
        foreach (var nested in type.NestedTypes)
        {
            CollectBodies(nested, into);
        }
    }

    private static void WriteBody(Utf8JsonWriter writer, CecilMethodDefinition method, int rid, RawBytesMap rawMap)
    {
        writer.WriteStartObject();
        writer.WriteString("method", CanonicalMemberName(method));
        writer.WriteNumber("rva", method.RVA);
        writer.WriteBoolean("initLocals", method.Body.InitLocals);
        writer.WriteNumber("maxStack", method.Body.MaxStackSize);
        writer.WriteNumber("codeSize", method.Body.CodeSize);

        writer.WritePropertyName("locals");
        writer.WriteStartArray();
        foreach (var local in method.Body.Variables)
        {
            writer.WriteStartObject();
            writer.WriteString("type", CanonicalTypeName(local.VariableType));
            writer.WriteBoolean("pinned", local.IsPinned);
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WritePropertyName("instructions");
        writer.WriteStartArray();
        int rva = method.RVA;
        int index = 0;
        foreach (var instruction in method.Body.Instructions)
        {
            int nextOffset;
            if (index + 1 < method.Body.Instructions.Count)
            {
                nextOffset = method.Body.Instructions[index + 1].Offset;
            }
            else
            {
                nextOffset = method.Body.CodeSize;
            }
            int length = nextOffset - instruction.Offset;
            var raw = rawMap.ReadRawBytes(rva, instruction.Offset, length);

            writer.WriteStartObject();
            writer.WriteNumber("offset", instruction.Offset);
            writer.WriteString("opcode", instruction.OpCode.Name);
            writer.WriteString("operandType", instruction.OpCode.OperandType.ToString());
            writer.WriteString("rawBytes", raw.Length > 0 ? Convert.ToHexString(raw).ToLowerInvariant() : "");
            writer.WritePropertyName("operand");
            WriteOperand(writer, instruction.Operand);
            writer.WriteEndObject();
            index++;
        }
        writer.WriteEndArray();

        writer.WritePropertyName("exceptionHandlers");
        writer.WriteStartArray();
        foreach (var handler in method.Body.ExceptionHandlers)
        {
            writer.WriteStartObject();
            writer.WriteString("type", handler.HandlerType.ToString());
            writer.WriteNumber("tryStart", handler.TryStart?.Offset ?? -1);
            writer.WriteNumber("tryEnd", handler.TryEnd?.Offset ?? -1);
            writer.WriteNumber("handlerStart", handler.HandlerStart?.Offset ?? -1);
            writer.WriteNumber("handlerEnd", handler.HandlerEnd?.Offset ?? -1);
            if (handler.CatchType != null)
            {
                writer.WriteString("catchType", CanonicalTypeName(handler.CatchType));
            }
            if (handler.FilterStart != null)
            {
                writer.WriteNumber("filterStart", handler.FilterStart.Offset);
            }
            writer.WriteEndObject();
        }
        writer.WriteEndArray();

        writer.WriteEndObject();
    }

    private static void WriteOperand(Utf8JsonWriter writer, object operand)
    {
        switch (operand)
        {
            case null:
                writer.WriteStringValue("null");
                return;
            case string s:
                writer.WriteStringValue(s);
                return;
            case sbyte b:
                writer.WriteNumberValue(b);
                return;
            case byte b:
                writer.WriteNumberValue(b);
                return;
            case int i:
                writer.WriteNumberValue(i);
                return;
            case uint u:
                writer.WriteNumberValue(u);
                return;
            case long l:
                writer.WriteNumberValue(l);
                return;
            case ulong ul:
                writer.WriteNumberValue(ul);
                return;
            case float f:
                writer.WriteStringValue(f.ToString("R", CultureInfo.InvariantCulture));
                return;
            case double d:
                writer.WriteStringValue(d.ToString("R", CultureInfo.InvariantCulture));
                return;
            case Instruction target:
                writer.WriteNumberValue(target.Offset);
                return;
            case Instruction[] targets:
                writer.WriteStartArray();
                foreach (var t in targets)
                {
                    writer.WriteNumberValue(t.Offset);
                }
                writer.WriteEndArray();
                return;
            case VariableDefinition variable:
                writer.WriteStringValue("local." + variable.Index);
                return;
            case ParameterDefinition parameter:
                writer.WriteStringValue("param." + parameter.Index);
                return;
            case CecilMethodReference methodRef:
                writer.WriteStringValue(CanonicalMemberName(methodRef));
                return;
            case CecilFieldReference fieldRef:
                writer.WriteStringValue(fieldRef.FullName);
                return;
            case CecilTypeReference typeRef:
                writer.WriteStringValue(CanonicalTypeName(typeRef));
                return;
            default:
                writer.WriteStringValue(operand.ToString());
                return;
        }
    }
}
