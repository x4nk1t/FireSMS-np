package com.firesms.app.domain.rules

import com.firesms.app.data.local.entity.ParserRule
import com.firesms.app.domain.model.ParsedTransaction
import com.firesms.app.domain.parser.SmsParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Example messages are made up in the shapes Nepali banks and wallets use; none are real. */
class NepalDefaultRulesTest {

    private val now = 1_763_000_000_000L

    /** The default rules switched on, in the order the parser tries them. */
    private val enabled: List<ParserRule> =
        NepalDefaultRules.rules.map { it.copy(enabled = true) }.sortedWith(compareBy({ it.priority }, { it.name.lowercase() }))

    private fun parse(sender: String, body: String): Pair<ParserRule, ParsedTransaction>? =
        enabled.firstNotNullOfOrNull { rule -> SmsParser.applyRule(rule, sender, body, now)?.let { rule to it } }

    @Test
    fun everyDefaultRuleIsValid() {
        for (rule in NepalDefaultRules.rules) {
            val error = RuleValidator.validate(
                rule.bodyPattern, rule.amountTemplate, rule.descriptionSource, rule.descriptionValue, rule.type,
                rule.datePattern.orEmpty(), rule.dateFormat.orEmpty(),
                rule.remarksPattern.orEmpty(), rule.remarksTemplate.orEmpty()
            )
            assertNull(error, "${rule.name}: $error")
            assertNull(RuleValidator.patternProblem(rule.senderPattern), rule.name)
        }
    }

    @Test
    fun defaultRulesStartSwitchedOffWithUniqueIds() {
        assert(NepalDefaultRules.rules.none { it.enabled })
        assertEquals(NepalDefaultRules.rules.size, NepalDefaultRules.ids.size)
    }

    @Test
    fun bankDebitWithRemarks() {
        val (rule, tx) = assertNotNull(
            parse(
                "NABIL_ALERT",
                "Dear Customer, Your A/C 01XXXXXXX5012 has been debited by NPR 1,500.00 on 15/11/2026. " +
                    "Remarks: ATM WDL KTM. Avl Bal: NPR 25,000.00"
            )
        )
        assertEquals("np-bank-sms-debit", rule.id)
        assertEquals("1500.00", tx.amount)
        assertEquals("withdrawal", tx.transactionType)
        assertEquals("ATM WDL KTM", tx.description)
    }

    @Test
    fun bankCreditWithoutRemarksFallsBackToRuleName() {
        val (rule, tx) = assertNotNull(
            parse("GIBL", "NPR 12,000 credited to your A/C ###4521 on 2026-11-15. Bal NPR 40,250.75")
        )
        assertEquals("np-bank-sms-credit", rule.id)
        assertEquals("12000", tx.amount)
        assertEquals("deposit", tx.transactionType)
        assertEquals("Bank SMS credit", tx.description)
    }

    @Test
    fun bankRulesIgnoreOtpAndFailedMessages() {
        assertNull(parse("NICASIA", "Your OTP for fund transfer of NPR 5,000 is 482913. Do not share."))
        assertNull(parse("NICASIA", "Transaction of NPR 5,000 debited from your A/C has failed and will be reversed."))
    }

    @Test
    fun bankRulesIgnoreOtherSenders() {
        assertNull(parse("98XXXXXXXX", "Your A/C has been debited by NPR 100.00"))
    }

    @Test
    fun esewaMoneyReceived() {
        val (rule, tx) = assertNotNull(
            parse(NepalDefaultRules.ESEWA_PACKAGE, "Money Received\nYou have received NPR 500.00 from Ram Bahadur (98XXXXXXXX).")
        )
        assertEquals("np-esewa-received", rule.id)
        assertEquals("500.00", tx.amount)
        assertEquals("Ram Bahadur", tx.description)
        // A deposit puts the user's own wallet on the destination side.
        assertEquals("eSewa", tx.destinationAccountName)
    }

    @Test
    fun esewaPayment() {
        val (rule, tx) = assertNotNull(
            parse(NepalDefaultRules.ESEWA_PACKAGE, "Payment Successful\nYou have paid Rs. 1,200 to Nepal Telecom.")
        )
        assertEquals("np-esewa-sent", rule.id)
        assertEquals("1200", tx.amount)
        assertEquals("Nepal Telecom", tx.description)
        assertEquals("eSewa", tx.sourceAccountName)
    }

    @Test
    fun khaltiTransferSent() {
        val (rule, tx) = assertNotNull(
            parse(NepalDefaultRules.KHALTI_PACKAGE, "Fund Transferred\nरु 750 sent to Sita Sharma successfully.")
        )
        assertEquals("np-khalti-sent", rule.id)
        assertEquals("750", tx.amount)
        assertEquals("Sita Sharma", tx.description)
        assertEquals("Khalti", tx.sourceAccountName)
    }

    @Test
    fun walletPromotionsWithoutTransactionWordsAreIgnored() {
        assertNull(parse(NepalDefaultRules.KHALTI_PACKAGE, "Dashain Offer!\nGet up to Rs. 500 cashback on electricity bills."))
    }

    @Test
    fun walletRulesDoNotMatchBankSms() {
        assertNull(
            enabled.filter { it.id.startsWith("np-esewa") }
                .firstNotNullOfOrNull { SmsParser.applyRule(it, "NABIL", "You have received NPR 500 from X", now) }
        )
    }
}
