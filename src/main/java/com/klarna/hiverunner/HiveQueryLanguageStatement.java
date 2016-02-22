package com.klarna.hiverunner;

public class HiveQueryLanguageStatement {

    public static HiveQueryLanguageStatement forStatementString(String statementString) {
        return new HiveQueryLanguageStatement(statementString);
    }

    private final String statementString;

    private HiveQueryLanguageStatement(String statementString) {
        this.statementString = statementString;
    }

    public String getStatementString() {
        return statementString;
    }
}
