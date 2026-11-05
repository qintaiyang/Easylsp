package io.github.easylsp.demo;
import org.junit.Test;
import static org.junit.Assert.*;
public class CalculatorTest {
    @Test public void baselineAndOverload() { Calculator c = new Calculator(); assertEquals(5, c.add(2,3)); assertEquals("ab", c.add("a","b")); assertEquals(6, Calculator.multiply(2,3)); }
    @Test(expected = IllegalStateException.class) public void expectedException() { new Calculator().fail(); }
}
