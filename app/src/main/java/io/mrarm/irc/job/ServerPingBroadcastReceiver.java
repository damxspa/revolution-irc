package io.mrarm.irc.job;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ServerPingBroadcastReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final PendingResult pendingResult = goAsync();
        ServerPingScheduler.getInstance(context).onJobRan();
        ServerPingTask.pingServers(context, () -> {
            pendingResult.finish();
        });
    }

}
