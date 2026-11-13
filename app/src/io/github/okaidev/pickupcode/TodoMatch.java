package io.github.okaidev.pickupcode;

/** SQL identity helpers shared by module and system-process writers. */
final class TodoMatch {
    static final String ROW_PREFIX = "PICKUP_ROW:";
    static final String CAPTURE_ROW_SQL = "SELECT '" + ROW_PREFIX + "' || last_insert_rowid();\n";

    static String quote(String value) {
        return "'" + (value == null ? "" : value.replace("\0", "").replace("'", "''")) + "'";
    }

    /** Read-only compatibility for old rows/frozen-process writes with no stored target ID.
     * Accept only the exact minimal template or a full-template prefix, never a substring.
     */
    static String legacyClause(String column, String code) {
        if (code == null || code.length() > 32 || !code.matches("[A-Za-z0-9]+(?:-[A-Za-z0-9]+){0,4}")) {
            return "0";
        }
        String prefix = "取件码 " + code + "｜";
        return "(" + column + "=" + quote(code) + " OR substr(" + column + ",1,"
                + prefix.length() + ")=" + quote(prefix) + ")";
    }

    static long insertedRow(String output) {
        if (output == null) return -1;
        for (String line : output.split("\\R")) {
            if (line.startsWith(ROW_PREFIX)) {
                try { return Long.parseLong(line.substring(ROW_PREFIX.length()).trim()); }
                catch (NumberFormatException ignored) { }
            }
        }
        return -1;
    }

    private TodoMatch() { }
}
