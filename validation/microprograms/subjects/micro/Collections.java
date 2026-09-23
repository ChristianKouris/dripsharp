// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

import java.util.ArrayList;
import java.util.List;

public final class Collections {
    private static String select(List<String> values, int index) {
        return values.get(index);
    }

    public static String pick(String first, String second, int index) {
        List<String> values = new ArrayList<>();
        values.add(first);
        values.add(second);
        values.set(0, second);
        values.set(1, first);
        return select(values, index);
    }
}
