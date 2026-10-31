package io.github.maxhayim.cornerplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After the TV restarts or Corner Player is updated: network control comes back on if it was on, and what was in
 * the corner comes back if Reopen after restart is on.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (Config.remote(c)) RemoteService.sync(c);
        Corner.Request last = Config.last(c);
        if (Config.reopen(c) && last != null && CornerService.canDraw(c)) CornerService.start(c, last, 0);
    }
}
