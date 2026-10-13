package com.ianshkondin.mat239;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * После перезагрузки AlarmManager чист — восстанавливаем будильник
 * напоминаний из снапшота (t239-snapshot.json), который JS-мост
 * обновляет при каждом сохранении прогресса и настроек.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AlarmScheduler.rescheduleFromSnapshot(ctx);
        }
    }
}
