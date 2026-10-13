package com.ianshkondin.mat239;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Напоминание о занятии в день плана.
 *
 * Срабатывает ежедневно в настроенное время; показывает уведомление, только
 * если на сегодня есть занятие по плану с нерешёнными задачами (снапшот
 * sessions: дата → left, обновляется JS-мостом при каждом сохранении
 * прогресса). Сразу перепланирует следующий день — независимо от того,
 * показало ли уведомление.
 */
public class NotifyReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "t239_train";
    private static final int NOTIFY_ID = 1001;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            JSONObject snap = AlarmScheduler.readSnapshot(ctx);

            // Следующий день — независимо от наличия занятия сегодня
            if (snap != null) {
                JSONObject n = snap.optJSONObject("notify");
                if (n != null && n.optBoolean("enabled", false)) {
                    AlarmScheduler.schedule(ctx, n.optInt("hh", 17), n.optInt("mm", 0));
                }
            }

            if (snap == null) return; // данных ещё нет — тихо

            Calendar now = Calendar.getInstance();
            String today = String.format(Locale.US, "%04d-%02d-%02d",
                    now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));

            JSONObject sessions = snap.optJSONObject("sessions");
            if (sessions == null) return;
            JSONObject ses = sessions.optJSONObject(today);
            if (ses == null) return;              // сегодня занятий нет
            int left = ses.optInt("left", 0);
            if (left <= 0) return;                // сегодняшнее занятие уже решено

            int total = ses.optInt("total", left);
            int solved = snap.optInt("solved", 0);
            int allTotal = snap.optInt("allTotal", 1036);
            String goal = snap.optString("goal", "");

            String title = "Время заниматься";
            StringBuilder sb = new StringBuilder();
            sb.append("Сегодня по плану ")
              .append(left).append(left == 1 ? " задача" : plural(left)).append(" из ").append(total).append('.');
            sb.append("\nРешено: ").append(solved).append(" из ").append(allTotal).append(".");
            if (!goal.isEmpty()) sb.append("\n").append(goal);

            showNotification(ctx, title, sb.toString());
        } catch (Exception e) {
            // Напоминание некритично: тихо логируем и не роняем будильник
            android.util.Log.w("T239Notify", "notification failed", e);
        }
    }

    /** Русская форма для 2–20+ задач («задачи»/«задач»). */
    private static String plural(int n) {
        int mod100 = n % 100;
        int mod10 = n % 10;
        if (mod100 >= 11 && mod100 <= 14) return " задач";
        if (mod10 >= 2 && mod10 <= 4) return " задачи";
        return " задач";
    }

    private void showNotification(Context ctx, String title, String message) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Напоминания о занятиях",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL_ID);
        } else {
            b = new Notification.Builder(ctx); // устаревший конструктор для API 24–25
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // FLAG_IMMUTABLE — с API 23 (см. AlarmScheduler.piFlags)
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, piFlags);

        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(pi)
                .setAutoCancel(true);

        nm.notify(NOTIFY_ID, b.build());
    }
}
