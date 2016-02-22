package com.klarna.hiverunner.sql;

import com.klarna.hiverunner.HiveQueryLanguageStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;
import java.util.regex.Pattern;

/**
 * Splits hive sql statements into executable elements.
 *
 * Input will be split on ';'.
 * ';' in comments (--) or quotes (' or ") will be ignored.
 *
 * Trailing whitespaces and empty elements caused by multiple ';' will be removed.
 * <p/>
 * E.g:
 * foo;bar
 * baz -- comment with ;
 * 'fox;';
 * love
 * <p/>
 * will be split into
 * <p/>
 * [foo,
 * bar
 * baz -- comment with ;
 * 'fox;',
 * love]
 */
public class StatementsSplitter {

    public static final Pattern START_OF_COMMENT_PATTERN = Pattern.compile(".*\\s--", Pattern.DOTALL);
    public static final Pattern LAST_CHAR_NOT_ESCAPED_PATTERN = Pattern.compile(".*[^\\\\].", Pattern.DOTALL);

    public static final String SQL_SPECIAL_CHARS = ";\"'-\n";
    public static final String BEELINE_SPECIAL_CHARS = "!";

    /**
     * Splits expression on ';'.
     * Also, detects Beeline's SQLLine commands (commands start with '!' and do not end with ';').
     * ';' within quotes (" or ') or comments ( -- ) are ignored.
     */
    public static List<HiveQueryLanguageStatement> splitStatements(String expression) {
        StringTokenizer tokenizer = new StringTokenizer(expression, SQL_SPECIAL_CHARS + BEELINE_SPECIAL_CHARS, true);

        List<HiveQueryLanguageStatement> statements = new ArrayList<>();
        String statement = "";
        while (tokenizer.hasMoreElements()) {
            String token = (String) tokenizer.nextElement();
            switch (token) {

                // Close statement and start a new one
                case ";":
                    // Only add statement that is not empty
                    if (isValidStatement(statement)) {
                        statements.add(HiveQueryLanguageStatement.forStatementString(statement));
                    }
                    statement = "";
                    break;

                // Preserve comments (--)
                case "-":
                    statement += token;
                    if (START_OF_COMMENT_PATTERN.matcher(statement).matches()) {
                        statement += readUntilEndOfLine(tokenizer);
                    }
                    break;

                // Preserve quotes
                case "\"":
                case "'":
                    statement += readQuoted(tokenizer, token);
                    break;

                // Beeline's SQLLine commands
                case "!":
                    if (statement.trim().isEmpty()) {
                        statement += token;
                        statement += readUntilEndOfLine(tokenizer);
                        // Only add statement that is not empty
                        if (isValidStatement(statement)) {
                            statements.add(HiveQueryLanguageStatement.forStatementString(statement));
                        }
                        statement = "";
                        break;
                    }

                // Add any other elements to the current statement
                default:
                    statement += token;
            }
        }

        // Only add statement that is not empty
        if (isValidStatement(statement)) {
            statements.add(HiveQueryLanguageStatement.forStatementString(statement));
        }
        return statements;
    }

    private static boolean isValidStatement(String statement) {
        // Empty strings does not validate.
        return statement.trim().length() > 0;
    }

    static String readUntilEndOfLine(StringTokenizer tokenizer) {
        String statement = "";

        while (tokenizer.hasMoreElements()) {
            statement += tokenizer.nextElement();
            if (statement.endsWith("\n")) {
                break;
            }
        }

        return statement;
    }

    static String readQuoted(StringTokenizer tokenizer, String startQuoteToken) {
        String quotedString = startQuoteToken;
        while (tokenizer.hasMoreElements()) {
            quotedString += (String) tokenizer.nextElement();
            // If the last char is an end of quote token and it was not escaped by the previous token, we break.
            if (quotedString.endsWith(startQuoteToken) &&
                    LAST_CHAR_NOT_ESCAPED_PATTERN.matcher(quotedString).matches()) {
                break;
            }
        }
        return quotedString;
    }

}
