package io.mrarm.irc;

import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import android.os.Message;
import android.util.Log;

import android.os.Handler;
import android.os.Looper;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.mrarm.chatlib.dto.MessageId;
import io.mrarm.chatlib.dto.MessageInfo;
import io.mrarm.chatlib.message.MessageListener;
import io.mrarm.irc.job.ServerPingScheduler;
import io.mrarm.irc.job.ServerPingTask;
import io.mrarm.irc.util.WarningHelper;

public class IRCService extends Service implements ServerConnectionManager.ConnectionsListener, ServerConnectionInfo.InfoChangeListener {

    private static final String TAG = "IRCService";

    public static final int IDLE_NOTIFICATION_ID = 100;
    public static final int EXIT_ACTION_ID = 102; // 101 is taken by chat summary
    public static final String ACTION_START_FOREGROUND = "start_foreground";

    private static final String IDLE_NOTIFICATION_CHANNEL = "IdleNotification";

    private ConnectivityChangeReceiver mConnectivityReceiver = new ConnectivityChangeReceiver();
    private ConnectivityManager.NetworkCallback mNetworkCallback;

    private boolean mCreatedChannel = false;

    private Map<ServerConnectionInfo, MessageListener> messageListeners = new HashMap<>();
    private PowerManager.WakeLock mWakeLock;
    private WifiManager.WifiLock mWifiLock;

