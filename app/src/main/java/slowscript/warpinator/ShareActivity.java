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

        //Start service (if not running)
        if (!Utils.isMyServiceRunning(this, MainService.class))
            startService(new Intent(this, MainService.class));

        //Set up UI
        recyclerView = findViewById(R.id.recyclerView);
        layoutNotFound = findViewById(R.id.layoutNotFound);
        adapter = new RemotesAdapter(this) {
            @Override
            public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
                Remote remote = (Remote) MainService.remotes.values().toArray()[position];
                setupViewHolder(holder, remote);

                //Send to selected remote
                holder.cardView.setOnClickListener((view) -> {
                    Transfer t = new Transfer();
                    t.uris = uris;
                    t.remoteUUID = remote.uuid;
                    t.prepareSend();

                    remote.transfers.add(t);
                    t.privId = remote.transfers.size()-1;
                    remote.startSendTransfer(t);

                    Intent i = new Intent(app, TransfersActivity.class);
                    i.putExtra("remote", remote.uuid);
                    app.startActivity(i);
                    finish();
                });
            }
        };
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getString("downloadDir", "").equals("")) {
            MainActivity.askForDirectoryAccess(this);
        }
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
