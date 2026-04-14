package com.example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StringUtilsTest {

    private final StringUtils utils = new StringUtils();

    @Test
    void reverse_returnsReversedString() {
        assertEquals("olleh", utils.reverse("hello"));
    }

    @Test
    void isPalindrome_detectsPalindrome() {
        assertTrue(utils.isPalindrome("racecar"));
        assertFalse(utils.isPalindrome("hello"));
    }

    @Test
    void capitalize_firstLetterUppercase() {
        assertEquals("Hello", utils.capitalize("hello"));
    }
}
