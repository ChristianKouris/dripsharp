// Only trusted in-process synthetic state is serialized, on actual .NET Framework.
// This executable does not enable BinaryFormatter on modern .NET runtimes.
using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Formatters.Binary;
class LegacyExceptions
{
    const BindingFlags Instance = BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic;
    static void Equal(object expected, object actual, string name)
    { if (!Equals(expected, actual)) throw new Exception(name + ": expected " + expected + ", got " + actual); }
    static void Same(Exception original, Exception copy)
    {
        Equal(original.GetType(), copy.GetType(), "type");
        Equal(original.Message, copy.Message, "message");
        Equal(original.HResult, copy.HResult, "HResult");
        Equal(original.InnerException?.Message, copy.InnerException?.Message, "inner message");
        Equal(original.InnerException?.GetType(), copy.InnerException?.GetType(), "inner type");
        Equal(original.Data["context"], copy.Data["context"], "data");
        if (original is ArgumentException a) Equal(a.ParamName, ((ArgumentException)copy).ParamName, "parameter");
        if (original is ObjectDisposedException d) Equal(d.ObjectName, ((ObjectDisposedException)copy).ObjectName, "object");
    }
    static int Main()
    {
        Console.WriteLine("Actual .NET Framework " + Environment.Version + "; " + typeof(object).Assembly.Location);
        if (typeof(object).Assembly.GetName().Name != "mscorlib") throw new Exception("Not .NET Framework");
        int count = 0;
        foreach (string assemblyName in new[] { "DripSharp.PdfCarton.IO", "DripSharp.PdfCarton.Fonts", "DripSharp.PdfCarton.Xmp" })
        {
            var assembly = Assembly.Load(assemblyName);
            foreach (string name in new[] { "ArgumentNullException", "ArgumentException", "ArgumentOutOfRangeException", "ObjectDisposedException" })
            {
                Type type = assembly.GetTypes().Single(t => t.Name == name && t.Namespace.Contains("Runtime"));
                Equal(true, type.IsSerializable, type.FullName);
                object[] args = name == "ArgumentException" ? new object[] { "message", "parameter", new InvalidOperationException("inner") } : new object[] { "parameter-or-object" };
                var original = (Exception)Activator.CreateInstance(type, Instance, null, args, null);
                original.Data["context"] = "trusted synthetic state";
                var context = new StreamingContext(StreamingContextStates.All);
                var info = new SerializationInfo(type, new FormatterConverter());
                original.GetObjectData(info, context);
                var constructor = type.GetConstructor(Instance, null, new[] { typeof(SerializationInfo), typeof(StreamingContext) }, null);
                if (constructor == null || !constructor.IsFamily) throw new Exception("Missing protected serialization constructor: " + type);
                Same(original, (Exception)constructor.Invoke(new object[] { info, context }));
                using (var stream = new MemoryStream())
                {
                    var formatter = new BinaryFormatter();
                    formatter.Serialize(stream, original);
                    stream.Position = 0;
                    Same(original, (Exception)formatter.Deserialize(stream));
                }
                Console.WriteLine("PASS serialized " + type.FullName);
                count++;
            }
        }
        Equal(12, count, "runtime exception coverage");
        return 0;
    }
}
