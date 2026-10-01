package com.mckoss.message_killer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierTest {
    private val classifier = Classifier()

    private val political = listOf(
        "Paid for by Smith for Congress. Chip in \$5 now before the midnight deadline: secure.actblue.com/x Reply STOP to quit",
        "BREAKING: Democrats just launched a 500%-MATCH! Rush \$10 now >> wnrd.us/abc STOP2END",
        "Your 2026 Official Presidential Survey is waiting. Your response is needed! Take the survey: bit.ly/xyz Txt STOP to opt out",
        "Republicans are about to lose the Senate. Donate today to stop them: winred.com/give",
        "Hi it's Jess with the DCCC. Can we count on you to pitch in \$15 for the campaign? Stop to end",
        "Election day is tomorrow! Find your polling place and vote: iwillvote.com Reply STOP to unsubscribe",
        "TRIPLE-MATCH ACTIVE: give \$25 today and it's matched 3x. Paid for by NRCC.",
    )

    // Notification previews from a real inbox (Oct 2026), exactly as visible
    // (truncated). The full text is usually even more obviously political.
    private val realInboxPreviews = listOf(
        "It's JB Pritzker. I'm 2X-matching every gift to Ready for the Fight to",
        "UPDATE: After leading in the polls for months, Democrats in MI, TX, A",
        "Angie Nixon here. Our legally required deadline is in 3 hrs. I hate",
        "It's Sherrod Brown. I'm sending one last Hail Mary request before our c",
        "It's Sherrod, sending a Hail Mary request before our end-of-quarter",
        "Amy Klobuchar here. This is the final end-of-quarter deadline befo",
        "NOBEL PEACE PRICE ANNOUNCEMENT\n\nRe: Dr. Fauci\n\nPlease read before next week's vote: dswhowin.org/l/uEel5Z\n\nEnd2End",
    )

    // Opt-out footer variants seen in a real export.
    @Test
    fun recognizesFooterVariants() {
        for (footer in listOf("Stop2End", "Stop to End", "StopToEnd", "End2End", "Stop to stop", "Text STOP to quit")) {
            assertTrue(footer, classifier.classify("Hello $footer").reasons.contains("Bulk-text opt-out footer"))
        }
    }

    // Long picture message (MMS) from a real inbox, names removed.
    @Test
    fun flagsLongBallotMeasureMms() {
        val text = """
            Hey — what do you think about our bus? Our goal is to hit the road traveling all across
            California talking with undecided voters about passing the first ever Billionaire Tax.
            Will you rush a donation? https://dem.secure-act.co/xxxx
            Will you pitch in ${'$'}15 or ${'$'}25 to directly fund our bus?
            Paid for by Tax the Ultra-Rich Now, Yes on 3 & 40, No on 41 & 42.
            Stop2End
        """.trimIndent()
        assertTrue(classifier.classify(text).isPolitical)
    }

    private val normal = listOf(
        "Hey, are we still on for dinner tonight?",
        "Your verification code is 482913. Don't share it with anyone.",
        "G-123456 is your Google verification code.",
        "Your package has been delivered. Reply STOP to unsubscribe.",
        "Did you vote yet? The line at the school was long lol",
        "Mom's birthday is Saturday, can you chip in for the cake?",
        "Flash sale! 30% off everything today only. Reply STOP to opt out",
        "Your Chase account: a payment of \$45.00 posted today.",
        "Hey, it's Adam. Running 10 minutes late!",
        "Jamie Lee here, your dental appointment is confirmed for Tuesday.",
        "The quarterly report deadline moved to Friday.",
        "HAPPY BIRTHDAY MOM!! Love you",
        "FLASH SALE TODAY: 30% off everything. Shop shop.example.com/l/abc123 Reply STOP to opt out",
    )

    @Test
    fun flagsPoliticalMessages() {
        for (text in political) {
            val result = classifier.classify(text)
            assertTrue("Expected political (score=${result.score}, ${result.reasons}): $text", result.isPolitical)
        }
    }

    @Test
    fun flagsRealInboxPreviews() {
        for (text in realInboxPreviews) {
            val result = classifier.classify(text, sender = "(302) 464-8095")
            assertTrue("Expected political (score=${result.score}, ${result.reasons}): $text", result.isPolitical)
        }
    }

    @Test
    fun leavesNormalMessagesAlone() {
        for (text in normal) {
            val result = classifier.classify(text)
            assertFalse("Expected normal (score=${result.score}, ${result.reasons}): $text", result.isPolitical)
        }
    }

    @Test
    fun verificationCodesAreNeverFlagged() {
        val text = "Paid for by Smith for Congress. Your verification code is 123456. Donate at actblue.com"
        assertFalse(classifier.classify(text).isPolitical)
    }

    @Test
    fun customKeywordFlagsOnItsOwn() {
        val custom = Classifier(customKeywords = listOf("Jane Doe"))
        val result = custom.classify("A message from Jane Doe about the town meeting")
        assertTrue(result.isPolitical)
        assertTrue(result.reasons.contains("Custom keyword: Jane Doe"))
        assertFalse(custom.classify("A message from Jane Doer").isPolitical)
    }

    @Test
    fun everythingFromATaintedSenderIsFlagged() {
        val c = Classifier(taintedSenders = listOf("+15042944686"))
        val result = c.classify("Thanks for being with us this year!", sender = "(504) 294-4686")
        assertTrue(result.isPolitical)
        assertTrue(result.reasons.contains(Classifier.TAINTED_REASON))
        assertFalse(c.classify("Thanks for being with us this year!", sender = "(504) 294-0000").isPolitical)
    }

    @Test
    fun taintedSenderStillCannotHideVerificationCodes() {
        val c = Classifier(taintedSenders = listOf("90999"))
        assertFalse(c.classify("Your verification code is 482913", sender = "90999").isPolitical)
    }

    @Test
    fun allowListBeatsTaint() {
        val c = Classifier(allowedSenders = listOf("5042944686"), taintedSenders = listOf("5042944686"))
        assertFalse(c.classify("Chip in \$5 at actblue.com", sender = "+15042944686").isPolitical)
    }

    @Test
    fun allowListedSenderIsNeverFlagged() {
        val custom = Classifier(allowedSenders = listOf("+1 (555) 123-4567"))
        val result = custom.classify(political[0], sender = "5551234567")
        assertFalse(result.isPolitical)
        assertEquals(0.0, result.score, 0.0)
    }

    @Test
    fun shortCodeAddsWeight() {
        val result = classifier.classify("Vote early this year!", sender = "88022")
        assertTrue(result.reasons.contains("Sent from a short code"))
    }

    @Test
    fun normalizesSenders() {
        assertEquals("5551234567", Classifier.normalizeSender("+1 (555) 123-4567"))
        assertEquals("88022", Classifier.normalizeSender("88022"))
        assertEquals("jess smith", Classifier.normalizeSender("  Jess Smith "))
    }
}
