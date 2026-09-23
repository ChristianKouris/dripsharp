// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Text;

// Native launcher only. Never call JavaCompat to observe a subject result.
internal static class Program
{
    private static Type ParameterType(string name) => name switch
    {
        "int" => typeof(int), "long" => typeof(long), "string" => typeof(string),
        _ => throw new ArgumentException("Unknown parameter type: " + name)
    };

    private static object? Argument(string token, Type type)
    {
        if (type == typeof(int)) return int.Parse(token.Substring(2), CultureInfo.InvariantCulture);
        if (type == typeof(long)) return long.Parse(token.Substring(2), CultureInfo.InvariantCulture);
        if (token == "n:") return null;
        var value = new StringBuilder();
        for (int i = 2; i < token.Length; i += 4)
            value.Append((char)int.Parse(token.Substring(i, 4), NumberStyles.HexNumber, CultureInfo.InvariantCulture));
        return value.ToString();
    }

    private static string Observe(object? value)
    {
        if (value is null) return "n:";
        if (value is int || value is long) return "i:" + Convert.ToString(value, CultureInfo.InvariantCulture);
        if (value is string text)
        {
            var result = new StringBuilder("u:");
            foreach (char unit in text) result.Append(((int)unit).ToString("x4", CultureInfo.InvariantCulture));
            return result.ToString();
        }
        throw new ArgumentException("Unsupported result type: " + value.GetType());
    }

    private static string Category(Exception error) => error switch
    {
        DivideByZeroException => "arithmetic",
        IndexOutOfRangeException => "index-out-of-bounds",
        ArgumentOutOfRangeException => "index-out-of-bounds",
        NullReferenceException => "null-pointer",
        InvalidCastException => "class-cast",
        ArgumentException => "illegal-argument",
        _ => throw error
    };

    public static void Main(string[] args)
    {
        if (args.Length == 2 && args[0] == "--native-consumer")
        {
            typeof(Program).Assembly.GetType("NativeConsumer", true)!
                .GetMethod("Run", BindingFlags.Public | BindingFlags.Static)!
                .Invoke(null, new object[] { args[1] });
            return;
        }
        if (args.Length == 1 && args[0] == "--runtime-version")
        {
            Console.WriteLine(RuntimeInformation.FrameworkDescription);
            return;
        }
        var library = Assembly.Load("DripSharp.Microprograms");
        var methods = new Dictionary<string, MethodInfo>(StringComparer.Ordinal);
        var states = new Dictionary<string, MethodInfo>(StringComparer.Ordinal);
        foreach (string line in File.ReadAllLines(args[0], Encoding.UTF8))
        {
            string[] fields = line.Split('\t');
            string[] names = fields[3].Length == 0 ? Array.Empty<string>() : fields[3].Split(',');
            Type[] types = Array.ConvertAll(names, ParameterType);
            MethodInfo method = library.GetType(fields[1], true)!.GetMethod(fields[2], types)
                ?? throw new MissingMethodException(fields[1], fields[2]);
            if (!method.IsStatic) throw new ArgumentException("Subject entry must be static");
            methods.Add(fields[0], method);
            if (fields.Length > 4 && fields[4].Length != 0)
                states.Add(fields[0], library.GetType(fields[1], true)!.GetMethod(fields[4], Type.EmptyTypes)!);
        }
        string[] input = File.ReadAllLines(args[1], Encoding.UTF8);
        if (input[0] != "DRIPSHARP_DIFFERENTIAL_CASES_V1") throw new ArgumentException("Invalid input header");
        using var output = new StreamWriter(args[2], false, new UTF8Encoding(false));
        output.WriteLine("DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1");
        for (int row = 1; row < input.Length; row++)
        {
            string[] fields = input[row].Split('\t');
            MethodInfo method = methods[fields[3]];
            ParameterInfo[] types = method.GetParameters();
            var values = new object?[types.Length];
            for (int i = 0; i < values.Length; i++) values[i] = Argument(fields[i + 5], types[i].ParameterType);
            if (fields[0] == "floating") CultureInfo.CurrentCulture = (string)values[0]! == "root"
                ? CultureInfo.InvariantCulture : CultureInfo.GetCultureInfo((string)values[0]!);
            string exception = "none";
            string result;
            try { result = Observe(method.Invoke(null, values)); }
            catch (TargetInvocationException error) when (error.InnerException is not null)
            {
                exception = Category(error.InnerException);
                result = "n:";
            }
            output.WriteLine(string.Join("\t", fields[0], fields[1], fields[2], fields[3], exception, result,
                states.TryGetValue(fields[3], out var state) ? Observe(state.Invoke(null, null)) : "n:"));
        }
    }
}
