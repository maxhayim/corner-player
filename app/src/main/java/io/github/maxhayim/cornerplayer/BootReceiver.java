package io.github.maxhayim.cornerplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Network control comes back on after the TV restarts or Corner Player is updated, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) && Config.remote(c)) {
            RemoteService.sync(c);
        }
    }
}
