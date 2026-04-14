package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CalculatorTest {

    private final Calculator calc = new Calculator();

    @Test
    void add_twoPositives_returnsSum() {
        assertEquals(5, calc.add(2, 3));
    }

    @Test
    void subtract_returnsCorrectDifference() {
        assertEquals(1, calc.subtract(4, 3));
    }

    @Test
    void multiply_returnsProduct() {
        assertEquals(12, calc.multiply(3, 4));
    }

    @Test
    void divide_byZero_throwsException() {
        assertThrows(ArithmeticException.class, () -> calc.divide(10, 0));
    }
}
