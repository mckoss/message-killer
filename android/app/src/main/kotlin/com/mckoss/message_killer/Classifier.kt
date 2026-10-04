package com.mckoss.message_killer

/**
 * Rule-based, on-device spam classifier with three categories: political ads and
 * donation requests, commercial marketing, and phishing / scams.
 *
 * Each rule belongs to one or more categories and contributes its weight at most
 * once. A message is filtered when some enabled category's score reaches
 * [THRESHOLD]; the highest-scoring category wins (ties: political, phishing,
 * commercial). Plain JVM code (no Android imports) so it can be unit-tested
 * off-device.
 */
class Classifier(
    customKeywords: Collection<String> = emptyList(),
    allowedSenders: Collection<String> = emptyList(),
    /** Senders that already sent political texts; everything else from them is flagged too. */
    taintedSenders: Collection<String> = emptyList(),
    private val enabled: Set<Category> = Category.entries.toSet(),
) {
    enum class Category(val key: String, val label: String) {
        POLITICAL("political", "Political"),
        PHISHING("phishing", "Phishing / scam"),
        COMMERCIAL("commercial", "Commercial"),
        ;

        companion object {
            fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
        }
    }

    data class Rule(val label: String, val weight: Double, val pattern: Regex, val categories: Set<Category>)

    /**
     * [category] is null when the message isn't filtered. [score] and [reasons]
     * describe the winning category (or the strongest one, if none passed).
     */
    data class Result(
        val score: Double,
        val reasons: List<String>,
        val category: Category?,
        internal val scores: Map<Category, Double> = emptyMap(),
        internal val reasonsBy: Map<Category, List<String>> = emptyMap(),
    ) {
        val isSpam: Boolean get() = category != null
    }

    private val customRules = customKeywords
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { Rule("Custom keyword: $it", THRESHOLD, phrase(Regex.escape(it)), ALL) }

    private val allowed = allowedSenders.map(::normalizeSender).filter { it.isNotEmpty() }.toSet()
    private val tainted = taintedSenders.map(::normalizeSender).filter { it.isNotEmpty() }.toSet()

    fun classify(body: String, sender: String? = null): Result {
        if (sender != null && normalizeSender(sender) in allowed) {
            return Result(0.0, listOf("Sender is on your allow list"), null)
        }
        val scores = HashMap<Category, Double>()
        val reasons = HashMap<Category, MutableList<String>>()
        fun add(label: String, weight: Double, categories: Set<Category>) {
            for (c in categories) {
                scores[c] = (scores[c] ?: 0.0) + weight
                reasons.getOrPut(c) { mutableListOf() } += label
            }
        }
        for (rule in BUILT_IN_RULES) if (rule.pattern.containsMatchIn(body)) add(rule.label, rule.weight, rule.categories)
        for (rule in customRules) if (rule.pattern.containsMatchIn(body)) add(rule.label, rule.weight, rule.categories)
        if (sender != null && SHORT_CODE.matches(sender.trim())) {
            add("Sent from a short code", 0.5, setOf(Category.POLITICAL, Category.COMMERCIAL))
        }
        return withTaint(decide(scores, reasons), sender, tainted)
    }

    /**
     * Adds the "already sent political texts" weight to the political score when
     * [sender] is in [taintedNormalized]. Verification codes keep their large
     * negative score, so they stay visible.
     */
    fun withTaint(result: Result, sender: String?, taintedNormalized: Set<String>): Result {
        if (sender == null || Category.POLITICAL !in enabled) return result
        if (normalizeSender(sender) !in taintedNormalized) return result
        val political = result.reasonsBy[Category.POLITICAL].orEmpty()
        if (TAINTED_REASON in political) return result
        val scores = result.scores.toMutableMap()
        scores[Category.POLITICAL] = (scores[Category.POLITICAL] ?: 0.0) + THRESHOLD
        val reasons = result.reasonsBy.toMutableMap()
        reasons[Category.POLITICAL] = political + TAINTED_REASON
        return decide(scores, reasons)
    }

    private fun decide(scores: Map<Category, Double>, reasons: Map<Category, List<String>>): Result {
        // Enum order breaks ties: political, phishing, commercial.
        val best = Category.entries.filter { it in enabled }.maxByOrNull { scores[it] ?: 0.0 }
        val score = best?.let { scores[it] } ?: 0.0
        val winner = best?.takeIf { score >= THRESHOLD }
        return Result(score, best?.let { reasons[it] }.orEmpty(), winner, scores, reasons)
    }

    companion object {
        const val THRESHOLD = 3.0
        const val TAINTED_REASON = "Sender previously sent political texts"

        /**
         * Bump when scan logic changes in ways the rule list doesn't capture
         * (e.g. which messages are read, how senders are matched).
         */
        const val SCAN_LOGIC_VERSION = 4

        private val P = setOf(Category.POLITICAL)
        private val C = setOf(Category.COMMERCIAL)
        private val X = setOf(Category.PHISHING)
        private val ALL = Category.entries.toSet()

        /**
         * Identifies everything that decides what gets flagged. When it differs
         * from the last scan's, the next scan re-checks every message instead of
         * just recent ones. Built-in rules are hashed automatically, so editing
         * them forces a full rescan without a manual version bump.
         */
        fun fingerprint(
            customKeywords: Collection<String>,
            allowedSenders: Collection<String>,
            enabled: Set<Category> = Category.entries.toSet(),
        ): String {
            val parts = buildList {
                add("logic=$SCAN_LOGIC_VERSION")
                add("threshold=$THRESHOLD")
                add("categories=" + enabled.map { it.key }.sorted().joinToString(","))
                BUILT_IN_RULES.forEach {
                    add("${it.label}|${it.weight}|${it.pattern.pattern}|${it.pattern.options}|${it.categories.map { c -> c.key }.sorted()}")
                }
                customKeywords.map { it.trim().lowercase() }.sorted().forEach { add("kw=$it") }
                allowedSenders.map(::normalizeSender).sorted().forEach { add("allow=$it") }
            }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("\n").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }.take(16)
        }

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

        private const val FOOTER =
            """(reply|replying|txt|text|texting)\s+stop|opt[-\s]?out\s+at\s+any\s+time|stop\s*(2|to)\s*(end|quit|stop|opt[-\s]?out|unsub\w*)|stop2end|stop=end|\bend2end\b"""

        val BUILT_IN_RULES: List<Rule> = listOf(
            // ---------------------------------------------------------- political
            // Fundraising platforms – nearly conclusive on their own.
            Rule("Fundraising platform (ActBlue/WinRed/Anedot)", 3.0,
                phrase("""act\s?blue|win\s?red|anedot|secure\.actblue\.com|secure\.winred\.com"""), P),

            // Donation asks.
            Rule("Asks for a donation", 2.0,
                phrase("""donate|donation|chip\s+in|pitch\s+in|contribute|contribution|rush\s+\$\d+|give\s+\$\d+|hail\s+mary\s+(request|ask|appeal)"""), P),
            Rule("Dollar amount ask (\$X now/today)", 1.0,
                Regex("""\$\s?\d{1,4}(\.\d\d)?\s+(or\s+more\s+)?(now|today|right\s+now|immediately|before)""", RegexOption.IGNORE_CASE), P),
            Rule("Donation match / multiplier", 1.5,
                phrase("""(double|triple|quadruple|\d{3,4}%|\d+x)[-\s]?match(ed|ing)?|match(ed|ing)?\s+(every\s+|each\s+|your\s+)?(gift|donation|dollar)s?"""), P),
            Rule("Fundraising deadline", 2.0,
                phrase("""end[-\s]of[-\s]quarter|(midnight|fec|end[-\s]of[-\s](month|year)|fundraising|legally\s+required|filing)\s+deadline|deadline\s+(tonight|is\s+midnight)"""), P),

            // Political terms.
            Rule("\"Paid for by\" disclaimer", 2.0, phrase("""paid\s+for\s+by"""), P),
            Rule("PAC / committee", 1.5,
                phrase("""(super\s+)?pac|dnc|rnc|dccc|nrcc|dscc|nrsc|dga|rga|political\s+action\s+committee"""), P),
            Rule("Party / partisan terms", 1.0,
                phrase("""democrats?|democratic\s+party|republicans?|gop|maga|liberals?|conservatives?|progressives?"""), P),
            Rule("Election terms", 1.0,
                phrase("""elections?|vote|voting|voters?|midterms?|campaign|(house|senate)\s+(control|majority)|control\s+(of\s+)?the\s+(house|senate|congress)"""), P),
            Rule("Voting logistics / get-out-the-vote", 1.0,
                phrase("""ballots?|(in|leading|trailing)\s+the\s+polls|down[-\s]ballot|(close|tight|key|unsexy|competitive)\s+races?|flip\s+(the|a|this)\s+(house|senate|seat|district)|flip\s+(texas|florida|georgia|arizona|nevada|north\s+carolina|pennsylvania|michigan|wisconsin|ohio|iowa|maine|alaska|montana|kansas|nebraska|virginia|new\s+hampshire|minnesota|colorado|new\s+mexico|california|new\s+york|mississippi|south\s+carolina|missouri|indiana)|polling\s+(place|location)s?|early\s+voting|election\s+day|primary\s+election|swing\s+states?|get\s+out\s+the\s+vote|register(ed)?\s+to\s+vote"""), P),
            Rule("Political office / figure", 1.0,
                phrase("""congress(wo)?man|congress|presidential|senate|senator|governor|speaker\s+(of\s+the\s+house)?|white\s+house|president\s+\w+|trump|biden|harris|vance|obama|pelosi|schumer|mcconnell|jeffries|aoc|desantis|newsom|pritzker|klobuchar|sherrod\s+brown|warren|sanders|kennedy|rfk|fauci|commissioner|attorney\s+general|secretary\s+of\s+(state|the\s+\w+)|mayor|state\s+(rep|representative|senator)|candidate"""), P),
            Rule("Political survey / poll bait", 1.0,
                Regex("""(?i:(official|national|presidential|patriot)\s+(survey|poll)|take\s+the\s+(survey|poll)|your\s+(response|vote)\s+is\s+(needed|required)|membership\s+(has\s+)?expired|you('ve|\s+have)\s+been\s+selected|do\s+you\s+(support|agree|stand\s+with|approve))|\b(Y\s*/\s*N|YES\s*/\s*NO)\b"""), P),
            // "We're polling WA residents. Can you answer a 4-question poll?"
            Rule("Polling request", 2.0,
                phrase("""(\d|two|three|four|five|six|short|quick)[-\s]question\s+(poll|survey)|polling\s+(\w+\s+)?(residents|voters|households)|quit\s+survey"""), P),

            // Urgent money pressure without the word "donate": "last-ditch request",
            // "falling short tonight", "budget cuts", "give in the next hour".
            Rule("Fundraising urgency", 2.0,
                phrase("""last[-\s]ditch|fall(ing)?\s+short|budget\s+cuts?|(give|donate|chip\s+in|pitch\s+in)\s+(in|within)\s+the\s+next\s+(hour|\d+\s+(hours|minutes))|final\s+ad\s+buy|ad\s+buy|missed\s+our\s+goal|(hit|meet|reach)\s+our\s+(goal|target)\s+(tonight|by\s+midnight)"""), P),
            // Issue-advocacy asks: "help to defend Social Security", "sign the petition".
            Rule("Advocacy ask", 1.5,
                phrase("""(help|join)\s+(us|me)?\s*(to\s+)?(defend|protect|fight\s+for|save|stop)|stand\s+with\s+(us|me)|sign\s+(the|our|this|my)\s+(petition|pledge)|add\s+your\s+name|tell\s+congress"""), P),
            Rule("Political issue", 1.0,
                phrase("""social\s+security|medicare|medicaid|abortion|reproductive\s+(rights|freedom)|gun\s+(safety|control|rights)|second\s+amendment|supreme\s+court|scotus|impeach\w*|filibuster|democracy"""), P),
            // Signed by a person at the end of the first line: "My last-ditch request. - James Talarico"
            Rule("Signed by a person", 1.0,
                Regex("""^[^\n]{0,160}\s[-–—]\s?\p{Lu}[\p{L}'’]+(\s+\p{Lu}[\p{L}'’.]+){1,2}\s*(\n|$)"""), P),

            // Opinion-survey solicitations: "We are conducting a study on local issues…
            // your response is voluntary and confidential… receive studies in the future?"
            Rule("Survey / opinion-poll solicitation", 2.0,
                phrase("""conducting\s+(a|an|our)\s+(\w+\s+)?(study|survey|poll)|research[-\s]polls?|(your\s+)?(response|answers?|participation)\s+(is|are)\s+(voluntary|anonymous|confidential)|kept\s+(strictly\s+)?confidential|receive\s+(future\s+)?(studies|surveys|polls)|(studies|surveys|polls)\s+in\s+the\s+future|(local|community)\s+issues|what\s+you\s+would\s+like\s+to\s+see\s+changed"""), P),

            // Peer-to-peer texting platforms use tracking links like "site.org/l/uEel5Z".
            Rule("Campaign-style tracking link", 0.5,
                Regex("""\b[\w-]+\.(org|com|us|io|co|net)/l/\w{5,}""", RegexOption.IGNORE_CASE), P),

            // Peer-to-peer texts open with the sender introducing themselves:
            // "It's Sherrod Brown." / "Amy Klobuchar here." / "Hi, I am Mia from US Speaks."
            // Weak alone (friends do this too).
            Rule("Opens with a personal introduction", 1.0,
                Regex("""^\W*((?i:hi|hey|hello)[,!]?\s+)?(?i:it'?s|it’s|this\s+is|i'?m|i’m|i\s+am)\s+\p{Lu}[\p{L}'’-]+(\s+\p{Lu}[\p{L}'’-]+){0,2}\s*[.!,]|^\W*\p{Lu}[\p{L}'’-]+(\s+\p{Lu}[\p{L}'’-]+){1,2}\s+here\b|^\W*((?i:hi|hey|hello)[,!]?\s+)?(?i:i\s+am|i'm|i’m|my\s+name\s+is|this\s+is)\s+\p{Lu}[\p{L}'’-]+(\s+\p{Lu}[\p{L}'’-]+)?\s*,?\s+(?i:from|with)\b"""), P),

            // "BREAKING:", "UPDATE:", "BOYCOTT:" – alarmist all-caps openers (case-sensitive),
            // or an opening line of 3+ all-caps words ("NOBEL PEACE PRIZE ANNOUNCEMENT").
            Rule("Alarmist all-caps opener", 1.0,
                Regex("""^\W*((BREAKING|UPDATE|URGENT|ALERT|EMERGENCY|FINAL NOTICE|HUGE NEWS|DEVASTATING|IT'S OFFICIAL)\b|\p{Lu}{4,}:|(\p{Lu}{2,}[\p{Lu}'’!-]*\s+){2,}\p{Lu}{2,})"""), P),

            // Committee acronym signed just before the opt-out footer: "HDV\nStop2End", "-STFU PAC Text STOP".
            Rule("Committee sign-off before opt-out", 1.0,
                Regex("""(^|\n|\s{2,}|-)\s*\p{Lu}{2,6}(\s+PAC)?\s*\n?\s*(?i:$FOOTER)"""), P),

            // --------------------------------------------------------- commercial
            // "CVS ExtraCare: …", "birddogs: …"
            // Weak: banks and pharmacies prefix service notices the same way, so a
            // promotion also needs real marketing language.
            Rule("Brand-name prefix", 0.5, Regex("""^\s*[\p{L}\p{N}][\p{L}\p{N}&'’. -]{1,30}:\s"""), C),
            Rule("Discount or sale offer", 2.0,
                phrase("""\d{1,2}%\s+off|\$\d+(\.\d\d)?\s+off|bogo|buy\s+one|free\s+shipping|promo\s+code|coupons?|use\s+code\s+\w+|(flash|clearance|semi-annual|black\s+friday|cyber\s+monday|end\s+of\s+season)\s+sale|on\s+sale|send\s+to\s+card"""), C),
            Rule("Shopping urgency", 1.0,
                Regex("""(?i)\b(last\s+call|shop\s+now|get\s+'?em|limited\s+time|while\s+supplies\s+last|ends\s+(tonight|today|soon|sunday|midnight)|today\s+only|don'?t\s+miss|new\s+arrivals|back\s+in\s+stock|restock(ed)?|selling\s+(out|fast)|aren'?t\s+making\s+more)"""), C),
            // SMS-marketing platforms (Postscript, Attentive, Klaviyo): used only for promotions.
            Rule("Marketing-platform link", 2.0,
                Regex("""\b(pscrpt\.io|attn\.tv|klclick\d?\.com|kmail-lists\.com|txt\.so|i\.cvs\.com)\b""", RegexOption.IGNORE_CASE), C),
            Rule("New-product pitch", 1.0,
                phrase("""brand\s+new|new\s+(arrivals?|drops?|collection|styles?|colou?rs?|flavors?|releases?)|just\s+(dropped|landed|arrived|launched)|(fall|spring|summer|winter|holiday)\s+(collection|lineup|line|edit)|for\s+(fall|spring|summer|winter)"""), C),
            Rule("Asks you to save a contact card", 1.0,
                phrase("""save\s+(this|our)\s+(new\s+)?(contact|number)|contact\s+card"""), C),

            // Transactional notices (e.g. pharmacy pickups) are never treated as
            // promotions or political, even from a flagged sender.
            Rule("Looks like a transactional notice (prescription, order, appointment)", -4.0,
                phrase("""prescriptions?|rx|refills?|ready\s+for\s+pick\s*-?\s*up|pharmacy\s+(order|hours)|your\s+(\w+\s+){0,2}appointment|appointment\s+(reminder|confirmed|confirmation|is\s+(on|at|scheduled|tomorrow|today)|tomorrow|today)|reminder\s+of\s+your\s+appointment|order\s+(#|number|has\s+shipped|is\s+on\s+its\s+way)|has\s+shipped|out\s+for\s+delivery|confirmation\s+(number|code)"""), P + C),

            // ----------------------------------------------------- phishing / scams
            Rule("Account problem bait", 2.0,
                phrase("""(account|card|apple\s*id|payment|subscription)\s+(has\s+been|is|was|will\s+be)\s+(suspended|locked|disabled|restricted|on\s+hold|deactivated|compromised)|unusual\s+(sign[-\s]?in|activity|login)|verify\s+your\s+(account|identity|information)|update\s+your\s+(payment|billing)\s+(info|information|details|method)"""), X),
            Rule("Toll or delivery scam", 2.0,
                phrase("""(unpaid|outstanding|overdue)\s+(toll|balance|fee)s?|toll\s+(balance|payment|violation)|e-?z\s?pass|(package|parcel|shipment)\s+(could\s+not|cannot|can'?t|was\s+unable\s+to|is\s+on\s+hold)|re-?deliver(y)?|address\s+(is\s+)?(incomplete|invalid|incorrect)"""), X),
            Rule("Prize or reward bait", 2.0,
                phrase("""congratulations[!,]?\s+(you|you've|you\s+have)|you\s+(have\s+)?won|gift\s+card\s+(reward|winner)"""), X),
            Rule("Asks you to claim something", 1.5,
                phrase("""claim\s+(your|the|this)\s+(prize|reward|gift|refund|package|bonus)"""), X),
            Rule("Job or money scam", 2.0,
                phrase("""\$\d{2,4}\s*(per|a|/)\s*(day|hour|hr)|work\s+from\s+home|investment\s+opportunity|guaranteed\s+(return|profit)s?|crypto(currency)?\s+(investment|trading|opportunity)"""), X),
            Rule("Pressure to act now", 1.0,
                phrase("""within\s+(12|24|48|72)\s+hours|to\s+avoid\s+(suspension|penalt(y|ies)|late\s+fees?|additional\s+fees?|legal\s+action)|final\s+notice"""), X),
            Rule("Suspicious link domain", 1.5,
                Regex("""\b[\w-]+\.(top|xyz|icu|vip|click|cfd|sbs|cyou|buzz|rest)(/|\b)""", RegexOption.IGNORE_CASE), X),
            Rule("Wrong-number opener", 1.0,
                Regex("""^\W*(?i:hi|hello|hey)[,!]?\s+(?i:is\s+this|are\s+you)\s+\p{Lu}"""), X),

            // ------------------------------------------------------------- shared
            Rule("Link shortener", 0.5,
                Regex("""\b(bit\.ly|tinyurl\.com|rb\.gy|t\.co|ow\.ly|wnrd\.us|is\.gd|cutt\.ly)/""", RegexOption.IGNORE_CASE), ALL),
            // Mass-texting opt-out footer ("Stop2End"). A strong sign of bulk texts, but
            // banks and pharmacies use it too, so it can't decide alone; the safety
            // rules below cover those.
            Rule("Bulk-text opt-out footer", 1.5, Regex(FOOTER, RegexOption.IGNORE_CASE), P + C),

            // Safety valve: bank / card alerts are never political or promotional, even
            // when the merchant is ActBlue ("Credit card charge $515.00 … ACTBLUE").
            // Phishing is left alone: scams imitate these.
            Rule("Looks like a bank or card alert (never hidden)", -10.0,
                phrase("""(credit|debit)\s+card\s+(charge|purchase|transaction|payment)|card\s+(ending|-)\s*(in\s+)?\d{4}|account\s+ending\s+(in\s+)?\d{4}|(charge|purchase|transaction|withdrawal|deposit)\s+(of|for)\s+\$\d|\(declined\)|end\s+account\s+texts"""), P + C),

            // Safety valve: receipts for your own donations ("Thank you for your
            // contribution of $25 … receipt") aren't fundraising.
            Rule("Looks like a donation receipt (never hidden)", -10.0,
                phrase("""(contribution|donation)\s+receipt|receipt\s+for\s+your\s+(contribution|donation)|your\s+(contribution|donation)\s+of\s+\$\d[\d,.]*\s+(to|for)\s+.{1,60}(was|has\s+been)\s+(received|processed|successful)"""), P + C),

            // Safety valve: never hide one-time codes or verification messages.
            Rule("Looks like a verification code (never hidden)", -10.0,
                Regex("""(verification|security|login|one[-\s]time|access)\s+code|code\s+(is|:)\s*\d{4,8}|\b\d{4,8}\s+is\s+your\b|\botp\b""", RegexOption.IGNORE_CASE), ALL),
        )
    }
}
