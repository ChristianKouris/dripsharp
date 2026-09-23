// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

public final class Numeric {
    public static int mix(int left, int right) {
        return (left + right) ^ (left >>> right);
    }

    public static int divide(int left, int right) {
        return left / right;
    }

    public static long wide(long left, long right) {
        return (left ^ right) + 1L;
    }
}
