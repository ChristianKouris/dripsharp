// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
public final class FloatingCases {
    public static String run(String locale, String operation, String text) {
        if (operation.equals("parse-double")) {
            double parsed = Double.parseDouble(text);
            return Double.isNaN(parsed) ? "nan" : Long.toString(Double.doubleToRawLongBits(parsed));
        }
        if (operation.equals("parse-float")) return Integer.toString(Float.floatToIntBits(Float.parseFloat(text)));
        if (operation.equals("integral-convert")) {
            long n = Long.parseLong(text);
            return Float.floatToIntBits((float)n) + "|" + Double.doubleToRawLongBits((double)n);
        }
        double d = Double.parseDouble(text);
        if (operation.equals("boxed")) {
            Number n = Double.valueOf(text);
            return n.intValue() + "|" + n.longValue();
        }
        if (operation.equals("expression")) {
            double result = d * -1.0;
            return Double.isNaN(result) ? "nan" : Long.toString(Double.doubleToRawLongBits(result));
        }
        if (operation.equals("convert")) {
            float f = (float)d;
            return (int)d + "|" + (long)d + "|" + (byte)d + "|" + (short)d + "|" + (int)(char)d
                + "|" + Float.floatToIntBits(f) + "|" + (int)f + "|" + (long)f
                + "|" + (Float.isNaN(f) ? "nan" : Long.toString(Double.doubleToRawLongBits((double)f)));
        }
        if (operation.equals("round")) return Math.round(d) + "|" + Math.round((float)d);
        if (operation.equals("text-double")) return Double.toString(d);
        if (operation.equals("text-float")) return Float.toString(Float.parseFloat(text));
        if (operation.equals("pdf")) return new DecimalFormat("0.########", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(d);
        return new DecimalFormat(operation, DecimalFormatSymbols.getInstance(Locale.getDefault())).format(d);
    }
}