    public static void start(Context context) {
        Intent intent = new Intent(context, IRCService.class);
        intent.setAction(ACTION_START_FOREGROUND);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent);
        else
            context.startService(intent);
    }
    public static void stop(Context context) {
        context.stopService(new Intent(context, IRCService.class));
    }

    public static void createNotificationChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        NotificationChannel channel = new NotificationChannel(IDLE_NOTIFICATION_CHANNEL,
                ctx.getString(R.string.notification_channel_idle),
                android.app.NotificationManager.IMPORTANCE_MIN);
        channel.setGroup(NotificationManager.getSystemNotificationChannelGroup(ctx));
        channel.setShowBadge(false);
        android.app.NotificationManager mgr = (android.app.NotificationManager)
                ctx.getSystemService(NOTIFICATION_SERVICE);
        mgr.createNotificationChannel(channel);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        WarningHelper.setAppContext(getApplicationContext());

        ChatLogStorageManager.getInstance(getApplicationContext());

        for (ServerConnectionInfo connection : ServerConnectionManager.getInstance(this).getConnections())
            onConnectionAdded(connection);
        ServerConnectionManager.getInstance(this).addListener(this);
        ServerConnectionManager.getInstance(this).addGlobalConnectionInfoListener(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null) {
                mNetworkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        onConnectivityChanged(true);
                    }

                    @Override
                    public void onLost(Network network) {
                        onConnectivityChanged(false);
                    }

                    @Override
                    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
                        ServerPingScheduler.getInstance(IRCService.this).onWifiStateChanged(
                                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                    }
                };
                connectivityManager.registerDefaultNetworkCallback(mNetworkCallback);
            }
        } else {
            @SuppressWarnings("deprecation")
            String action = ConnectivityManager.CONNECTIVITY_ACTION;
            registerReceiver(mConnectivityReceiver, new IntentFilter(action));
        }

        ServerPingScheduler.getInstance(this).startIfEnabled();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (ServerConnectionManager.hasInstance()) {
            for (ServerConnectionInfo connection : ServerConnectionManager.getInstance(this)
                    .getConnections())
                onConnectionRemoved(connection);
            ServerConnectionManager.getInstance(this).removeListener(this);
            ServerConnectionManager.getInstance(this).removeGlobalConnectionInfoListener(this);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && mNetworkCallback != null) {
            ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager != null)
                connectivityManager.unregisterNetworkCallback(mNetworkCallback);
        } else {
            unregisterReceiver(mConnectivityReceiver);
        }

        ServerPingScheduler.getInstance(this).stop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null)
            return START_STICKY;
        if (action.equals(ACTION_START_FOREGROUND)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !mCreatedChannel) {
                createNotificationChannel(this);
                mCreatedChannel = true;
            }

            StringBuilder b = new StringBuilder();
            int connectedCount = 0, connectingCount = 0, disconnectedCount = 0;
            for (ServerConnectionInfo connectionInfo :
                    ServerConnectionManager.getInstance(this).getConnections()) {
                if (connectionInfo.isConnected())
                    connectedCount++;
                else if (connectionInfo.isConnecting())
                    connectingCount++;
                else
                    disconnectedCount++;
            }
            b.append(getResources().getQuantityString(R.plurals.service_status_connected, connectedCount, connectedCount));
            if (connectingCount > 0) {
                b.append(getResources().getString(R.string.text_comma));
                b.append(getResources().getQuantityString(R.plurals.service_status_connecting, connectingCount, connectingCount));
            }
            if (disconnectedCount > 0) {
                b.append(getResources().getString(R.string.text_comma));
                b.append(getResources().getQuantityString(R.plurals.service_status_disconnected, disconnectedCount, disconnectedCount));
            }

            Intent mainIntent = MainActivity.getLaunchIntent(this, null, null);
            int pendingIntentFlags = PendingIntent.FLAG_CANCEL_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;

            PendingIntent exitIntent = PendingIntent.getBroadcast(this, EXIT_ACTION_ID,
                    ExitActionReceiver.getIntent(this),
                    pendingIntentFlags);
            NotificationCompat.Builder notification = new NotificationCompat.Builder(this, IDLE_NOTIFICATION_CHANNEL)
                    .setContentTitle(getString(R.string.service_title))
                    .setContentText(b.toString())
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(PendingIntent.getActivity(this, IDLE_NOTIFICATION_ID, mainIntent, pendingIntentFlags))
                    .addAction(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ? R.drawable.ic_close : R.drawable.ic_notification_close, getString(R.string.action_exit), exitIntent);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                notification.setSmallIcon(R.drawable.ic_server_connected);
            else
                notification.setSmallIcon(R.drawable.ic_notification_connected);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(IDLE_NOTIFICATION_ID, notification.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(IDLE_NOTIFICATION_ID, notification.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(IDLE_NOTIFICATION_ID, notification.build());
            }
            updatePersistentLocks();
        }
        return START_STICKY;
    }

    @Override
    public void onConnectionInfoChanged(ServerConnectionInfo connection) {
        if (mWakeLock != null && mWakeLock.isHeld()) {
            mWakeLock.release();
            mWakeLock = null;
        }
        updatePersistentLocks();
    }

    private void updatePersistentLocks() {
        boolean needed = false;
        if (ServerConnectionManager.hasInstance()) {
            for (ServerConnectionInfo connection : ServerConnectionManager.getInstance(this).getConnections()) {
                if (connection.isConnected() || connection.isConnecting()) {
                    needed = true;
                    break;
                }
            }
        }
        if (needed) {
            if (mWakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "io.mrarm.irc:ServiceWakeLock");
                mWakeLock.acquire(10 * 60 * 1000L); // 10 minutos de seguridad, se refresca con cada cambio
            }
            if (mWifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wm != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "io.mrarm.irc:ServiceWifiLock");
                    } else {
                        //noinspection deprecation
                        mWifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "io.mrarm.irc:ServiceWifiLock");
                    }
                    mWifiLock.acquire();
                }
            }
        } else {
            if (mWakeLock != null) {
                mWakeLock.release();
                mWakeLock = null;
            }
            if (mWifiLock != null) {
                mWifiLock.release();
                mWifiLock = null;
            }
        }
    }

    private void onMessage(ServerConnectionInfo connection, String channel, MessageInfo info,
                           MessageId messageId) {
        NotificationManager.getInstance().processMessage(this, connection, channel, info, messageId);
        ChatLogStorageManager.getInstance(this).onMessage(connection);
    }

    @Override
    public void onConnectionAdded(ServerConnectionInfo connection) {
        MessageListener listener =  (String channel, MessageInfo info, MessageId id) -> {
            onMessage(connection, channel, info, id);
        };
        messageListeners.put(connection, listener);
        connection.getApiInstance().getMessageStorageApi().subscribeChannelMessages(null, listener, null, null);
    }

    @Override
    public void onConnectionRemoved(ServerConnectionInfo connection) {
        MessageListener listener = messageListeners.get(connection);
        if (listener != null)
            connection.getApiInstance().getMessageStorageApi().unsubscribeChannelMessages(null, listener, null, null);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static class BootReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i("IRCService", "Device booted");
            IRCService.start(context);
        }

    }

    public static class ExitActionReceiver extends BroadcastReceiver {

        public static Intent getIntent(Context context) {
            return new Intent(context, ExitActionReceiver.class);
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            ((IRCApplication) context.getApplicationContext()).requestExit();
        }

    }

    private void onConnectivityChanged(boolean connected) {
        Log.i(TAG, "Connectivity changed: " + connected);
        ServerConnectionManager.getInstance(this).notifyConnectivityChanged(connected);
        ServerPingScheduler.getInstance(this).onWifiStateChanged(
                ServerConnectionManager.isWifiConnected(this));
    }

    public class ConnectivityChangeReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            onConnectivityChanged(!intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, Boolean.FALSE));
        }

    }

}
