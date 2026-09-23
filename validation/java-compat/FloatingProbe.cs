// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
using System;
using System.Globalization;
using System.Linq;
using DripSharp.Runtime;

internal static class FloatingProbe
{
    internal static string Run(string locale, string operation, string text)
    {
        CultureInfo.CurrentCulture = locale == "root" ? CultureInfo.InvariantCulture : CultureInfo.GetCultureInfo(locale);
        if (operation == "parse-double")
        {
            var value = JavaCompat.ParseDouble(text);
            return double.IsNaN(value) ? "nan" : BitConverter.DoubleToInt64Bits(value).ToString(CultureInfo.InvariantCulture);
        }
        if (operation == "parse-float") return JavaCompat.FloatToIntBits(JavaCompat.ParseFloat(text)).ToString(CultureInfo.InvariantCulture);
        if (operation == "integral-convert")
        {
            long n = long.Parse(text, CultureInfo.InvariantCulture);
            return JavaCompat.FloatToIntBits((float)n).ToString(CultureInfo.InvariantCulture) + "|" + BitConverter.DoubleToInt64Bits((double)n).ToString(CultureInfo.InvariantCulture);
        }
        double d = JavaCompat.ParseDouble(text);
        if (operation == "boxed") return JavaCompat.NumberIntValue(d).ToString(CultureInfo.InvariantCulture) + "|" + JavaCompat.NumberLongValue(d).ToString(CultureInfo.InvariantCulture);
        if (operation == "expression")
        {
            double result = d * -1.0;
            return double.IsNaN(result) ? "nan" : BitConverter.DoubleToInt64Bits(result).ToString(CultureInfo.InvariantCulture);
        }
        if (operation == "convert")
        {
            float f = (float)d;
            return string.Join("|", new long[] { JavaCompat.NumberIntValue(d), JavaCompat.NumberLongValue(d),
                unchecked((sbyte)JavaCompat.NumberIntValue(d)), unchecked((short)JavaCompat.NumberIntValue(d)),
                unchecked((char)JavaCompat.NumberIntValue(d)), JavaCompat.FloatToIntBits(f),
                JavaCompat.NumberIntValue(f), JavaCompat.NumberLongValue(f) }.Select(value => value.ToString(CultureInfo.InvariantCulture)))
                + "|" + (float.IsNaN(f) ? "nan" : BitConverter.DoubleToInt64Bits((double)f).ToString(CultureInfo.InvariantCulture));
        }
        if (operation == "round") return JavaCompat.MathRound(d).ToString(CultureInfo.InvariantCulture) + "|" + JavaCompat.MathRoundFloat((float)d).ToString(CultureInfo.InvariantCulture);
        if (operation == "text-double") return JavaCompat.StringValueOf(d);
        if (operation == "text-float") return JavaCompat.StringValueOf(JavaCompat.ParseFloat(text));
        return new JavaDecimalFormat(operation == "pdf" ? "0.########" : operation,
            JavaCompat.DecimalFormatSymbols(operation == "pdf" ? CultureInfo.InvariantCulture : CultureInfo.CurrentCulture)).Format(d);
    }
}
