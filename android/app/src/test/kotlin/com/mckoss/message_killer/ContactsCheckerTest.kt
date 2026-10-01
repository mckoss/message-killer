package com.mckoss.message_killer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsCheckerTest {
    @Test
    fun recognizesPhoneNumbersVersusNames() {
        assertTrue(ContactsChecker.looksLikePhoneNumber("(302) 464-8095"))
        assertTrue(ContactsChecker.looksLikePhoneNumber("+13024648095"))
        assertTrue(ContactsChecker.looksLikePhoneNumber("88022"))
        assertFalse(ContactsChecker.looksLikePhoneNumber("Mom"))
        assertFalse(ContactsChecker.looksLikePhoneNumber("Jess Smith"))
        assertFalse(ContactsChecker.looksLikePhoneNumber("Room 101"))
    }
}
