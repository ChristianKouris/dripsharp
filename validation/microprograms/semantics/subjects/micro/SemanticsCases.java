// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

/** Explicit templates. Inputs select bounded branches, never Java syntax. */
public final class SemanticsCases {
    private static String trace = "";
    private static int count;
    private static int[] cells;
    public static String state() { return trace + "|count=" + count; }
    private static void reset() { trace = ""; count = 0; }
    private static int event(String name, int value) {
        trace += name + ";";
        count++;
        return value;
    }
    private static int[] array() { event("array", 0); return cells; }
    private static int right(int value) { event("rhs", 0); cells[0] = 99; return value; }
    private static SemanticsCases receiver() { event("receiver", 0); return new SemanticsCases(); }
    private int combine(int a, int b) { event("call", 0); return a * 10 + b; }
    public static int order(int x, int y) {
        reset();
        cells = new int[]{x};
        int saved = array()[event("index", 0)] += right(y);
        int result = receiver().combine(event("left", x), event("right", y));
        return saved + result + cells[0];
    }
    private static boolean flag(String name, boolean value) { event(name, 0); return value; }
    public static int shortCircuit(int x, int y) {
        reset();
        boolean a = flag("and-left", x > 0) && flag("and-right", y > 0);
        boolean b = flag("or-left", x > 0) || flag("or-right", y > 0);
        int c = flag("condition", a) ? event("then", x) : event("else", y);
        return c + (b ? 1 : 0);
    }
    public static int unbox(int x, int y) {
        reset();
        Integer boxed = x == 0 ? null : Integer.valueOf(y);
        event("before-unbox", 0);
        int value = boxed;
        event("after-unbox", 0);
        return value;
    }
    public static int cast(int x, int y) {
        reset();
        Object value = x == 0 ? null : (x == 1 ? "text" : new Object());
        event("before-cast", 0);
        String text = (String)value;
        event("after-cast", 0);
        return text == null ? -1 : text.length();
    }
    public static String conversion(int x, int y) {
        reset();
        Object missing = null;
        char unit = (char)x;
        byte narrowed = (byte)y;
        return "" + event("number", x) + ":" + (x + y) + ":" + unit + ":" + narrowed + ":" + missing;
    }
    public interface Voice {
        int speak();
        default int fallback() { return event("default", 30); }
    }
    public static class Parent implements Voice {
        public int value = 4;
        public int speak() { return event("parent", value); }
    }
    public static class Child extends Parent {
        public int value = 7;
        public int speak() { return event("child", value); }
        public int parentCall() { return super.speak(); }
    }
    public static class DefaultChild implements Voice {
        public int speak() { return event("default-child", 11); }
        public int fallback() { return event("override", 1) + Voice.super.fallback(); }
    }
    public static int dispatch(int x, int y) {
        reset();
        Child child = new Child();
        Parent parent = child;
        Voice voice = parent;
        DefaultChild defaults = new DefaultChild();
        return parent.speak() + voice.speak() + child.parentCall() + voice.fallback()
            + defaults.fallback() + parent.value * 100 + child.value * 10;
    }
    public static class Box<T> {
        public T map(T value) { event("generic-base", 0); return value; }
    }
    public static class StringBox extends Box<String> {
        public String map(String value) { event("generic-child", 0); return value + "!"; }
    }
    public static String bridge(int x, int y) {
        reset();
        Box<String> box = new StringBox();
        return box.map("value");
    }
    private static int throughFinally(int mode, int value) {
        try {
            event("try", 0);
            if (mode == 1) throw new IllegalArgumentException("body");
            return event("return", value);
        } finally {
            event("finally", 0);
            if (mode == 2) throw new NullPointerException("finalizer");
        }
    }
    public static int control(int x, int y) {
        reset();
        try {
            try { return throughFinally(x, y); }
            catch (IllegalArgumentException error) {
                event("inner-catch", 0);
                throw new NullPointerException("nested");
            } finally { event("inner-finally", 0); }
        } catch (NullPointerException error) {
            return event("outer-catch", -1);
        } finally { event("outer-finally", 0); }
    }
    public static class Resource implements AutoCloseable {
        private final String name;
        private final boolean fail;
        public Resource(String name, boolean fail) { this.name = name; this.fail = fail; event("open-" + name, 0); }
        public void close() {
            event("close-" + name, 0);
            if (fail) throw new IllegalArgumentException(name);
        }
    }
    public static int cleanup(int x, int y) {
        reset();
        try (Resource first = new Resource("first", x > 0);
             Resource second = new Resource("second", x > 1)) {
            event("body", 0);
            if (x > 2) throw new IllegalArgumentException("body");
            return y;
        } catch (IllegalArgumentException error) {
            event("caught-" + error.getMessage(), 0);
            return error.getSuppressed().length;
        } finally { event("cleanup-finally", 0); }
    }
}
