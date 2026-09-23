// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

import java.util.*;

/** Bounded key/value domain: -1 is null, 0..3 are distinct colliding keys. */
public final class CollectionMachine {
    public static final class Key {
        public final int id;
        public Key(int id) { this.id = id; }
        @Override public int hashCode() { return 7; }
        @Override public boolean equals(Object other) {
            return other instanceof Key && id == ((Key) other).id;
        }
    }
    private final Map<Key, String> map;
    private final Set<Key> keys;
    private final Collection<String> values;
    private final Set<Map.Entry<Key, String>> entries;
    private final List<String> list = new ArrayList<>();
    private final List<List<String>> aliases = new ArrayList<>();
    private final Set<Key> set = new HashSet<>();
    private final Deque<String> deque = new ArrayDeque<>();
    private final int order;

    public CollectionMachine(int order) {
        this.order = order;
        map = order == 0 ? new HashMap<>() : new LinkedHashMap<>(8, 0.75f, order == 2);
        keys = map.keySet(); values = map.values(); entries = map.entrySet();
        aliases.add(list);
    }
    private static Key key(int id) { return id == -1 ? null : new Key(id); }
    private static String value(int id) { return id == -1 ? null : Integer.toString(id); }
    private static String text(String value) { return value == null ? "N" : value; }
    private static String bit(boolean value) { return value ? "1" : "0"; }
    private static String sequence(Collection<String> items) {
        StringBuilder out = new StringBuilder();
        for (String item : items) out.append(text(item)).append(',');
        return out.toString();
    }
    public String step(int op, int id, int val) {
        Key k = key(id); String v = value(val);
        switch (op) {
            case 0: return text(map.put(k, v));
            case 1: return text(map.get(k));
            case 2: return text(map.remove(k));
            case 3: return bit(keys.remove(k));
            case 4: return bit(values.remove(v));
            case 5:
                for (Map.Entry<Key, String> entry : entries)
                    if (Objects.equals(entry.getKey(), k)) return text(entry.setValue(v));
                return "absent";
            case 6: return bit(entries.remove(new AbstractMap.SimpleEntry<>(k, v)));
            case 7: return bit(list.add(v));
            case 8: return text(list.remove(id));
            case 9: return bit(list.remove((Object) v));
            case 10: return text(aliases.get(0).set(id, v));
            case 11: return bit(set.add(k));
            case 12: return bit(set.remove(k));
            case 13: deque.addFirst(v); return "void";
            case 14: deque.addLast(v); return "void";
            case 15: return text(deque.poll());
            case 16: return text(deque.remove());
            case 17: return bit(deque.remove(v));
            case 18: return bit(map.containsKey(k)) + ":" + bit(map.containsValue(v)) + ":" + bit(values.contains(v));
            case 19: keys.clear(); return "void";
            case 20: values.clear(); return "void";
            case 21: entries.clear(); return "void";
            case 22:
                Map<Key, String> changedMap = new HashMap<>(map);
                changedMap.put(new Key(9), null);
                List<String> changedList = new ArrayList<>(list); changedList.add(null);
                Set<Key> changedSet = new HashSet<>(set); changedSet.add(new Key(9));
                Map<Key, String> left = new HashMap<>(); left.put(new Key(0), null);
                Map<Key, String> right = new HashMap<>(); right.put(new Key(1), null);
                List<String> forward = new ArrayList<>(); forward.add("0"); forward.add("1");
                List<String> backward = new ArrayList<>(); backward.add("1"); backward.add("0");
                Set<Key> one = new HashSet<>(); one.add(new Key(0));
                Set<Key> two = new HashSet<>(); two.add(new Key(1));
                Deque<String> nullable = new LinkedList<>(); nullable.addFirst(null);
                String linkedState = sequence(nullable); String linkedResult = nullable.remove();
                return bit(map.equals(changedMap)) + bit(list.equals(changedList)) + bit(set.equals(changedSet))
                    + bit(left.equals(right)) + bit(forward.equals(backward)) + bit(one.equals(two))
                    + "/" + linkedState + ":" + text(linkedResult) + ":" + nullable.size();
            default: throw new IllegalArgumentException("Unknown operation");
        }
    }
    public String state() {
        StringBuilder out = new StringBuilder();
        // Canonical membership for HashMap/HashSet. Never demand their iteration order.
        for (int id = -1; id < 4; id++) {
            boolean present = false; String v = null;
            for (Map.Entry<Key, String> e : entries) {
                if (Objects.equals(e.getKey(), key(id))) { present = true; v = e.getValue(); }
            }
            out.append(bit(present)).append(':').append(text(v)).append(',');
        }
        out.append('/').append(map.size()).append(':').append(keys.size()).append(':').append(values.size());
        if (order != 0) {
            out.append('/');
            for (Key k : keys) out.append(k == null ? -1 : k.id).append(',');
        }
        out.append('/').append(sequence(list)).append('/').append(sequence(aliases.get(0)));
        out.append('/');
        for (int id = -1; id < 4; id++) out.append(bit(set.contains(key(id))));
        out.append('/').append(sequence(deque));
        // Equality/hash assertions execute the real collection contracts, including copies.
        out.append('/').append(bit(map.equals(new HashMap<>(map)))).append(':').append(map.hashCode());
        out.append('/').append(bit(list.equals(new ArrayList<>(list)))).append(':').append(list.hashCode());
        out.append('/').append(bit(set.equals(new HashSet<>(set)))).append(':').append(set.hashCode());
        return out.toString();
    }
    public static String run(int order, String script) {
        CollectionMachine machine = new CollectionMachine(order);
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (String command : script.split(";")) {
            String[] parts = command.split(",");
            int op = Integer.parseInt(parts[0]);
            String result = "N"; String exception = "none";
            try { result = machine.step(op, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])); }
            catch (IndexOutOfBoundsException e) { exception = "index-out-of-bounds"; }
            catch (NullPointerException e) { exception = "null-pointer"; }
            catch (NoSuchElementException e) { exception = "no-such-element"; }
            out.append(index++).append(':').append(op).append(':').append(exception).append(':')
                .append(result).append('|').append(machine.state()).append('\n');
        }
        return out.toString();
    }
}
