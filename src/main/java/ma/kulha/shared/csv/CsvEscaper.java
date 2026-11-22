package ma.kulha.shared.csv;

/**
 * Encodes untrusted values as CSV cells and prevents spreadsheet formula execution.
 */
public final class CsvEscaper {
    private CsvEscaper() {}

    public static String cell(String value) {
        if (value == null) return "\"\"";
        int firstMeaningful = 0;
        while (firstMeaningful < value.length()
                && (Character.isWhitespace(value.charAt(firstMeaningful))
                || Character.isISOControl(value.charAt(firstMeaningful))
                || value.charAt(firstMeaningful) == '\uFEFF')) {
            firstMeaningful++;
        }
        String safe = value;
        if (firstMeaningful < value.length()) {
            char first = value.charAt(firstMeaningful);
            if (first == '=' || first == '+' || first == '-' || first == '@') safe = "'" + value;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
