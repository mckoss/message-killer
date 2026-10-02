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
        assertTrue(classifier.classify(text).isSpam)
    }

    private val normal = listOf(
        "Hey, are we still on for dinner tonight?",
        "Your verification code is 482913. Don't share it with anyone.",
        "G-123456 is your Google verification code.",
        "Your package has been delivered. Reply STOP to unsubscribe.",
        "Did you vote yet? The line at the school was long lol",
        "Mom's birthday is Saturday, can you chip in for the cake?",
        "Your Chase account: a payment of \$45.00 posted today.",
        "Hey, it's Adam. Running 10 minutes late!",
        "Jamie Lee here, your dental appointment is confirmed for Tuesday.",
        "The quarterly report deadline moved to Friday.",
        "HAPPY BIRTHDAY MOM!! Love you",
    )

    // More political texts that got through (Oct 2026), links shortened.
    private val morePolitical = listOf(
        "Hi, I am Mia from US Speaks. We're polling WA residents. Can you answer a 4-question poll?\n\n1) Yes\n2) No (or QUIT Survey)",
        "BOYCOTT: TV Networks Stop Coverage of Trump Over Media Ban\n\nDo you support the media's historic BOYCOTT of Trump?\n\nY / N: dem-action.org/l/xxxxx\n\nDAC\nStop2End",
        "BREAKING: Steve Kornacki's SHOCKING prediction about House control. Read more: house-dem-victory.org/l/xxxxx\n\nHDV\nStop2End",
        "My last-ditch request. - James Talarico\n\nI'll be blunt: We're at risk of falling short tonight. If it happens, we'll be forced to make budget cuts. That's a risk we can't afford. So we need folks who want to flip Texas to give in the next hour. Use this link: i.example.com/xxxx\n\nStop2End",
        "I'm Martin O'Malley, former Social Security Administration commissioner. I'm asking for your help to defend Social Security >> d-so.org/l/xxxxx\n\nDSS\nEnd2End",
    )

    // Pharmacy notices from a store that also sends coupons must never be filtered.
    private val transactional = listOf(
        "CVS Pharmacy: Your prescription is ready for pickup at 123 Main St. Reply STOP to opt out",
        "CVS Pharmacy: Your refill is ready. Questions? Call 800-555-0100. Reply HELP for help, STOP to end",
        "CVS: Your order #12345 has shipped. Track it: cvs.com/track. Reply STOP to opt out",
        "Walgreens: Reminder of your appointment tomorrow at 10:00 AM. Reply C to confirm, STOP to end",
    )

    @Test
    fun neverFiltersTransactionalNotices() {
        for (text in transactional) {
            val r = classifier.classify(text, sender = "287898")
            assertFalse("score=${r.score}, ${r.category} ${r.reasons}: $text", r.isSpam)
        }
    }

    private val commercial = listOf(
        "birddogs: Last Call on Sharehouse Shorts!\n\nGet 'em now, we aren't making more of these...\n\nhttps://birddogs.pscrpt.io/xxxx\n\n...until next summer.\n\np.s. sorry about the incorrect contact card in our last text. Save this new one, I pinky promise it works.",
        "CVS ExtraCare: School's back! Be prepared with \$3 off your \$10 pain relief, allergy or immunity support purchase. Tap link to send to card: i.cvs.com/xxxx",
        "Flash sale! 30% off everything today only. Reply STOP to opt out",
        "FLASH SALE TODAY: 30% off everything. Shop shop.example.com/l/abc123 Reply STOP to opt out",
    )

    private val phishing = listOf(
        "USPS: Your package could not be delivered due to an incomplete address. Update here: usps-redeliver.top/xx",
        "Toll Services: You have an unpaid toll balance of \$6.99. Pay within 24 hours to avoid late fees: ezdrive-pay.xyz",
        "Your Apple ID has been locked due to unusual sign-in activity. Verify your account: apple-id-check.icu",
        "Congratulations! You have won a \$500 gift card. Claim your reward: bit.ly/xyz",
    )

    @Test
    fun flagsMorePoliticalMessages() {
        for (text in morePolitical) {
            val r = classifier.classify(text, sender = "+12065550100")
            assertEquals("score=${r.score}, ${r.reasons}: $text", Classifier.Category.POLITICAL, r.category)
        }
    }

    @Test
    fun flagsCommercialMessages() {
        for (text in commercial) {
            val r = classifier.classify(text, sender = "+12065550100")
            assertEquals("score=${r.score}, ${r.reasons}: $text", Classifier.Category.COMMERCIAL, r.category)
        }
    }

    @Test
    fun flagsPhishing() {
        for (text in phishing) {
            val r = classifier.classify(text, sender = "+12065550100")
            assertEquals("score=${r.score}, ${r.reasons}: $text", Classifier.Category.PHISHING, r.category)
        }
    }

    @Test
    fun disabledCategoriesAreNotFiltered() {
        val politicalOnly = Classifier(enabled = setOf(Classifier.Category.POLITICAL))
        assertFalse(politicalOnly.classify(commercial[1]).isSpam)
        assertTrue(politicalOnly.classify(political[0]).isSpam)
    }

    @Test
    fun taintOnlyAppliesToPolitical() {
        val c = Classifier(taintedSenders = listOf("5042944686"))
        val r = c.classify("Your prescription is ready for pickup.", sender = "+15042944686")
        assertEquals(Classifier.Category.POLITICAL, r.category)
        val noPolitical = Classifier(taintedSenders = listOf("5042944686"),
            enabled = setOf(Classifier.Category.COMMERCIAL, Classifier.Category.PHISHING))
        assertFalse(noPolitical.classify("Your prescription is ready for pickup.", sender = "+15042944686").isSpam)
    }

    @Test
    fun flagsPoliticalMessages() {
        for (text in political) {
            val result = classifier.classify(text)
            assertTrue("Expected political (score=${result.score}, ${result.reasons}): $text", result.isSpam)
        }
    }

    @Test
    fun flagsRealInboxPreviews() {
        for (text in realInboxPreviews) {
            val result = classifier.classify(text, sender = "(302) 464-8095")
            assertTrue("Expected political (score=${result.score}, ${result.reasons}): $text", result.isSpam)
        }
    }

    @Test
    fun leavesNormalMessagesAlone() {
        for (text in normal) {
            val result = classifier.classify(text)
            assertFalse("Expected normal (score=${result.score}, ${result.reasons}): $text", result.isSpam)
        }
    }

    @Test
    fun verificationCodesAreNeverFlagged() {
        val text = "Paid for by Smith for Congress. Your verification code is 123456. Donate at actblue.com"
        assertFalse(classifier.classify(text).isSpam)
    }

    @Test
    fun customKeywordFlagsOnItsOwn() {
        val custom = Classifier(customKeywords = listOf("Jane Doe"))
        val result = custom.classify("A message from Jane Doe about the town meeting")
        assertTrue(result.isSpam)
        assertTrue(result.reasons.contains("Custom keyword: Jane Doe"))
        assertFalse(custom.classify("A message from Jane Doer").isSpam)
    }

    @Test
    fun fingerprintChangesOnlyWhenFilteringChanges() {
        val base = Classifier.fingerprint(listOf("Jane Doe"), listOf("+1 (555) 123-4567"))
        assertEquals(base, Classifier.fingerprint(listOf(" jane doe "), listOf("5551234567")))
        assertFalse(base == Classifier.fingerprint(listOf("Jane Doe", "Bob"), listOf("5551234567")))
        assertFalse(base == Classifier.fingerprint(listOf("Jane Doe"), emptyList()))
        assertFalse(base == Classifier.fingerprint(listOf("Jane Doe"), listOf("5551234567"), setOf(Classifier.Category.POLITICAL)))
    }

    @Test
    fun everythingFromATaintedSenderIsFlagged() {
        val c = Classifier(taintedSenders = listOf("+15042944686"))
        val result = c.classify("Thanks for being with us this year!", sender = "(504) 294-4686")
        assertTrue(result.isSpam)
        assertTrue(result.reasons.contains(Classifier.TAINTED_REASON))
        assertFalse(c.classify("Thanks for being with us this year!", sender = "(504) 294-0000").isSpam)
    }

    @Test
    fun taintedSenderStillCannotHideVerificationCodes() {
        val c = Classifier(taintedSenders = listOf("90999"))
        assertFalse(c.classify("Your verification code is 482913", sender = "90999").isSpam)
    }

    @Test
    fun allowListBeatsTaint() {
        val c = Classifier(allowedSenders = listOf("5042944686"), taintedSenders = listOf("5042944686"))
        assertFalse(c.classify("Chip in \$5 at actblue.com", sender = "+15042944686").isSpam)
    }

    @Test
    fun allowListedSenderIsNeverFlagged() {
        val custom = Classifier(allowedSenders = listOf("+1 (555) 123-4567"))
        val result = custom.classify(political[0], sender = "5551234567")
        assertFalse(result.isSpam)
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
