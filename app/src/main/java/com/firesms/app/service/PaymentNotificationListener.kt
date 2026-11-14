package com.firesms.app.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.firesms.app.data.local.AppDatabase
import com.firesms.app.data.local.DataMaintenanceLock
import com.firesms.app.domain.parser.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Reads payment notifications from apps such as eSewa and Khalti and sends them through the same
 * parser rules as SMS. The app's package name (e.g. `com.f1soft.esewa`) is the sender and the
 * notification's title and text, joined by a newline, are the body.
 *
 * Only apps that an enabled rule's sender pattern names are read; everything else is ignored
 * without being stored. The user turns this on under Android's notification access settings.
 */
class PaymentNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PaymentNotifications"

        /** Apps update or repost the same notification; one transaction per text in this window. */
        private const val REPEAT_WINDOW_MILLIS = 10 * 60 * 1000L

        /** Wallet apps also post offers and reminders; only text with an amount can be a payment. */
        private val HAS_AMOUNT = Regex("""(?i)(?:NPR|Rs\.?|रु\.?)\s*\d""")

        /** The title and the longest available text of [notification], or null when it has neither. */
        fun notificationBody(notification: Notification): String? {
            val extras = notification.extras ?: return null
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
            val text = (
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                )?.toString()?.trim()
            return listOfNotNull(title, text).filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
        }

        fun looksLikePayment(body: String): Boolean = HAS_AMOUNT.containsMatchIn(body)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = LinkedHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val sender = sbn.packageName ?: return
        if (sender == packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val body = notificationBody(notification) ?: return
        if (!looksLikePayment(body)) return
        if (!isFirstSighting("$sender|$body", sbn.postTime)) return

        val context = applicationContext
        scope.launch {
            try {
                val parser = SmsParser(AppDatabase.getInstance(context).parserRuleDao())
                if (!parser.senderMatchesAnyEnabledRule(sender)) return@launch
                val result = DataMaintenanceLock.withLock {
                    SmsTransactionProcessor(context).ingest(sender, body, sbn.postTime)
                }
                if (result is SmsTransactionProcessor.IngestResult.Processed) {
                    Log.d(TAG, "Processed notification from $sender: ${result.outcome}")
                    notifyIfNeedsAttention(context, result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle notification from $sender", e)
            }
        }
    }

    @Synchronized
    private fun isFirstSighting(key: String, postedAt: Long): Boolean {
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > REPEAT_WINDOW_MILLIS }
        if (key in recent) return false
        recent[key] = maxOf(postedAt, now)
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
