// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

/** Minimal construction regression, including a virtual read before child assignment. */
public final class InitializationOrder {
    private static String trace = "";
    public static String state() { return trace; }
    private static void reset() { trace = ""; }
    private static int event(String name, int value) { trace += name + ";"; return value; }
    public static class InitParent {
        static int parentStatic = event("parent-static-field", 1);
        static { event("parent-static-block", 0); }
        static int afterBlock = event("parent-after-block", 9);
        int parent = event("parent-field", 2);
        { event("parent-block", 0); }
        public InitParent(int arg) { event("parent-constructor", arg); inspect(); }
        public int inspect() { return event("parent-inspect", parent); }
    }
    public static class InitChild extends InitParent {
        static int childStatic = event("child-static-field", 3);
        static { event("child-static-block", 0); }
        int child = event("child-field", 4);
        public InitChild() { this(event("this-argument", 5)); event("delegating-constructor", 0); }
        public InitChild(int arg) { super(event("super-argument", arg)); event("child-constructor", 0); }
        public int inspect() { return event("child-inspect-" + child, child); }
    }
    public static class InitLeaf extends InitChild {}
    public static int initialization(int x, int y) {
        reset();
        event("before-new", 0);
        InitChild child = x == 0 ? new InitChild() : new InitLeaf();
        event("after-new", 0);
        return child.inspect() + InitParent.parentStatic + InitChild.childStatic;
    }
}
