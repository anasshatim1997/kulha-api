package ma.kulha.shared.csv;

/**
 * Encodes untrusted values as CSV cells and prevents spreadsheet formula execution.
 *
 * <p>CSV quoting alone does not prevent spreadsheet applications from evaluating cells
 * beginning with formula markers. A leading apostrophe forces those values to be treated
 * as text when opened in common spreadsheet applications.</p>
 */
public final class CsvEscaper {

    private CsvEscaper() {
    }

    public static String cell(String value) {
        if (value == null) {
            return "\"\"";
        }

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
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                safe = "'" + value;
            }
        } else if (!value.isEmpty()) {
            // A cell made entirely of control/whitespace characters is not executable,
            // but quote it normally rather than dropping user-provided data.
            safe = value;
        }

        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
}
