package io.mrarm.irc.job;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.mrarm.chatlib.ChatApi;
import io.mrarm.chatlib.irc.IRCConnection;
import io.mrarm.irc.ServerConnectionInfo;
import io.mrarm.irc.ServerConnectionManager;

public class ServerPingTask {

    public static void pingServers(Context ctx, DoneCallback cb) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "io.mrarm.irc:ServerPingWakeLock");
        wakeLock.acquire(30000); // 30 segundos máximo

        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.WifiLock wifiLock = null;
        if (wm != null) {
            wifiLock = wm.createWifiLock(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF : WifiManager.WIFI_MODE_FULL, "io.mrarm.irc:PingWifiLock");
            wifiLock.acquire();
        }
        final WifiManager.WifiLock fWifiLock = wifiLock;

        List<ServerConnectionInfo> servers =
                ServerConnectionManager.getInstance(ctx).getConnections();
        List<IRCConnection> serversToPing = new ArrayList<>();
        
        for (ServerConnectionInfo c : servers) {
            if (!c.isConnected()) {
                if (c.getServerAddress() != null && !c.isConnecting()) {
                     Log.d("ServerPingTask", "Server disconnected, forcing reconnect: " + c.getServerAddress());
                     c.connect();
                }
                continue;
            }
            ChatApi api = c.getApiInstance();
            if (api != null && api instanceof IRCConnection)
                serversToPing.add((IRCConnection) api);
        }

        if (serversToPing.size() == 0) {
            if (wakeLock.isHeld()) wakeLock.release();
            if (fWifiLock != null && fWifiLock.isHeld()) fWifiLock.release();
            cb.onDone();
            return;
        }

        AtomicInteger countdownInteger = new AtomicInteger(serversToPing.size());
        Handler timeoutHandler = new Handler(Looper.getMainLooper());

        for (IRCConnection api : serversToPing) {
            final boolean[] responded = {false};
            
            // Si en 10 segundos no hay respuesta, forzamos el cierre del socket para que el sistema reconecte
            Runnable timeoutRunnable = () -> {
                if (!responded[0]) {
                    Log.w("ServerPingTask", "Critical Ping Timeout! Forcing reconnect for a stale connection.");
                    for (ServerConnectionInfo info : ServerConnectionManager.getInstance(ctx).getConnections()) {
                        if (info.getApiInstance() == api) {
                            info.forceReconnect();
                            break;
                        }
                    }
                }
            };
            timeoutHandler.postDelayed(timeoutRunnable, 10000);

            api.sendPing((Void v) -> {
                responded[0] = true;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (countdownInteger.decrementAndGet() == 0) {
                    if (wakeLock.isHeld()) wakeLock.release();
                    if (fWifiLock != null && fWifiLock.isHeld()) fWifiLock.release();
                    cb.onDone();
                }
            },
            (Exception e) -> {
                responded[0] = true;
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (countdownInteger.decrementAndGet() == 0) {
                    if (wakeLock.isHeld()) wakeLock.release();
                    if (fWifiLock != null && fWifiLock.isHeld()) fWifiLock.release();
                    cb.onDone();
                }
            });
        }
    }

    public interface DoneCallback {
        void onDone();
    }
}
