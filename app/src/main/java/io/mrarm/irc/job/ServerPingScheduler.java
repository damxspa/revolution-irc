package io.mrarm.irc.job;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import android.util.Log;

import io.mrarm.irc.ServerConnectionManager;
import io.mrarm.irc.config.AppSettings;
import io.mrarm.irc.config.SettingsHelper;
import io.mrarm.irc.config.UiSettingChangeCallback;

public class ServerPingScheduler {

    private static final String TAG = "ServerPingScheduler";
    private static final int INTENT_ID = 300;
    public static final int JOB_ID = 1;

    private static ServerPingScheduler instance;

    public static ServerPingScheduler getInstance(Context ctx) {
        if (instance == null)
            instance = new ServerPingScheduler(ctx.getApplicationContext());
        return instance;
    }

    private Context context;
    private boolean running;
    private boolean enabled = true;
    private long interval = 15 * 1000; // 15 segundos constantes
    private boolean onlyOnWifi = false;

    public ServerPingScheduler(Context ctx) {
        this.context = ctx;
        SettingsHelper.registerCallbacks(this);
        onSettingChanged();
    }

    @UiSettingChangeCallback(keys = {AppSettings.PREF_PING_ENABLED,
            AppSettings.PREF_PING_WI_FI_ONLY, AppSettings.PREF_PING_INTERVAL})
    private void onSettingChanged() {
        enabled = AppSettings.isPingEnabled();
        onlyOnWifi = AppSettings.isPingWiFiOnly();
        interval = AppSettings.getPingInterval();
        stop();
        startIfEnabled();
    }

    void onJobRan() {
        if (!running) {
            forceStop();
            return;
        }
        scheduleNext();
    }

    public void startIfEnabled() {
        if (isUsingNetworkStateAwareApi() || !onlyOnWifi) {
            start();
        } else {
            onWifiStateChanged(ServerConnectionManager.isWifiConnected(context));
        }
    }

    public void onWifiStateChanged(boolean connectedToWifi) {
        if (!isUsingNetworkStateAwareApi() && onlyOnWifi) {
            if (connectedToWifi) start();
            else stop();
        }
    }

    public boolean isUsingJobService() {
        // JobService no permite intervalos menores a 15 minutos. 
        // Para 15 segundos DEBEMOS usar AlarmManager.
        return false;
    }

    public boolean isUsingNetworkStateAwareApi() {
        return isUsingJobService();
    }

    private void start() {
        if (running) return;
        Log.d(TAG, "Starting persistent Ping Scheduler (15s interval)");
        running = true;
        startUsingAlarmManager();
    }

    public void stop() {
        if (!running) return;
        forceStop();
    }

    public void forceStop() {
        running = false;
        stopUsingAlarmManager();
    }

    private PendingIntent getAlarmManagerIntent() {
        Intent intent = new Intent(context, ServerPingBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, INTENT_ID, intent, flags);
    }

    private PendingIntent getShowIntent() {
        Intent intent = new Intent(context, io.mrarm.irc.MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    private void startUsingAlarmManager() {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        long triggerAtWall = System.currentTimeMillis() + interval;

        // setAlarmClock es el método más potente disponible. 
        // Al ser tratado como una "Alarma de Reloj", el sistema (incluido OriginOS) 
        // tiene mucha más dificultad para ignorarlo o retrasarlo.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(triggerAtWall, getShowIntent());
            am.setAlarmClock(info, getAlarmManagerIntent());
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtWall, getAlarmManagerIntent());
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAtWall, getAlarmManagerIntent());
        }
    }

    public void scheduleNext() {
        if (running) {
            startUsingAlarmManager();
        }
    }

    private void stopUsingAlarmManager() {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(getAlarmManagerIntent());
        }
    }
}
