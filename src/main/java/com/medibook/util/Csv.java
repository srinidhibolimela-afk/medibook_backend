package com.medibook.util;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Minimal CSV writer with proper quoting and protection against spreadsheet formula injection. */
public final class Csv {
    private Csv() {}

    public static byte[] build(List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM so Excel opens UTF-8 correctly
        sb.append(String.join(",", header.stream().map(Csv::cell).toList())).append("\r\n");
        for (List<Object> row : rows) {
            sb.append(String.join(",", row.stream().map(Csv::cell).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String cell(Object o) {
        String s = o == null ? "" : o.toString();
        // Cells starting with = + - @ would be executed as formulas by Excel; neutralise them.
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
