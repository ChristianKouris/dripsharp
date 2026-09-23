// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

/** Minimal overload regression: the local is known non-null, the parameter is boxed. */
public final class BoxedOverload {
    private static String trace = "";
    public static String state() { return trace; }
    private static void reset() { trace = ""; }
    private static int event(String name, int value) { trace += name + ";"; return value; }
    private static int select(long value) { return event("long", 1); }
    private static int select(Integer value) { return event("Integer", 2); }
    private static int select(Object value) { return event("Object", 3); }
    public static int overloads(int x, int y) {
        reset();
        Integer boxed = x;
        Object object = boxed;
        return select(x) * 100 + select(boxed) * 10 + select(object);
    }
}
