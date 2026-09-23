// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

import java.io.*;

public final class IoFlows {
    public static class Source extends InputStream {
        private final StringBuilder trace;
        private final String name;
        private final int failAt;
        private final boolean failClose;
        private int position;
        public Source(StringBuilder trace, String name, int failAt, boolean failClose) {
            this.trace = trace; this.name = name; this.failAt = failAt; this.failClose = failClose;
            trace.append("open:").append(name).append(';');
        }
        public int read() throws IOException {
            trace.append("read:").append(name).append(':').append(position).append(';');
            if (position == failAt) throw new IOException("read-failure");
            if (position == 4) return -1;
            return new int[] {0, 127, 128, 255}[position++];
        }
        public void close() throws IOException {
            trace.append("close:").append(name).append(';');
            if (failClose) throw new IOException("close-" + name);
        }
    }
    public static class Wrapper extends FilterInputStream {
        private final StringBuilder trace;
        private final String name;
        public Wrapper(InputStream input, StringBuilder trace, String name) {
            super(input); this.trace = trace; this.name = name;
        }
        public void close() throws IOException {
            trace.append("close:").append(name).append(';'); super.close();
        }
    }
    private static void buffer(StringBuilder trace, byte[] bytes) {
        trace.append("buffer:");
        for (byte value : bytes) trace.append((int)value).append(',');
        trace.append(';');
    }
    private static void error(StringBuilder trace, Throwable error) {
        trace.append(error instanceof IOException ? "io:" : "other:").append(error.getMessage());
        Throwable cause = error.getCause();
        trace.append("/cause:").append(cause == null ? "null" : cause.getMessage());
        trace.append("/suppressed:");
        for (Throwable suppressed : error.getSuppressed()) {
            error(trace, suppressed); trace.append(',');
        }
        trace.append(';');
    }
    public static String describe(Throwable failure) {
        StringBuilder trace = new StringBuilder(); error(trace, failure); return trace.toString();
    }
    public static class BulkSource extends Source {
        private final StringBuilder trace;
        public BulkSource(StringBuilder trace) { super(trace, "bulk", 99, false); this.trace = trace; }
        public int read(byte[] bytes) throws IOException {
            trace.append("whole;"); return super.read(bytes);
        }
        public int read(byte[] bytes, int offset, int count) throws IOException {
            trace.append("slice:").append(offset).append(':').append(count).append(';');
            if (count == 0) return 0;
            bytes[offset] = (byte)255;
            throw new IOException("bulk-failure");
        }
    }
    public static String overloads(int mode) throws IOException {
        StringBuilder trace = new StringBuilder();
        byte[] bytes = new byte[] {42, 42, 42};
        try (InputStream input = mode < 3 ? new BulkSource(trace)
                : new Wrapper(new BulkSource(trace), trace, "wrapper")) {
            try {
                int count;
                if (mode % 3 == 0) count = input.read(bytes);
                else count = input.read(bytes, 1, mode % 3 == 1 ? 1 : 0);
                trace.append("result:").append(count).append(';');
            } catch (IOException failure) { error(trace, failure); }
            buffer(trace, bytes);
        }
        return trace.toString();
    }
    // Re-run the prefix for each independently replayable operation.
    public static String reads(int failAt, int step, int wrapped) throws IOException {
        StringBuilder trace = new StringBuilder();
        InputStream source = new Source(trace, "source", failAt, false);
        if (wrapped != 0) source = new Wrapper(new Wrapper(source, trace, "inner"), trace, "outer");
        byte[] bytes = new byte[] {42, 42, 42, 42, 42, 42};
        try (InputStream input = source) {
            for (int operation = 0; operation <= step; operation++) {
                trace.append("op:").append(operation).append(';');
                try {
                    int count;
                    if (operation == 0) count = input.read(bytes, 2, 0);
                    else if (operation == 1) count = input.read();
                    else if (operation == 2) count = input.read(bytes, 1, 4);
                    else if (operation == 3) count = input.read(bytes);
                    else if (operation == 4) count = input.read();
                    else count = input.read(bytes, bytes.length, 0);
                    trace.append("result:").append(count).append(';');
                } catch (IOException failure) { error(trace, failure); }
                buffer(trace, bytes);
            }
        }
        return trace.toString();
    }
    public static String byteArray(int step) throws IOException {
        StringBuilder trace = new StringBuilder();
        byte[] bytes = new byte[] {42, 42, 42, 42};
        try (InputStream input = new ByteArrayInputStream(new byte[] {(byte)128, (byte)255})) {
            for (int operation = 0; operation <= step; operation++) {
                int count;
                if (operation == 0) count = input.read(bytes, 1, 0);
                else if (operation == 1) count = input.read(bytes, 1, 2);
                else if (operation == 2) count = input.read(bytes);
                else if (operation == 3) count = input.read(bytes, 4, 0);
                else count = input.read();
                trace.append("op:").append(operation).append("/result:").append(count).append(';');
                buffer(trace, bytes);
            }
        }
        return trace.toString();
    }
    private static Source open(StringBuilder trace, int mode) throws IOException {
        if (mode == 5) { trace.append("init-failure;"); throw new IOException("init"); }
        return mode == 6 ? null : new Source(trace, "second", 99, mode == 2 || mode == 3);
    }
    private static int body(StringBuilder trace, int mode) throws IOException {
        try (Source first = new Source(trace, "first", 99, mode >= 2 && mode <= 5);
             Source second = open(trace, mode)) {
            trace.append("body;");
            if (mode == 1 || mode == 2 || mode == 4 || mode == 8) {
                IOException primary = new IOException("body", new IOException("cause"));
                throw primary;
            }
            return 17;
        } finally {
            trace.append("finally;");
            if (mode == 7) trace.append("return-path;");
            if (mode == 8) throw new IOException("finally");
        }
    }
    public static String cleanup(int mode) {
        StringBuilder trace = new StringBuilder();
        try { trace.append("return:").append(body(trace, mode)).append(';'); }
        catch (IOException failure) { error(trace, failure); }
        return trace.toString();
    }
    public static String referenced(int mode) {
        StringBuilder trace = new StringBuilder();
        Source source = new Source(trace, "reference", 99, mode != 0);
        try (source) {
            try (Source nested = new Source(trace, "nested", 99, mode != 0)) {
                trace.append("body;");
                if (mode == 2) throw new IOException("body");
            }
        } catch (IOException failure) { error(trace, failure); }
        return trace.toString();
    }
    public static class Sink extends OutputStream {
        private final StringBuilder trace;
        private final int failAt;
        private int count;
        public Sink(StringBuilder trace, int failAt) { this.trace = trace; this.failAt = failAt; }
        public void write(int value) throws IOException {
            trace.append("write:").append(value & 255).append(';');
            if (count++ == failAt) throw new IOException("write-failure");
        }
        public void close() { trace.append("close:sink;"); }
    }
    public static String writes(int failAt, int step) throws IOException {
        StringBuilder trace = new StringBuilder();
        try (OutputStream output = new FilterOutputStream(new FilterOutputStream(new Sink(trace, failAt)))) {
            for (int operation = 0; operation <= step; operation++) {
                trace.append("op:").append(operation).append(';');
                try {
                    if (operation == 0) output.write(255);
                    else if (operation == 1) output.write(new byte[] {0, 127, (byte)128, (byte)255});
                    else if (operation == 2) output.write(new byte[] {42, (byte)255, (byte)128, 42}, 1, 2);
                    else output.write(new byte[0]);
                } catch (IOException failure) { error(trace, failure); }
            }
        }
        return trace.toString();
    }
    public static class ClosingSink extends OutputStream {
        private final StringBuilder trace;
        private final int mode;
        public ClosingSink(StringBuilder trace, int mode) { this.trace = trace; this.mode = mode; }
        public void write(int value) { trace.append("write:").append(value).append(';'); }
        public void flush() throws IOException {
            trace.append("flush;");
            if ((mode & 1) != 0) throw new IOException("flush");
        }
        public void close() throws IOException {
            trace.append("close:sink;");
            if ((mode & 2) != 0) throw new IOException("close");
        }
    }
    public static String wrapperCleanup(int mode) {
        StringBuilder trace = new StringBuilder();
        try (OutputStream output = new FilterOutputStream(new FilterOutputStream(new ClosingSink(trace, mode)))) {
            trace.append("body;");
            if ((mode & 4) != 0) throw new IOException("body");
        } catch (IOException failure) { error(trace, failure); }
        return trace.toString();
    }
    public static String suppression() {
        IOException primary = new IOException("primary");
        IOException secondary = new IOException("secondary");
        primary.addSuppressed(secondary);
        primary.addSuppressed(secondary);
        Throwable[] copy = primary.getSuppressed();
        copy[0] = new IOException("must-not-replace");
        StringBuilder trace = new StringBuilder();
        error(trace, primary);
        try { primary.addSuppressed(primary); }
        catch (IllegalArgumentException expected) { trace.append("self-rejected;"); }
        try { primary.addSuppressed(null); }
        catch (NullPointerException expected) { trace.append("null-rejected;"); }
        return trace.toString();
    }
    // Public .NET Stream boundary: callers may return fewer bytes than requested.
    public static String consume(InputStream input) throws IOException {
        StringBuilder trace = new StringBuilder();
        byte[] bytes = new byte[] {42, 42, 42, 42};
        try (InputStream resource = input) {
            for (int operation = 0; operation < 4; operation++) {
                trace.append(resource.read(bytes, 1, 2)).append(';'); buffer(trace, bytes);
            }
        }
        return trace.toString();
    }
}
