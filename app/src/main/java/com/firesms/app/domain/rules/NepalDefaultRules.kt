package com.firesms.app.domain.rules

import com.firesms.app.data.local.entity.ParserRule

/**
 * Starter rules for Nepal: bank SMS alerts and eSewa/Khalti app notifications.
 *
 * Banks and wallets word their messages differently and change them over time, so these rules
 * look for keywords and the first NPR/Rs amount instead of one exact sentence. They are added
 * switched off: the user picks their own Firefly account, checks a real message with the rule
 * tester, and then switches the rule on.
 *
 * App notifications reach the parser with the app's package name as the sender and
 * "title\ntext" as the body (see [com.firesms.app.service.PaymentNotificationListener]).
 */
object NepalDefaultRules {

    const val ESEWA_PACKAGE = "com.f1soft.esewa"
    const val KHALTI_PACKAGE = "com.khalti"

    /** Default rules sit after the user's own rules, which normally use priority 10. */
    private const val PRIORITY = 50

    /** "NPR 1,234.50", "Rs. 1,234.50", "Rs 1234", "रु 1,234". */
    private const val AMOUNT = """(?:NPR|Rs\.?|रु\.?)\s*(?<amount>\d[\d,]*(?:\.\d{1,2})?)"""

    /** Failed, reversed or OTP messages are never transactions. */
    private const val NOT_A_TRANSACTION =
        """(?!.*\b(?:failed|unsuccessful|declined|reversed|reversal|cancell?ed|OTP)\b)"""

    /** "Remarks: ATM WDL", "Rmks- fund trf", "Narration: ..." up to the end of that sentence. */
    private const val REMARKS =
        """(?:.*?\b(?:remarks?|rmks?|narration|purpose)\b\s*[:\-]?\s*(?<remarks>[^\n]{1,80}?)\s*(?:[.;]\s|[.;]?\s*$|\n))?"""

    /**
     * Sender IDs used by Nepali banks for SMS alerts. Matched anywhere in the sender and ignoring
     * case, so "NABIL_ALERT" and "NICASIA" both match. Users can narrow this to their own bank.
     */
    const val BANK_SENDERS =
        "(?i)(NABIL|NICASIA|NIC.?ASIA|GIBL|GLOBAL.?IME|NMB|SIDDHARTHA|SBL|HIMALAYAN|HBL|KUMARI|KBL|" +
            "EVEREST|EBL|PRABHU|PRVU|SANIMA|MBL|MACHHAPUCHCHHRE|CITIZENS|CZBIL|LAXMI|LSBL|SCBNL|" +
            "STANDARD.?CHARTERED|NEPAL.?BANK|NBL|RBB|RASTRIYA|ADBL|NIMB|NSBI|PRIME|PCBL|" +
            "SUNRISE|MEGA|CIVIL|CENTURY|BANK)"

    private fun keywords(vararg words: String) = """(?=.*\b(?:${words.joinToString("|")})\b)"""

    private fun body(keywordLookahead: String, tail: String) =
        "(?i)^$NOT_A_TRANSACTION$keywordLookahead.*?$AMOUNT$tail"

    /** Where a name ends: punctuation, the end of a line, or words such as "successfully" or "on". */
    private const val PARTY_END =
        """\s*(?:[.(,]|\n|$|\b(?:successful(?:ly)?|on|via|has|is|was|with|through|using|ref)\b)"""

    /** "... from Ram Bahadur (98XXXXXXXX)" → "Ram Bahadur". */
    private const val FROM_PARTY = """(?:.*?\bfrom\s+(?<party>[^\n.(,]{2,60}?)$PARTY_END)?"""

    /** "... to Nepal Telecom." → "Nepal Telecom". */
    private const val TO_PARTY = """(?:.*?\b(?:to|for|at)\s+(?<party>[^\n.(,]{2,60}?)$PARTY_END)?"""

    private val WALLET_RECEIVED = body(keywords("received", "credited", "loaded", "added"), FROM_PARTY)
    private val WALLET_SENT = body(
        keywords("paid", "payment", "sent", "transferred", "debited", "purchased?", "recharged?", "topped\\s+up"),
        TO_PARTY
    )

    private fun rule(
        id: String,
        name: String,
        senderPattern: String,
        bodyPattern: String,
        type: String,
        description: String,
        ownAccount: String? = null,
        priority: Int = PRIORITY
    ) = ParserRule(
        id = id,
        name = name,
        enabled = false,
        senderPattern = senderPattern,
        bodyPattern = bodyPattern,
        amountTemplate = "{{amount}}",
        descriptionSource = "template",
        descriptionValue = description,
        sourceAccountName = ownAccount,
        type = type,
        priority = priority
    )

    val rules: List<ParserRule> = listOf(
        rule(
            id = "np-bank-sms-debit",
            name = "Bank SMS debit",
            senderPattern = BANK_SENDERS,
            bodyPattern = body(keywords("debited", "withdrawn", "deducted"), REMARKS),
            type = "withdrawal",
            description = "{{remarks}}"
        ),
        rule(
            id = "np-bank-sms-credit",
            name = "Bank SMS credit",
            senderPattern = BANK_SENDERS,
            bodyPattern = body(keywords("credited", "deposited"), REMARKS),
            type = "deposit",
            description = "{{remarks}}",
            priority = PRIORITY + 1
        ),
        // "Received" is checked before "sent" so "payment received" counts as money in.
        rule(
            id = "np-esewa-received",
            name = "eSewa received",
            senderPattern = "^" + SenderPatterns.escape(ESEWA_PACKAGE) + "$",
            bodyPattern = WALLET_RECEIVED,
            type = "deposit",
            description = "{{party}}",
            ownAccount = "eSewa"
        ),
        rule(
            id = "np-esewa-sent",
            name = "eSewa payment",
            senderPattern = "^" + SenderPatterns.escape(ESEWA_PACKAGE) + "$",
            bodyPattern = WALLET_SENT,
            type = "withdrawal",
            description = "{{party}}",
            ownAccount = "eSewa",
            priority = PRIORITY + 1
        ),
        rule(
            id = "np-khalti-received",
            name = "Khalti received",
            senderPattern = "^" + SenderPatterns.escape(KHALTI_PACKAGE) + "$",
            bodyPattern = WALLET_RECEIVED,
            type = "deposit",
            description = "{{party}}",
            ownAccount = "Khalti"
        ),
        rule(
            id = "np-khalti-sent",
            name = "Khalti payment",
            senderPattern = "^" + SenderPatterns.escape(KHALTI_PACKAGE) + "$",
            bodyPattern = WALLET_SENT,
            type = "withdrawal",
            description = "{{party}}",
            ownAccount = "Khalti",
            priority = PRIORITY + 1
        )
    )

    val ids: Set<String> = rules.mapTo(mutableSetOf()) { it.id }
}
