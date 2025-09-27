package slowscript.warpinator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public class MainService extends Service {
    private static final String TAG = "SERVICE";

    public static String CHANNEL_SERVICE = "MainService";
    public static String CHANNEL_INCOMING = "IncomingTransfer";
    public static String CHANNEL_PROGRESS = "TransferProgress";
    static int SVC_NOTIFICATION_ID = 1;
    static int PROGRESS_NOTIFICATION_ID = 2;
    static String ACTION_STOP = "StopSvc";
    static int WAKELOCK_TIMEOUT = 10; // 10 min
    static long pingTime = 10_000;
    static long reconnectTime = 40_000;
    static long autoStopTime = 60_000;

    public int runningTransfers = 0;
    public boolean networkAvailable = false;
    public boolean apOn = false;
    int notifId = 1300;
    Utils.IPInfo currentIPInfo = null;

    public static MainService svc;
    public static ConcurrentHashMap<String, Remote> remotes = new ConcurrentHashMap<>();
    public static final ArrayList<WeakReference<RemoteCountObserver>> remoteCountObservers = new ArrayList<>();
    public static List<String> remotesOrder = Collections.synchronizedList(new ArrayList<>());
    SharedPreferences prefs;
    ExecutorService executor = Executors.newCachedThreadPool();
    public NotificationManagerCompat notificationMgr;
    PowerManager.WakeLock wakeLock;

    private NotificationCompat.Builder notifBuilder = null;
    private Server server;
    private Timer timer;
    private Process logcatProcess;
    private WifiManager.MulticastLock lock;
    private ConnectivityManager connMgr;
    private ConnectivityManager.NetworkCallback networkCallback;
    private BroadcastReceiver apStateChangeReceiver;
    private TimerTask autoStopTask;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return new MainServiceBinder(this);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        remoteCountObservers.clear();
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        svc = this;
        notificationMgr = NotificationManagerCompat.from(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // Start logging
        if (prefs.getBoolean("debugLog", false))
            logcatProcess = launchLogcat();

        // Acquire multicast lock for mDNS
        acquireMulticastLock();

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"MainService::TransferWakeLock");
        wakeLock.setReferenceCounted(false);

        server = new Server(this);
        currentIPInfo = Utils.getIPAddress(); // Server needs to load iface setting before this
        Log.d(TAG, Utils.dumpInterfaces());

        createNotificationChannels();
        Notification notification = createForegroundNotification();
        startForeground(SVC_NOTIFICATION_ID, notification); //Sometimes fails. Maybe takes too long to get here?
        Log.v(TAG, "Entered foreground");

        // Actually start server if possible. This takes a long time so should be after startForeground()
        if (currentIPInfo != null) {
            Authenticator.getServerCertificate(); //Generate cert on start if doesn't exist
            if (Authenticator.certException != null) {
                LocalBroadcasts.displayMessage(this, "Failed to initialize service",
                        "A likely reason for this is that your IP address could not be obtained. " +
                                "Please make sure you are connected to WiFi.\n" +
                                "\nAvailable interfaces:\n" + Utils.dumpInterfaces() +
                                "\nException: " + Authenticator.certException.toString());
                Log.w(TAG, "Server will not start due to error");
            } else {
                server.Start();
            }
        }

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                pingRemotes();
            }
        }, 5L, pingTime);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                autoReconnect();
            }
        }, 5L, reconnectTime);

        listenOnNetworkChanges();

        // Notify the tile service that MainService just started, so it can attempt to bind
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileMainService.requestListeningState(this);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Task removed");
        if (runningTransfers == 0) // && autostop enabled
            autoStop();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        super.onTimeout(startId, fgsType);
        Log.e(TAG, "Service has run out of time and must be stopped (Android 15+)");
        stopSelf();
    }

    private void stopServer () {
        if (server == null) //I have no idea how this can happen
            return;
        for (Remote r : remotes.values()) {
            if (r.status == Remote.RemoteStatus.CONNECTED)
                r.disconnect();
        }
        remotes.clear();
        remotesOrder.clear();
        server.Stop(true);
        notificationMgr.cancelAll();
        connMgr.unregisterNetworkCallback(networkCallback);
        unregisterReceiver(apStateChangeReceiver);
        executor.shutdown();
        timer.cancel();
        if (lock != null)
            lock.release();
    }

    static void scheduleAutoStop() {
        if (svc != null && svc.runningTransfers == 0 && svc.autoStopTask == null &&
                svc.isAutoStopEnabled() && WarpinatorApp.activitiesRunning < 1) {
            svc.autoStopTask = new TimerTask() {
                @Override
                public void run() {
                    svc.autoStop();
                }
            };
            try {
                svc.timer.schedule(svc.autoStopTask, autoStopTime);
                Log.d(TAG, "AutoStop scheduled for " + autoStopTime/1000 + " seconds");
            } catch (IllegalStateException ignored) {} //when quitting app
        }
    }

    static void cancelAutoStop() {
        if (svc != null && svc.autoStopTask != null) {
            Log.d(TAG, "Cancelling AutoStop");
            svc.autoStopTask.cancel();
            svc.autoStopTask = null;
        }
    }

    private void autoStop() {
        if (!isAutoStopEnabled())
            return;
        Log.i(TAG, "Autostopping");
        stopSelf();
        LocalBroadcasts.closeAll(this);
        autoStopTask = null;
    }

    public void updateProgress() {
        //Do this on another thread as we don't want to block a sender or receiver thread
        try {
            executor.submit(this::updateNotification);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Rejected execution exception: " + e.getMessage());
        }
    }

    private Process launchLogcat() {
        File output = new File(getExternalFilesDir(null), "latest.log");
        Process process;
        String cmd = "logcat -f " + output.getAbsolutePath() + "\n";
        try {
            output.delete(); //Delete original file
            process = Runtime.getRuntime().exec(cmd);
            Log.d(TAG, "---- Logcat started ----");
        } catch (Exception e) {
            process = null;
            Log.e(TAG, "Failed to start logging to file", e);
        }
        return process;
    }

    private void listenOnNetworkChanges() {
        connMgr = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        assert connMgr != null;
        NetworkRequest nr = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                Log.d(TAG, "New network");
                networkAvailable = true;
                LocalBroadcasts.updateNetworkState(MainService.this);
                onNetworkChanged();
            }
            @Override
            public void onLost(@NonNull Network network) {
                Log.d(TAG, "Network lost");
                networkAvailable = false;
                LocalBroadcasts.updateNetworkState(MainService.this);
                onNetworkLost();
            }
            @Override
            public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
                Log.d(TAG, "Link properties changed");
                onNetworkChanged();
            }
        };
        apStateChangeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int apState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, 0);
                if (apState % 10 == WifiManager.WIFI_STATE_ENABLED) {
                    Log.d(TAG, "AP was enabled");
                    apOn = true;
                    LocalBroadcasts.updateNetworkState(MainService.this);
                    onNetworkChanged();
                } else if (apState % 10 == WifiManager.WIFI_STATE_DISABLED) {
                    Log.d(TAG, "AP was disabled");
                    apOn = false;
                    LocalBroadcasts.updateNetworkState(MainService.this);
                    onNetworkLost();
                }
            }
        };
        apOn = isHotspotOn(); // Manually get state, some devices don't fire broadcast when registered
        registerReceiver(apStateChangeReceiver, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));
        connMgr.registerNetworkCallback(nr, networkCallback);
    }

    private boolean isHotspotOn() {
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        try {
            final Method method = manager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true); //in the case of visibility change in future APIs
            return (Boolean) method.invoke(manager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get hotspot state", e);
        }
        return false;
    }

    public boolean gotNetwork() {
        return networkAvailable || apOn;
    }

    private void onNetworkLost() {
        if (!gotNetwork())
            currentIPInfo = null; //Rebind even if we reconnected to the same net
    }

    private void onNetworkChanged() {
        var newInfo = Utils.getIPAddress();
        if (newInfo == null) {
            Log.w(TAG, "Network changed, but we do not have an IP");
            currentIPInfo = null;
            return;
        }
        InetAddress newIP = newInfo.address;
        if (!newIP.equals(currentIPInfo == null ? null : currentIPInfo.address)) {
            Log.d(TAG, ":: Restarting. New IP: " + newIP);
            LocalBroadcasts.displayToast(this, getString(R.string.changed_network), 1);
            currentIPInfo = newInfo;
            // Regenerate cert
            Authenticator.getServerCertificate();
            // Restart server
            server.Stop(false); //Not async, so it finishes before we restart
            if (Authenticator.certException == null)
                server.Start();
            else Log.w(TAG, "No cert. Server not started.");
        }
    }

    String getCurrentIPStr() {
        return currentIPInfo == null ? null : currentIPInfo.address.getHostAddress();
    }

    private void updateNotification() {
        if (notifBuilder == null) {
            notifBuilder = new NotificationCompat.Builder(this, CHANNEL_PROGRESS);
            notifBuilder.setSmallIcon(R.drawable.ic_notification)
                    .setOngoing(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);
        }
        runningTransfers = 0;
        long bytesDone = 0;
        long bytesTotal = 0;
        long bytesPerSecond = 0;
        for (Remote r : remotes.values()) {
            for (Transfer t : r.transfers) {
                if (t.getStatus() == Transfer.Status.TRANSFERRING) {
                    runningTransfers++;
                    bytesDone += t.bytesTransferred;
                    bytesTotal += t.totalSize;
                    bytesPerSecond += t.bytesPerSecond;
                }
            }
        }
        int progress = (int)((float)bytesDone / bytesTotal * 1000f);
        if (runningTransfers > 0) {
            notifBuilder.setOngoing(true);
            notifBuilder.setProgress(1000, progress, false);
            notifBuilder.setContentTitle(String.format(Locale.getDefault(), getString(R.string.transfer_notification),
                    progress/10f, runningTransfers, Formatter.formatFileSize(this, bytesPerSecond)));
        } else {
            notifBuilder.setProgress(0, 0, false);
            notifBuilder.setContentTitle(getString(R.string.transfers_complete));
            notifBuilder.setOngoing(false);
            scheduleAutoStop();
            if (wakeLock.isHeld()) {
                Log.i(TAG, "Releasing transfer wake lock");
                wakeLock.release();
            }
        }
        if (runningTransfers > 0 || TransfersActivity.topmostRemote == null)
            notificationMgr.notify(PROGRESS_NOTIFICATION_ID, notifBuilder.build());
        else notificationMgr.cancel(PROGRESS_NOTIFICATION_ID);
    }

    private void pingRemotes() {
        try {
        for (Remote r : remotes.values()) {
            if ((r.api == 1) && (r.status == Remote.RemoteStatus.CONNECTED)) {
                r.ping();
            }
        }
        } catch (ConcurrentModificationException ignored) {}
    }

    private void autoReconnect() {
        if (!gotNetwork())
            return;
        try {
            for (Remote r : remotes.values()) {
                if ((r.status == Remote.RemoteStatus.DISCONNECTED || r.status == Remote.RemoteStatus.ERROR)
                        && r.serviceAvailable && !r.errorGroupCode) {
                    // Try reconnecting
                    Log.d(TAG, "Automatically reconnecting to " + r.hostname);
                    r.connect();
                }
            }
        } catch (ConcurrentModificationException ignored) {}
    }

    private Notification createForegroundNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, immutable);

        Intent stopIntent = new Intent(this, StopSvcReceiver.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent =
                PendingIntent.getBroadcast(this, 0, stopIntent, immutable);

        String notificationTitle = getString(R.string.warpinator_notification_title);
        String notificationButton = getString(R.string.warpinator_notification_button);
        return new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setContentTitle(notificationTitle)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .addAction(0, notificationButton, stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .setOngoing(true).build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.service_running);
            String description = getString(R.string.notification_channel_description);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_SERVICE, name, importance);
            channel.setDescription(description);

            CharSequence name2 = getString(R.string.incoming_transfer_channel);
            int importance2 = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel2 = new NotificationChannel(CHANNEL_INCOMING, name2, importance2);

            CharSequence name3 = getString(R.string.transfer_progress_channel);
            int importance3 = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel3 = new NotificationChannel(CHANNEL_PROGRESS, name3, importance3);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            assert notificationManager != null;
            notificationManager.createNotificationChannel(channel);
            notificationManager.createNotificationChannel(channel2);
            notificationManager.createNotificationChannel(channel3);
        }
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager)getApplicationContext()
                .getSystemService(android.content.Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("WarpMDNSLock");
            lock.setReferenceCounted(true);
            lock.acquire();
            Log.d(TAG, "Multicast lock acquired");
        }
    }

    private boolean isAutoStopEnabled() {
        if (prefs == null)
            return false;
        return prefs.getBoolean("autoStop", true) && !prefs.getBoolean("bootStart", false);
    }

    public void notifyDeviceCountUpdate() {
        int count = 0;
        Collection<Remote> remotes = MainService.remotes.values();
        for (Remote remote : remotes) {
            if (remote.status == Remote.RemoteStatus.CONNECTED) {
                count++;
            }
        }

        for (WeakReference<RemoteCountObserver> observer : remoteCountObservers) {
            RemoteCountObserver obs = observer.get();
            if (obs != null) {
                obs.onDeviceCountChange(count);
            }
        }
    }

    public DisposableRemoteCountObserver observeDeviceCount(RemoteCountObserver observer) {
        remoteCountObservers.add(new WeakReference<>(observer));
        observer.onDeviceCountChange(remotes.size());

        return () -> {
            remoteCountObservers.remove(new WeakReference<>(observer));
        };
    }

    public interface RemoteCountObserver {
        void onDeviceCountChange(int newCount);
    }

    public interface DisposableRemoteCountObserver {
        void dispose();
    }

    public static class StopSvcReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                context.stopService(new Intent(context, MainService.class));
                LocalBroadcasts.closeAll(context);
            }
        }
    }
}
