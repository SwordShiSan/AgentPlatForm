package com.agentplatform.app.chat;

/** Explicit extra stage scope, never an arbitrary JDBC URL escape hatch. */
final class FixtureDatabaseScope {
    private FixtureDatabaseScope() {}
    static boolean r44(String url){return "1".equals(System.getenv("R44_ACCEPTANCE"))
        && "jdbc:postgresql://127.0.0.1:15457/r44_test?connectTimeout=2&socketTimeout=5".equals(url)
        && url.equals(System.getenv("R44_TEST_JDBC_URL"));}
    static boolean r62(String url) {
        String port = System.getenv("R62_TEST_PORT");
        return "1".equals(System.getenv("R62_ACCEPTANCE")) && port != null && port.matches("[0-9]{1,5}")
                && url.equals("jdbc:postgresql://127.0.0.1:" + port + "/r62_test?sslmode=disable")
                && url.equals(System.getenv("R62_TEST_JDBC_URL"));
    }
}
