package slowscript.warpinator;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;

public class ShareActivity extends AppCompatActivity {

    static final String TAG = "Share";

    public static ShareActivity current;

    RecyclerView recyclerView;
    LinearLayout layoutNotFound;
    RemotesAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;
        setContentView(R.layout.activity_share);
        setTitle(R.string.title_activity_share);

        //Get uris to send
        Intent intent = getIntent();
        ArrayList<Uri> uris;
        if(Intent.ACTION_SEND.equals(intent.getAction())) {
            uris = new ArrayList<>();
            uris.add((Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM));
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        } else {
            Toast.makeText(this, R.string.unsupported_intent, Toast.LENGTH_LONG).show();
            return;
        }

        if (uris == null || uris.size() < 1) {
            Log.d(TAG, "Nothing to share");
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_LONG).show();
            return;
        }
        Log.d(TAG, "Sharing " + uris.size() + " files");
        for (Uri u : uris)
            Log.v(TAG, u.toString());

        //Start service (if not running)
        if (!Utils.isMyServiceRunning(this, MainService.class))
            startMainService();

        //Set up UI
        recyclerView = findViewById(R.id.recyclerView);
        layoutNotFound = findViewById(R.id.layoutNotFound);
        adapter = new RemotesAdapter(this) {
            boolean sent = false;
            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                Remote remote = (Remote) MainService.remotes.values().toArray()[position];
                setupViewHolder(holder, remote);

                //Send to selected remote
                holder.cardView.setOnClickListener((view) -> {
                	if (remote.status != Remote.RemoteStatus.CONNECTED || sent)
                		return;
                    Transfer t = new Transfer();
                    t.uris = uris;
                    t.remoteUUID = remote.uuid;
                    t.prepareSend();

                    remote.transfers.add(t);
                    t.privId = remote.transfers.size()-1;
                    remote.startSendTransfer(t);

                    Intent i = new Intent(app, TransfersActivity.class);
                    i.putExtra("remote", remote.uuid);
                    i.putExtra("shareMode", true);
                    app.startActivity(i);
                    sent = true;
                });
            }
        };
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getString("downloadDir", "").equals("")) {
            MainActivity.askForDirectoryAccess(this);
        }
    }

    void startMainService() {
        if (!Utils.isConnectedToWiFiOrEthernet(this) && !Utils.isHotspotOn(this)) {
            Utils.displayMessage(this, getString(R.string.connection_error), getString(R.string.not_connected_to_wifi));
            return;
        }
        startService(new Intent(this, MainService.class));
    }

    @Override
    protected void onDestroy() {
        current = null;
        super.onDestroy();
    }

    public void updateRemotes() {
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
        });
    }
}
