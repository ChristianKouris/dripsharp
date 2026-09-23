// SPDX-FileCopyrightText: 2026 Isak Sky
// SPDX-License-Identifier: Apache-2.0
package micro;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded translated-call template; input generation stays outside both runtimes. */
public final class RegexSequences {
  private RegexSequences() {}

  private static String group(Matcher matcher, int index) {
    String value = matcher.group(index);
    return matcher.start(index) + "," + matcher.end(index) + "," +
        (value == null ? "~" : "[" + value + "]");
  }

  private static String match(Matcher matcher) {
    StringBuilder result = new StringBuilder();
    for (int index = 0; index <= matcher.groupCount(); index++) {
      if (index != 0) result.append(';');
      result.append(group(matcher, index));
    }
    return result.toString();
  }

  private static void find(StringBuilder result, Matcher matcher) {
    boolean success = matcher.find();
    result.append(success).append(':').append(success ? match(matcher) : "~");
  }

  public static String run(String pattern, int flags, String input, String argument, int mode) {
    if ((flags & ~511) != 0) return "compile:illegal-argument";
    Pattern compiled;
    try {
      compiled = Pattern.compile(pattern, flags);
    } catch (IllegalArgumentException error) {
      return "compile:pattern-syntax";
    }

    if (mode == 2) {
      String[] values = compiled.split(input, Integer.parseInt(argument));
      StringBuilder result = new StringBuilder("split:").append(values.length);
      for (String value : values) result.append(':').append('[').append(value).append(']');
      return result.toString();
    }

    Matcher matcher = compiled.matcher(input);
    if (mode == 3) {
      try {
        return "replaceAll:[" + matcher.replaceAll(argument) + "]";
      } catch (IllegalArgumentException error) {
        return "replaceAll:illegal-argument";
      }
    }
    if (mode == 4) {
      return "quoteReplacement:[" + Matcher.quoteReplacement(argument) + "]";
    }

    StringBuilder result = new StringBuilder();
    if (mode == 1) {
      matcher.region(1, Math.max(1, input.length() - 1));
      result.append("region|");
      boolean matched = matcher.matches();
      result.append(matched).append(':').append(matched ? match(matcher) : "~");
      result.append("|reset|");
      matcher.reset();
      find(result, matcher);
      return result.toString();
    }

    result.append("find|");
    find(result, matcher);
    result.append("|find|");
    find(result, matcher);
    result.append("|reset|");
    matcher.reset();
    find(result, matcher);
    return result.toString();
  }
}
