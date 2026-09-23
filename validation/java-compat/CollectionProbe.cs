// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Collections.Generic;
using System.Text;
using DripSharp.Runtime;

// Authored direct-runtime subject. Inputs and native trace formatting are shared,
// but no generated subject is used to exercise the compatibility helpers here.
internal sealed class CollectionProbe
{
    private sealed class Key(int id)
    {
        internal readonly int Id = id;
        public override int GetHashCode() => 7;
        public override bool Equals(object? other) => other is Key key && Id == key.Id;
    }
    private readonly IDictionary<Key, string> map;
    private readonly ISet<Key> keys;
    private readonly ICollection<string> values;
    private readonly ISet<JavaMapEntry<Key, string>> entries;
    private readonly IList<string> list = new List<string>();
    private readonly List<IList<string>> aliases = new();
    private readonly ISet<Key> set = new HashSet<Key>();
    private readonly JavaDeque<string> deque = new();
    private readonly int order;
    private CollectionProbe(int order)
    {
        this.order = order;
        map = order == 0 ? JavaCompat.NewJavaDictionary<Key, string>()
            : new JavaLinkedHashMap<Key, string>(8, 0.75f, order == 2);
        keys = JavaCompat.MapKeySet(map); values = map.Values; entries = JavaCompat.MapEntrySet(map);
        aliases.Add(list);
    }
    private static Key K(int id) => id == -1 ? null! : new Key(id);
    private static string V(int id) => id == -1 ? null! : id.ToString(System.Globalization.CultureInfo.InvariantCulture);
    private static string Text(string? value) => value ?? "N";
    private static string Bit(bool value) => value ? "1" : "0";
    private static string Sequence(IEnumerable<string> items)
    {
        var result = new StringBuilder();
        foreach (var item in items) result.Append(Text(item)).Append(',');
        return result.ToString();
    }
    private string Step(int op, int id, int val)
    {
        var k = K(id); var v = V(val);
        switch (op)
        {
            case 0: return Text(JavaCompat.MapPut(map, k, v));
            case 1: return Text(JavaCompat.MapGet(map, k));
            case 2: return Text(JavaCompat.MapRemove(map, k));
            case 3: return Bit(JavaCompat.CollectionRemove(keys, k));
            case 4: return Bit(JavaCompat.CollectionRemove(values, v));
            case 5:
                foreach (var entry in entries)
                    if (JavaCompat.Equals(entry.Key, k)) return Text(entry.SetValue(v));
                return "absent";
            case 6: return Bit(JavaCompat.CollectionRemove(entries, new JavaSimpleEntry<Key, string>(k, v)));
            case 7: list.Add(v); return "1";
            case 8: return Text(JavaCompat.ListRemove(list, id));
            case 9: return Bit(JavaCompat.CollectionRemove(list, v));
            case 10: return Text(JavaCompat.ListSet(aliases[0], id, v));
            case 11: return Bit(set.Add(k));
            case 12: return Bit(JavaCompat.CollectionRemove(set, k));
            case 13: deque.AddFirst(v); return "void";
            case 14: deque.AddLast(v); return "void";
            case 15: return Text(deque.Poll());
            case 16: return Text(deque.Pop());
            case 17: return Bit(JavaCompat.CollectionRemove(deque, v));
            case 18: return Bit(JavaCompat.MapContainsKey(map, k)) + ":" + Bit(JavaCompat.MapContainsValue(map, v)) + ":" + Bit(JavaCompat.CollectionContains(values, v));
            case 19: keys.Clear(); return "void";
            case 20: values.Clear(); return "void";
            case 21: entries.Clear(); return "void";
            case 22:
                var changedMap = JavaCompat.NewJavaDictionary<Key, string>(map);
                JavaCompat.MapPut(changedMap, new Key(9), null!);
                var changedList = new List<string>(list); changedList.Add(null!);
                var changedSet = new HashSet<Key>(set); changedSet.Add(new Key(9));
                var left = JavaCompat.NewJavaDictionary<Key, string>(); JavaCompat.MapPut(left, new Key(0), null!);
                var right = JavaCompat.NewJavaDictionary<Key, string>(); JavaCompat.MapPut(right, new Key(1), null!);
                var forward = new List<string> { "0", "1" }; var backward = new List<string> { "1", "0" };
                var one = new HashSet<Key> { new Key(0) }; var two = new HashSet<Key> { new Key(1) };
                var nullable = new JavaDeque<string>(true); nullable.AddFirst(null!);
                string linkedState = Sequence(nullable); string linkedResult = nullable.Pop();
                return Bit(JavaCompat.Equals(map, changedMap)) + Bit(JavaCompat.Equals(list, changedList)) + Bit(JavaCompat.Equals(set, changedSet))
                    + Bit(JavaCompat.Equals(left, right)) + Bit(JavaCompat.Equals(forward, backward)) + Bit(JavaCompat.Equals(one, two))
                    + "/" + linkedState + ":" + Text(linkedResult) + ":" + nullable.Count;
            default: throw new ArgumentException("Unknown operation");
        }
    }
    private string State()
    {
        var result = new StringBuilder();
        for (int id = -1; id < 4; id++)
        {
            bool present = false; string? value = null;
            foreach (var entry in entries)
                if (object.Equals(entry.Key, K(id))) { present = true; value = entry.Value; }
            result.Append(Bit(present)).Append(':').Append(Text(value)).Append(',');
        }
        result.Append('/').Append(map.Count).Append(':').Append(keys.Count).Append(':').Append(values.Count);
        if (order != 0)
        {
            result.Append('/');
            foreach (var key in keys) result.Append(key is null ? -1 : key.Id).Append(',');
        }
        result.Append('/').Append(Sequence(list)).Append('/').Append(Sequence(aliases[0])).Append('/');
        for (int id = -1; id < 4; id++) result.Append(Bit(set.Contains(K(id))));
        result.Append('/').Append(Sequence(deque));
        result.Append('/').Append(Bit(JavaCompat.Equals(map, JavaCompat.NewJavaDictionary<Key, string>(map)))).Append(':').Append(JavaCompat.HashCode(map));
        result.Append('/').Append(Bit(JavaCompat.Equals(list, new List<string>(list)))).Append(':').Append(JavaCompat.HashCode(list));
        result.Append('/').Append(Bit(JavaCompat.Equals(set, new HashSet<Key>(set)))).Append(':').Append(JavaCompat.HashCode(set));
        return result.ToString();
    }
    internal static string Run(int order, string script)
    {
        var machine = new CollectionProbe(order);
        var result = new StringBuilder(); int index = 0;
        foreach (var command in script.Split(';'))
        {
            var parts = command.Split(','); int op = int.Parse(parts[0], System.Globalization.CultureInfo.InvariantCulture);
            string value = "N", exception = "none";
            try { value = machine.Step(op, int.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture), int.Parse(parts[2], System.Globalization.CultureInfo.InvariantCulture)); }
            catch (ArgumentOutOfRangeException) { exception = "index-out-of-bounds"; }
            catch (NullReferenceException) { exception = "null-pointer"; }
            catch (InvalidOperationException) when (op == 16) { exception = "no-such-element"; }
            result.Append(index++).Append(':').Append(op).Append(':').Append(exception).Append(':')
                .Append(value).Append('|').Append(machine.State()).Append('\n');
        }
        return result.ToString();
    }
}
