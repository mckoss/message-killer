package com.mckoss.message_killer

/**
 * Rule-based, on-device classifier for political ads and donation requests.
 *
 * Each rule contributes its weight at most once; a message is flagged when the
 * total reaches [THRESHOLD]. Plain JVM code (no Android imports) so it can be
 * unit-tested off-device.
 */
class Classifier(
    customKeywords: Collection<String> = emptyList(),
    allowedSenders: Collection<String> = emptyList(),
) {
    data class Rule(val label: String, val weight: Double, val pattern: Regex)

    data class Result(val score: Double, val reasons: List<String>) {
        val isPolitical: Boolean get() = score >= THRESHOLD
    }

    private val customRules = customKeywords
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Rule("Custom keyword: $it", THRESHOLD, phrase(Regex.escape(it))) }

    private val allowed = allowedSenders.map(::normalizeSender).filter { it.isNotEmpty() }.toSet()

    fun classify(body: String, sender: String? = null): Result {
        if (sender != null && normalizeSender(sender) in allowed) {
            return Result(0.0, listOf("Sender is on your allow list"))
        }
        val reasons = mutableListOf<String>()
        var score = 0.0
        for (rule in BUILT_IN_RULES + customRules) {
            if (rule.pattern.containsMatchIn(body)) {
                score += rule.weight
                reasons += rule.label
            }
        }
        if (sender != null && SHORT_CODE.matches(sender.trim())) {
            score += 0.5
            reasons += "Sent from a short code"
        }
        return Result(score, reasons)
    }

    companion object {
        const val THRESHOLD = 3.0

        private val SHORT_CODE = Regex("""^\d{5,6}$""")

        private fun phrase(p: String) = Regex("""(?<![\p{L}\p{N}])(?:$p)(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)

        /** Lower-cases names and strips phone numbers down to their last 10 digits. */
        fun normalizeSender(sender: String): String {
            val digits = sender.filter { it.isDigit() }
            return if (digits.length >= 5 && digits.length * 2 >= sender.trim().length) {
                digits.takeLast(10)
            } else {
                sender.trim().lowercase()
            }
        }

        val BUILT_IN_RULES: List<Rule> = listOf(
            // Fundraising platforms – nearly conclusive on their own.
            Rule("Fundraising platform (ActBlue/WinRed/Anedot)", 3.0,
                phrase("""act\s?blue|win\s?red|anedot|secure\.actblue\.com|secure\.winred\.com""")),

            // Donation asks.
            Rule("Asks for a donation", 2.0,
                phrase("""donate|donation|chip\s+in|pitch\s+in|contribute|contribution|rush\s+\$\d+|give\s+\$\d+|hail\s+mary\s+(request|ask|appeal)""")),
            Rule("Dollar amount ask (\$X now/today)", 1.0,
                Regex("""\$\s?\d{1,4}(\.\d\d)?\s+(or\s+more\s+)?(now|today|right\s+now|immediately|before)""", RegexOption.IGNORE_CASE)),
            Rule("Donation match / multiplier", 1.5,
                phrase("""(double|triple|quadruple|\d{3,4}%|\d+x)[-\s]?match(ed|ing)?|match(ed|ing)?\s+(every\s+|each\s+|your\s+)?(gift|donation|dollar)s?""")),
            Rule("Fundraising deadline", 2.0,
                phrase("""end[-\s]of[-\s]quarter|(midnight|fec|end[-\s]of[-\s](month|year)|fundraising|legally\s+required|filing)\s+deadline|deadline\s+(tonight|is\s+midnight)""")),

            // Political terms.
            Rule("\"Paid for by\" disclaimer", 2.0, phrase("""paid\s+for\s+by""")),
            Rule("PAC / committee", 1.5,
                phrase("""(super\s+)?pac|dnc|rnc|dccc|nrcc|dscc|nrsc|dga|rga|political\s+action\s+committee""")),
            Rule("Party / partisan terms", 1.0,
                phrase("""democrats?|democratic\s+party|republicans?|gop|maga|liberals?|conservatives?|progressives?""")),
            Rule("Election terms", 1.0,
                phrase("""elections?|vote|voting|voters?|midterms?|campaign""")),
            Rule("Voting logistics / get-out-the-vote", 1.0,
                phrase("""ballots?|(in|leading|trailing)\s+the\s+polls|down[-\s]ballot|(close|tight|key|unsexy|competitive)\s+races?|flip\s+(the|a|this)\s+(house|senate|seat|district)|polling\s+(place|location)s?|early\s+voting|election\s+day|primary\s+election|swing\s+states?|get\s+out\s+the\s+vote|register(ed)?\s+to\s+vote""")),
            Rule("Political office / figure", 1.0,
                phrase("""congress(wo)?man|congress|presidential|senate|senator|governor|speaker\s+(of\s+the\s+house)?|white\s+house|president\s+\w+|trump|biden|harris|vance|obama|pelosi|schumer|mcconnell|jeffries|aoc|desantis|newsom|pritzker|klobuchar|sherrod\s+brown|warren|sanders|kennedy|rfk|fauci""")),
            Rule("Political survey / poll bait", 1.0,
                phrase("""(official|national|presidential|patriot)\s+(survey|poll)|take\s+the\s+(survey|poll)|your\s+(response|vote)\s+is\s+(needed|required)|membership\s+(has\s+)?expired|you('ve|\s+have)\s+been\s+selected""")),

            // Peer-to-peer texting platforms use tracking links like "site.org/l/uEel5Z".
            Rule("Campaign-style tracking link", 0.5,
                Regex("""\b[\w-]+\.(org|com|us|io|co|net)/l/\w{5,}""", RegexOption.IGNORE_CASE)),
            Rule("Link shortener", 0.5,
                Regex("""\b(bit\.ly|tinyurl\.com|rb\.gy|t\.co|ow\.ly|wnrd\.us|is\.gd|cutt\.ly)/""", RegexOption.IGNORE_CASE)),

            // Peer-to-peer fundraising texts open with the politician introducing themselves:
            // "It's Sherrod Brown." / "Amy Klobuchar here." Weak alone (friends do this too).
            Rule("Opens with a personal introduction", 1.0,
                Regex("""^\W*((?i:hi|hey|hello)[,!]?\s+)?(?i:it'?s|it’s|this\s+is)\s+\p{Lu}[\p{L}'’-]+(\s+\p{Lu}[\p{L}'’-]+){0,2}\s*[.!,]|^\W*\p{Lu}[\p{L}'’-]+(\s+\p{Lu}[\p{L}'’-]+){1,2}\s+here\b""")),

            // "BREAKING:", "UPDATE:", "URGENT:" – alarmist all-caps openers (case-sensitive).
            // Also any opening line of 3+ all-caps words ("NOBEL PEACE PRIZE ANNOUNCEMENT").
            Rule("Alarmist all-caps opener", 1.0,
                Regex("""^\W*((BREAKING|UPDATE|URGENT|ALERT|EMERGENCY|FINAL NOTICE|HUGE NEWS|DEVASTATING|IT'S OFFICIAL)\b|(\p{Lu}{2,}[\p{Lu}'’!-]*\s+){2,}\p{Lu}{2,})""")),

            // Bulk-marketing opt-out footer – weak on its own.
            Rule("Bulk-text opt-out footer", 1.0,
                Regex("""(reply|txt|text)\s+stop|stop\s*(2|to)\s*(end|quit|stop|opt[-\s]?out|unsub\w*)|stop2end|stop=end|\bend2end\b""", RegexOption.IGNORE_CASE)),

            // Safety valve: never hide one-time codes or verification messages.
            Rule("Looks like a verification code (never hidden)", -10.0,
                Regex("""(verification|security|login|one[-\s]time|access)\s+code|code\s+(is|:)\s*\d{4,8}|\b\d{4,8}\s+is\s+your\b|\botp\b""", RegexOption.IGNORE_CASE)),
        )
    }
}
