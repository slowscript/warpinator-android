package slowscript.warpinator;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.format.Formatter;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.io.File;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
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
    static long pingTime = 10_000;

    public Server server;
    public static LinkedHashMap<String, Remote> remotes = new LinkedHashMap<>();
    public TransfersActivity transfersView;
    public SharedPreferences prefs;
    public int runningTransfers = 0;
    int notifId = 1300;

    public static MainService svc;
    public NotificationManagerCompat notificationMgr;
    NotificationCompat.Builder notifBuilder = null;
    Timer pingTimer;
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Process logcatProcess;
    WifiManager.MulticastLock lock;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        svc = this;
        notificationMgr = NotificationManagerCompat.from(this);
        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("debugLog", false))
            logcatProcess = launchLogcat();

        Authenticator.getServerCertificate(); //Generate cert on start if doesn't exist
        if (Authenticator.certException != null) {
            if (MainActivity.current != null) {
            Utils.displayMessage(MainActivity.current, "Failed to create certificate",
                    "A likely reason for this is that your IP address could not be obtained. " +
                    "Please make sure you are connected to WiFi, then restart the app.\n" +
                    "\nAvailable interfaces:\n" + Utils.dumpInterfaces() +
                    "\nException: " + Authenticator.certException.toString());
            }
            return START_NOT_STICKY;
        }

        android.net.wifi.WifiManager wifi =
                (android.net.wifi.WifiManager)
                        getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        if (wifi != null) {
            lock = wifi.createMulticastLock("WarpMDNSLock");
            lock.setReferenceCounted(true);
            lock.acquire();
            Log.d(TAG, "Multicast lock acquired");
        }

        Log.d(TAG, "Service starting...");
        server = new Server(this);
        server.Start();

        pingTimer = new Timer();
        pingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                pingRemotes();
            }
        }, 5L, pingTime);

        // Notification related stuff
        createNotificationChannels();
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openIntent, 0);

        Intent stopIntent = new Intent(this, StopSvcReceiver.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPendingIntent =
                PendingIntent.getBroadcast(this, 0, stopIntent, 0);

        if(!prefs.getBoolean("background", true))
            return START_STICKY;

        String notificationTitle = getString(R.string.warpinator_notification_title);
        String notificationButton = getString(R.string.warpinator_notification_button);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_SERVICE)
                .setContentTitle(notificationTitle)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pendingIntent)
                .addAction(0, notificationButton, stopPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .setOngoing(true).build();

        startForeground(SVC_NOTIFICATION_ID, notification);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        super.onDestroy();
    }

    public void stopServer () {
        if (server == null) //I have no idea how this can happen
            return;
        server.Stop();
        for (Remote r : remotes.values()) {
            if (r.status == Remote.RemoteStatus.CONNECTED)
                r.disconnect();
        }
        notificationMgr.cancelAll();
        remotes.clear();
        executor.shutdown();
        pingTimer.cancel();
        if (lock != null)
            lock.release();
    }

    public void updateProgress() {
        //Do this on another thread as we don't want to block a sender or receiver thread
        try {
            executor.submit(this::updateNotification);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Rejected execution exception: " + e.getMessage());
        }
    }

    public Process launchLogcat() {
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

    void updateNotification() {
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
        }
        if (runningTransfers > 0 || transfersView == null || !transfersView.isTopmost)
            notificationMgr.notify(PROGRESS_NOTIFICATION_ID, notifBuilder.build());
        else notificationMgr.cancel(PROGRESS_NOTIFICATION_ID);
    }

    void pingRemotes() {
        try {
        for (Remote r : remotes.values()) {
            if (r.status == Remote.RemoteStatus.CONNECTED) {
                r.ping();
            }
        }
        } catch (ConcurrentModificationException ignored) {}
    }

    public void addRemote(Remote remote) {
        //Add to remotes list
        remotes.put(remote.uuid, remote);
        //Connect to it
        remote.connect();
    }

    public void removeRemote(String uuid) {
        Remote r = remotes.get(uuid);
        //Disconnect
        r.disconnect();
        //Remove from GUI
        MainActivity.current.updateRemoteList();
        //Remove
        remotes.remove(uuid);
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

    public static class StopSvcReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_STOP.equals(intent.getAction())) {
                context.stopService(new Intent(context, MainService.class));
                if (MainActivity.current != null)
                    MainActivity.current.finishAffinity();
            }
        }
    }
}
