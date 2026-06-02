package dev.tally.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class EntityExtractorTest {

    @Test
    fun plainTextIsNone() {
        assertEquals(EntityType.NONE, EntityExtractor.classify("hello world"))
    }

    @Test
    fun emptyStringIsNone() {
        assertEquals(EntityType.NONE, EntityExtractor.classify(""))
    }

    @Test
    fun blankStringIsNone() {
        assertEquals(EntityType.NONE, EntityExtractor.classify("   "))
    }

    @Test
    fun httpsUrlDetected() {
        assertEquals(EntityType.URL, EntityExtractor.classify("https://example.com/path?q=1"))
    }

    @Test
    fun httpUrlDetected() {
        assertEquals(EntityType.URL, EntityExtractor.classify("http://foo.bar"))
    }

    @Test
    fun ftpUrlDetected() {
        assertEquals(EntityType.URL, EntityExtractor.classify("ftp://files.example.org/data.zip"))
    }

    @Test
    fun wwwUrlDetected() {
        assertEquals(EntityType.URL, EntityExtractor.classify("www.example.com/page"))
    }

    @Test
    fun emailDetected() {
        assertEquals(EntityType.EMAIL, EntityExtractor.classify("user@example.com"))
    }

    @Test
    fun emailWithPlusDetected() {
        assertEquals(EntityType.EMAIL, EntityExtractor.classify("user+tag@mail.co.uk"))
    }

    @Test
    fun urlTakesPriorityOverEmail() {
        // A URL that also looks like it has an email-like component.
        assertEquals(EntityType.URL, EntityExtractor.classify("https://mail.google.com/"))
    }

    @Test
    fun phoneNorthAmericanDetected() {
        assertEquals(EntityType.PHONE, EntityExtractor.classify("+1 (555) 867-5309"))
    }

    @Test
    fun phoneInternationalDetected() {
        assertEquals(EntityType.PHONE, EntityExtractor.classify("+44 20 7946 0958"))
    }

    @Test
    fun shortNumericStringNotPhone() {
        // Five digits — too short to be a real phone number.
        assertEquals(EntityType.NONE, EntityExtractor.classify("12345"))
    }

    @Test
    fun streetAddressDetected() {
        assertEquals(EntityType.ADDRESS, EntityExtractor.classify("123 Main Street, Springfield"))
    }

    @Test
    fun addressRequiresStreetNumber() {
        // No leading number → not an address.
        assertEquals(EntityType.NONE, EntityExtractor.classify("Main Street, Springfield"))
    }

    @Test
    fun numbersOnlyIsNone() {
        assertEquals(EntityType.NONE, EntityExtractor.classify("42"))
    }
}
