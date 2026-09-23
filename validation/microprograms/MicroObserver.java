// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native launcher only: no subject algorithms or translated assertions. */
public final class MicroObserver {
    private static Class<?> type(String name) {
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "string": return String.class;
            default: throw new IllegalArgumentException("Unknown parameter type: " + name);
        }
    }

    private static Object argument(String token, Class<?> type) {
        if (type == int.class) return Integer.parseInt(token.substring(2));
        if (type == long.class) return Long.parseLong(token.substring(2));
        if (token.equals("n:")) return null;
        StringBuilder value = new StringBuilder();
        for (int i = 2; i < token.length(); i += 4)
            value.append((char) Integer.parseInt(token.substring(i, i + 4), 16));
        return value.toString();
    }

    private static String observe(Object value) {
        if (value == null) return "n:";
        if (value instanceof Integer || value instanceof Long) return "i:" + value;
        if (value instanceof String) {
            StringBuilder result = new StringBuilder("u:");
            for (char unit : ((String) value).toCharArray())
                result.append(String.format(java.util.Locale.ROOT, "%04x", (int) unit));
            return result.toString();
        }
        throw new IllegalArgumentException("Unsupported result type: " + value.getClass());
    }

    private static String category(Throwable error) throws Throwable {
        if (error instanceof ArithmeticException) return "arithmetic";
        if (error instanceof IndexOutOfBoundsException) return "index-out-of-bounds";
        if (error instanceof NullPointerException) return "null-pointer";
        if (error instanceof IllegalArgumentException) return "illegal-argument";
        throw error; // Unexpected exceptions are process failures, never parity.
    }

    public static void main(String[] args) throws Throwable {
        if (args.length == 1 && args[0].equals("--runtime-version")) {
            System.out.println(System.getProperty("java.version"));
            return;
        }
        Map<String, Method> methods = new HashMap<>();
        Map<String, Method> states = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            String[] names = fields[3].isEmpty() ? new String[0] : fields[3].split(",");
            Class<?>[] types = new Class<?>[names.length];
            for (int i = 0; i < names.length; i++) types[i] = type(names[i]);
            Method method = Class.forName(fields[1]).getMethod(fields[2], types);
            if (!Modifier.isStatic(method.getModifiers()))
                throw new IllegalArgumentException("Subject entry must be static");
            methods.put(fields[0], method);
            if (fields.length > 4 && !fields[4].isEmpty())
                states.put(fields[0], Class.forName(fields[1]).getMethod(fields[4]));
        }
        List<String> input = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        if (!input.get(0).equals("DRIPSHARP_DIFFERENTIAL_CASES_V1"))
            throw new IllegalArgumentException("Invalid input header");
        try (java.io.BufferedWriter output = Files.newBufferedWriter(Path.of(args[2]), StandardCharsets.UTF_8)) {
            output.write("DRIPSHARP_DIFFERENTIAL_CASE_RESULTS_V1\n");
            for (String line : input.subList(1, input.size())) {
                String[] fields = line.split("\t", -1);
                Method method = methods.get(fields[3]);
                Class<?>[] types = method.getParameterTypes();
                Object[] values = new Object[types.length];
                for (int i = 0; i < types.length; i++) values[i] = argument(fields[i + 5], types[i]);
                if (fields[0].equals("floating")) java.util.Locale.setDefault(
                    values[0].equals("root") ? java.util.Locale.ROOT : java.util.Locale.forLanguageTag((String)values[0]));
                String exception = "none";
                String result;
                try {
                    result = observe(method.invoke(null, values));
                } catch (InvocationTargetException error) {
                    exception = category(error.getCause());
                    result = "n:";
                }
                output.write(String.join("\t", fields[0], fields[1], fields[2], fields[3],
                                         exception, result, states.containsKey(fields[3])
                                         ? observe(states.get(fields[3]).invoke(null)) : "n:") + "\n");
            }
        }
    }
}
