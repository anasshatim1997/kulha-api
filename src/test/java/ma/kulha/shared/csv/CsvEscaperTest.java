package ma.kulha.shared.csv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvEscaperTest {

    @Test
    void quotesValuesAndEscapesEmbeddedQuotes() {
        assertThat(CsvEscaper.cell("Boulangerie \"Atlas\"")).isEqualTo("\"Boulangerie \"\"Atlas\"\"\"");
    }

    @Test
    void representsNullAsAnEmptyCell() {
        assertThat(CsvEscaper.cell(null)).isEqualTo("\"\"");
    }

    @Test
    void neutralizesEverySpreadsheetFormulaPrefix() {
        for (String prefix : new String[] {"=", "+", "-", "@"}) {
            assertThat(CsvEscaper.cell(prefix + "SUM(A1:A2)"))
                    .isEqualTo("\"'" + prefix + "SUM(A1:A2)\"");
        }
    }

    @Test
    void neutralizesFormulaPrefixesAfterLeadingWhitespaceAndControlCharacters() {
        assertThat(CsvEscaper.cell("  =1+1")).isEqualTo("\"'  =1+1\"");
        assertThat(CsvEscaper.cell("\t=1+1")).isEqualTo("\"'\t=1+1\"");
        assertThat(CsvEscaper.cell("\r\n@SUM(A1)")).isEqualTo("\"'\r\n@SUM(A1)\"");
    }

    @Test
    void leavesOrdinaryTextUnchanged() {
        assertThat(CsvEscaper.cell("Panier du fournil")).isEqualTo("\"Panier du fournil\"");
        assertThat(CsvEscaper.cell("support@example.com")).isEqualTo("\"support@example.com\"");
    }
}
