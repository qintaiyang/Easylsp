package io.github.easylsp.demo;
public final class Calculator {
    public static final String MARKER = "EASYLSP_DEMO_ADDITION";
    public int add(int a, int b) { return a + b; }
    public String add(String a, String b) { return a + b; }
    public static int multiply(int a, int b) { return a * b; }
    public int fail() { throw new IllegalStateException("EASYLSP_DEMO_EXPECTED_EXCEPTION"); }
    public String marker() { return MARKER; }
}
