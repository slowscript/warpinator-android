package slowscript.warpinator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;

public class ShareActivity extends AppCompatActivity {

    static final String TAG = "Share";

    RecyclerView recyclerView;
    LinearLayout layoutNotFound;
    RemotesAdapter adapter;
    TextView txtError, txtNoNetwork, txtSharing;
    BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_share);
        setTitle(R.string.title_activity_share);
        receiver = newBroadcastReceiver();

        //Get uris to send
        Intent intent = getIntent();
        ArrayList<Uri> uris;
        if(Intent.ACTION_SEND.equals(intent.getAction())) {
            uris = new ArrayList<>();
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null)
                uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        } else {
            Toast.makeText(this, R.string.unsupported_intent, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (uris == null || uris.size() < 1) {
            Log.d(TAG, "Nothing to share");
            Toast.makeText(this, R.string.nothing_to_share, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Log.d(TAG, "Sharing " + uris.size() + " files");
        for (Uri u : uris) {
            Log.v(TAG, u.toString());
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        //Start service (if not running)
        if (!Utils.isMyServiceRunning(this, MainService.class))
            startMainService();

        //Set up UI
        recyclerView = findViewById(R.id.recyclerView);
        layoutNotFound = findViewById(R.id.layoutNotFound);
        txtError = findViewById(R.id.txtError);
        txtNoNetwork = findViewById(R.id.txtNoNetwork);
        txtSharing = findViewById(R.id.txtSharing);
        txtSharing.setMovementMethod(new ScrollingMovementMethod());
        String sharedFilesList = getString(R.string.files_being_sent);
        for (int i = 0; i < uris.size(); i++) {
            sharedFilesList += "\n " + Utils.getNameFromUri(this, uris.get(i));
            if (i >= 29) {
                sharedFilesList += "\n + " + (uris.size() - 30);
                break;
            }
        }
        txtSharing.setText(sharedFilesList);
        adapter = new RemotesAdapter(this) {
            boolean sent = false;
            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                Remote remote = MainService.remotes.get(MainService.remotesOrder.get(position));
                setupViewHolder(holder, remote);

                //Send to selected remote
                holder.cardView.setOnClickListener((view) -> {
                	if (remote.status != Remote.RemoteStatus.CONNECTED || sent)
                		return;
                    Transfer t = new Transfer();
                    t.uris = uris;
                    t.remoteUUID = remote.uuid;

                    remote.addTransfer(t);
                    t.setStatus(Transfer.Status.INITIALIZING);
                    new Thread(()->{
                        t.prepareSend(false);
                        remote.startSendTransfer(t);
                    }).start();
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
        String dlDir = prefs.getString("downloadDir", "");
        boolean docFileExists = false;
        try {
            docFileExists = DocumentFile.fromTreeUri(this, Uri.parse(dlDir)).exists();
        } catch (Exception ignored) {}
        if (dlDir.equals("") || !(new File(dlDir).exists() || docFileExists)) {
            if (!MainActivity.trySetDefaultDirectory(this))
                MainActivity.askForDirectoryAccess(this);
        }
    }

    void startMainService() {
        startService(new Intent(this, MainService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (adapter == null) // If onCreate did not finish (nothing to share)
            return;
        updateRemotes();
        updateNetworkStateUI();
        IntentFilter f = new IntentFilter(LocalBroadcasts.ACTION_UPDATE_REMOTES);
        f.addAction(LocalBroadcasts.ACTION_UPDATE_NETWORK);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_MESSAGE);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_TOAST);
        f.addAction(LocalBroadcasts.ACTION_CLOSE_ALL);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
    }

    private BroadcastReceiver newBroadcastReceiver() {
        Context ctx = this;
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null)
                    return;
                switch (action) {
                    case LocalBroadcasts.ACTION_UPDATE_REMOTES:
                        updateRemotes();
                        break;
                    case LocalBroadcasts.ACTION_UPDATE_NETWORK:
                        updateNetworkStateUI();
                        break;
                    case LocalBroadcasts.ACTION_DISPLAY_MESSAGE:
                        String title = intent.getStringExtra("title");
                        String msg = intent.getStringExtra("msg");
                        Utils.displayMessage(ctx, title, msg);
                        break;
                    case LocalBroadcasts.ACTION_DISPLAY_TOAST:
                        msg = intent.getStringExtra("msg");
                        int length = intent.getIntExtra("length", 0);
                        Toast.makeText(ctx, msg, length).show();
                        break;
                    case LocalBroadcasts.ACTION_CLOSE_ALL:
                        finishAffinity();
                        break;
                }
            }
        };
    }

    private void updateRemotes() {
        recyclerView.post(() -> {
            adapter.notifyDataSetChanged();
            layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
        });
    }

    private void updateNetworkStateUI() {
        runOnUiThread(() -> {
            if (MainService.svc != null)
                txtNoNetwork.setVisibility(MainService.svc.gotNetwork() ? View.GONE : View.VISIBLE);
            if (Server.current != null)
                txtError.setVisibility(Server.current.running ? View.GONE : View.VISIBLE);
        });
    }
}
