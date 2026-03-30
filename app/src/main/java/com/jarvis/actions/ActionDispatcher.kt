package com.jarvis.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.concurrent.TimeUnit

data class ActionResult(
    val handled: Boolean,
    val message: String,
    val needsConfirmation: Boolean = false,
    val pendingPhoneNumber: String? = null
)

enum class ActionKind {
    Reminder,
    Alarm,
    OpenApp,
    Call,
    None
}

class ActionDispatcher(private val context: Context) {

    fun dispatch(text: String): ActionResult {
        val normalized = text.lowercase()
        return when (detectActionKind(normalized)) {
            ActionKind.Reminder -> createReminder(normalized)
            ActionKind.Alarm -> setAlarm(normalized)
            ActionKind.OpenApp -> openApp(normalized)
            ActionKind.Call -> prepareCall(normalized)
            ActionKind.None -> ActionResult(handled = false, message = "")
        }
    }

    fun executeConfirmedCall(phone: String): ActionResult {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = android.net.Uri.parse("tel:$phone")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionResult(handled = true, message = "Qo'ng'iroq oynasi ochildi: $phone")
        }.getOrElse {
            ActionResult(handled = true, message = "Qo'ng'iroqni boshlash uchun ruxsat yoki ilova mavjud emas.")
        }
    }

    private fun createReminder(text: String): ActionResult {
        val minutes = Regex("(\\d+)\\s*daq").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 10
        val triggerAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(minutes.toLong())
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_MESSAGE, "Eslatma vaqti bo'ldi.")
        }
        val pending = PendingIntent.getBroadcast(
            context,
            triggerAt.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return runCatching {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            ActionResult(true, "Eslatma $minutes daqiqadan keyin qo'yildi.")
        }.getOrElse {
            ActionResult(true, "Eslatma qo'yishda ruxsat yoki tizim xatosi yuz berdi.")
        }
    }

    private fun setAlarm(text: String): ActionResult {
        val hour = Regex("(\\d{1,2})[:.]").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 7
        val minute = Regex("(\\d{1,2})[:.](\\d{1,2})").find(text)?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            ActionResult(true, "Budilnik uchun soat $hour:$minute oynasi ochildi.")
        }.getOrElse {
            ActionResult(true, "Budilnikni qo'yish uchun tizim ruxsati yoki ilovasi topilmadi.")
        }
    }

    private fun openApp(text: String): ActionResult {
        val appName = text.substringAfter("ilova").substringBefore("och").trim()
        if (appName.isBlank()) {
            return ActionResult(true, "Qaysi ilovani ochishni ayting.")
        }
        val packageManager = context.packageManager
        val app = packageManager.getInstalledApplications(0).firstOrNull {
            val label = packageManager.getApplicationLabel(it).toString().lowercase()
            label.contains(appName)
        } ?: return ActionResult(true, "Bu nomga mos ilova topilmadi.")

        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionResult(true, "Ilovani ishga tushirib bo'lmadi.")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            ActionResult(true, "Ilova ochildi: $appName")
        }.getOrElse {
            ActionResult(true, "Ilovani ochishda tizim xatosi yuz berdi.")
        }
    }

    private fun prepareCall(text: String): ActionResult {
        val phone = Regex("(\\+?\\d{7,15})").find(text)?.groupValues?.getOrNull(1)
            ?: return ActionResult(true, "Qo'ng'iroq uchun raqamni ayting.")
        return ActionResult(
            handled = true,
            message = "$phone raqamiga qo'ng'iroq qilishni tasdiqlaysizmi?",
            needsConfirmation = true,
            pendingPhoneNumber = phone
        )
    }

    companion object {
        fun detectActionKind(text: String): ActionKind {
            val normalized = text.lowercase()
            return when {
                normalized.contains("eslatma") -> ActionKind.Reminder
                normalized.contains("budilnik") || normalized.contains("signal") -> ActionKind.Alarm
                normalized.contains("ilova") && normalized.contains("och") -> ActionKind.OpenApp
                normalized.contains("qo'ng'iroq") || normalized.contains("qongiroq") -> ActionKind.Call
                else -> ActionKind.None
            }
        }
    }
}
