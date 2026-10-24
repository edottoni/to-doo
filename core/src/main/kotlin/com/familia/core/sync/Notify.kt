package com.familia.core.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notificações com deduplicação: cada chave de evento só dispara uma vez. */
object Notify {
    /** Primeira checagem após login: só registra eventos antigos, sem notificar. */
    @Volatile var silent = false
    fun isSeeded(ctx: Context) = seen(ctx).getBoolean("_seeded", false)
    fun markSeeded(ctx: Context) = seen(ctx).edit().putBoolean("_seeded", true).apply()

    private fun seen(ctx: Context) = ctx.getSharedPreferences("notified", Context.MODE_PRIVATE)

    fun prefEnabled(ctx: Context, category: String): Boolean =
        ctx.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE).getBoolean("on_$category", true)
    fun setEnabled(ctx: Context, category: String, on: Boolean) =
        ctx.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE).edit().putBoolean("on_$category", on).apply()
    /** Horário do lembrete em minutos desde 00:00. */
    fun reminderMinutes(ctx: Context, category: String, default: Int): Int =
        ctx.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE).getInt("t_$category", default)
    fun setReminderMinutes(ctx: Context, category: String, m: Int) =
        ctx.getSharedPreferences("notif_prefs", Context.MODE_PRIVATE).edit().putInt("t_$category", m).apply()

    fun clearHistory(ctx: Context) = seen(ctx).edit().clear().apply()

    fun fire(ctx: Context, activity: Class<*>, category: String, eventKey: String, title: String, text: String) {
        if (!prefEnabled(ctx, category)) return
        if (seen(ctx).getBoolean(eventKey, false)) return
        if (silent) { seen(ctx).edit().putBoolean(eventKey, true).apply(); return }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(category, "Casa em dia — $category", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(ctx, eventKey.hashCode(), Intent(ctx, activity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, category).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi).setAutoCancel(true).build()
        NotificationManagerCompat.from(ctx).notify(eventKey.hashCode(), n)
        seen(ctx).edit().putBoolean(eventKey, true).apply()
    }
}
