package slowscript.warpinator;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.conscrypt.Conscrypt;

import java.security.Security;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MAIN";
    private final String helpUrl = "https://github.com/slowscript/warpinator-android/blob/master/connection-issues.md";

    RecyclerView recyclerView;
    RemotesAdapter adapter;
    LinearLayout layoutNotFound;
    TextView txtError, txtNoNetwork;
    BroadcastReceiver receiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Security.insertProviderAt(Conscrypt.newProvider(), 1);

        receiver = newBroadcastReceiver();
        recyclerView = findViewById(R.id.recyclerView);
        adapter = new RemotesAdapter(this);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        layoutNotFound = findViewById(R.id.layoutNotFound);
        txtError = findViewById(R.id.txtError);
        txtNoNetwork = findViewById(R.id.txtNoNetwork);
        Button btnHelp = findViewById(R.id.btnHelp);
        btnHelp.setOnClickListener((l) -> {
            Intent helpIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl));
            startActivity(helpIntent);
        });

        //initializes theme based on preferences
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        switch (prefs.getString("theme_setting", "sysDefault")){
            case "sysDefault":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                break;
            case "lightTheme":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case "darkTheme":
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
        }

        String dlDir = prefs.getString("downloadDir", "");
        if (dlDir.equals("") || !DocumentFile.fromTreeUri(this, Uri.parse(dlDir)).exists()) {
            askForDirectoryAccess(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        recyclerView.post(() -> { //Run when UI is ready
            if (!Utils.isMyServiceRunning(this, MainService.class))
                startMainService();
        });
        updateRemoteList();

        IntentFilter f = new IntentFilter();
        f.addAction(LocalBroadcasts.ACTION_UPDATE_REMOTES);
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

    void startMainService() {
        startService(new Intent(this, MainService.class));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            case R.id.about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;
            case R.id.menu_quit:
                Log.i(TAG, "Quitting");
                stopService(new Intent(this, MainService.class));
                finishAffinity();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void updateRemoteList() {
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
            if (MainService.svc != null)
                txtNoNetwork.setVisibility(MainService.svc.gotNetwork() ? View.GONE : View.VISIBLE);
            if (Server.current != null)
                txtError.setVisibility(Server.current.running ? View.GONE : View.VISIBLE);
        });
    }

    private BroadcastReceiver newBroadcastReceiver()
    {
        Context ctx = this;
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case LocalBroadcasts.ACTION_UPDATE_REMOTES:
                        updateRemoteList();
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

    public static void askForDirectoryAccess(Activity a) {
        new AlertDialog.Builder(a)
            .setTitle(R.string.download_dir_settings_title)
            .setMessage(R.string.please_select_download_dir)
            .setPositiveButton(android.R.string.ok, (b,c) -> {
                Intent intent = new Intent(a, SettingsActivity.class);
                intent.putExtra("pickDir", true);
                a.startActivity(intent);
            })
            .show();
    }
}
