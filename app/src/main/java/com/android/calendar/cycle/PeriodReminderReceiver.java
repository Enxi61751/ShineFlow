package com.android.calendar.cycle;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import java.time.LocalDate;
import java.time.ZoneId;

import ws.xsoh.etar.R;

/**
 * Schedules and shows the "period is coming" reminder notification. Kept
 * self-contained: prediction comes from {@link PeriodRepository}, everything is
 * local.
 */
public class PeriodReminderReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "shineflow_period";
    private static final int NOTIFICATION_ID = 0x5F10;
    private static final int REQUEST_CODE = 0x5F11;
    private static final int NOTIFY_HOUR = 9;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (PeriodRepository.isEnabled(context)) {
            showReminder(context);
        }
        // Always compute the next occurrence.
        schedule(context);
    }

    private void showReminder(Context context) {
        PeriodRepository repo = PeriodRepository.get(context);
        long next = repo.predictedNextStart();
        if (next < 0) {
            return;
        }
        long today = PeriodRepository.todayEpochDay();
        long daysLeft = next - today;
        if (daysLeft < 0 || daysLeft > repo.getReminderDays()) {
            return;
        }
        String text = daysLeft <= 0
                ? context.getString(R.string.period_reminder_today)
                : context.getString(R.string.period_reminder_soon, (int) daysLeft);

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.period_reminder_channel),
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, PeriodActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_period)
                .setContentTitle(context.getString(R.string.period_reminder_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    /**
     * (Re)schedules the next reminder alarm based on the current prediction.
     * Safe to call whenever data or settings change.
     */
    public static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = buildPendingIntent(context);

        if (!PeriodRepository.isEnabled(context)) {
            am.cancel(pi);
            return;
        }
        PeriodRepository repo = PeriodRepository.get(context);
        long next = repo.predictedNextStart();
        if (next < 0) {
            am.cancel(pi);
            return;
        }
        long remindDay = next - repo.getReminderDays();
        long today = PeriodRepository.todayEpochDay();
        if (remindDay < today) {
            // Reminder day already passed for this cycle; try the day itself.
            remindDay = next;
            if (remindDay < today) {
                am.cancel(pi);
                return;
            }
        }
        long triggerAt = LocalDate.ofEpochDay(remindDay)
                .atTime(NOTIFY_HOUR, 0)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
        am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, PeriodReminderReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
