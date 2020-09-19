package slowscript.warpinator;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.conscrypt.Conscrypt;

import java.security.Security;
import java.util.LinkedHashMap;

public class MainActivity extends AppCompatActivity {

    private String TAG = "MAIN";

    public static MainActivity ctx;
    public TransfersActivity transfersView;

    private Server server;

    public LinkedHashMap<String, Remote> remotes = new LinkedHashMap<>();

    RecyclerView recyclerView;
    RemotesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Security.insertProviderAt(Conscrypt.newProvider(), 1);

        ctx = this;

        recyclerView = findViewById(R.id.recyclerView);
        adapter = new RemotesAdapter(this);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recyclerView.post(() -> { //Run when UI is ready
            //TODO: Should run inside of a foreground service
            server = new Server(42000, ctx);
            server.Start();
        });

        if (!checkWriteExternalPermission())
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        server.Stop();
        for (Remote r : remotes.values()) {
            r.disconnect();
        }
        remotes.clear();
    }

    public void addRemote(Remote remote) {
        //Add to remotes list
        remotes.put(remote.uuid, remote);
        //Add to GUI
        updateRemoteList();
        //Connect to it
        remote.connect();
    }

    public void removeRemote(String uuid) {
        Remote r = remotes.get(uuid);
        //Disconnect
        r.disconnect();
        //Remove from GUI
        updateRemoteList();
        //Remove
        remotes.remove(uuid);
    }

    public void updateRemoteList() {
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private boolean checkWriteExternalPermission()
    {
        String permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        int res = checkCallingOrSelfPermission(permission);
        return (res == PackageManager.PERMISSION_GRANTED);
    }
}
