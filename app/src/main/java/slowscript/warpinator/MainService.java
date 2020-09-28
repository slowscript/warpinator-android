package slowscript.warpinator;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Timer;
import java.util.TimerTask;

public class MainService extends Service {

    static String TAG = "SERVICE";
    static int PORT = 42000;
    static long pingTime = 10_000;

    public static Server server;
    public static LinkedHashMap<String, Remote> remotes = new LinkedHashMap<>();
    public TransfersActivity transfersView;

    public static MainService svc;
    Timer pingTimer;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        svc = this;

        Authenticator.getServerCertificate(); //Generate cert on start if doesn't exist
        Utils.getSaveDir().mkdirs();

        Log.d(TAG, "Service starting...");
        server = new Server(PORT, this);
        server.Start();

        pingTimer = new Timer();
        pingTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                pingRemotes();
            }
        }, 0L, pingTime);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServer();
    }

    public void stopServer () {
        server.Stop();
        for (Remote r : remotes.values()) {
            r.disconnect();
        }
        remotes.clear();
    }

    void pingRemotes() {
        for (Remote r : remotes.values()) {
            if (r.status == Remote.RemoteStatus.CONNECTED) {
                r.ping();
            }
        }
    }

    public void addRemote(Remote remote) {
        //Add to remotes list
        remotes.put(remote.uuid, remote);
        //Add to GUI
        MainActivity.current.updateRemoteList();
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
}
