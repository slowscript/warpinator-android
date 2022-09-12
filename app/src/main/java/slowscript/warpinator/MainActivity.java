package slowscript.warpinator;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.conscrypt.Conscrypt;

import java.io.File;
import java.security.Security;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MAIN";
    private static final String helpUrl = "https://github.com/slowscript/warpinator-android/blob/master/connection-issues.md";

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
        boolean docFileExists = false;
        try {
            docFileExists = DocumentFile.fromTreeUri(this, Uri.parse(dlDir)).exists();
        } catch (Exception ignored) {}
        if (dlDir.equals("") || !(new File(dlDir).exists() || docFileExists)) {
            if (!trySetDefaultDirectory(this))
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
        try {
	        startService(new Intent(this, MainService.class));
        } catch (Exception e) {
        	Log.e(TAG, "Could not start service", e);
        	Toast.makeText(this, "Could not start service: " + e.toString(), Toast.LENGTH_LONG).show(); 
        }
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
        int itemID = item.getItemId();
        if (itemID == R.id.settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else if (itemID == R.id.conn_issues) {
            Intent helpIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl));
            startActivity(helpIntent);
        } else if (itemID == R.id.reannounce) {
            if (Server.current != null && Server.current.running)
                Server.current.reannounce();
            else Toast.makeText(this, R.string.error_service_not_running, Toast.LENGTH_SHORT);
        } else if (itemID == R.id.rescan) {
            if (Server.current != null)
                Server.current.rescan();
            else Toast.makeText(this, R.string.error_service_not_running, Toast.LENGTH_SHORT);
        } else if (itemID == R.id.about) {
            startActivity(new Intent(this, AboutActivity.class));
        } else if (itemID == R.id.menu_quit) {
            Log.i(TAG, "Quitting");
            stopService(new Intent(this, MainService.class));
            finishAffinity();
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private void updateRemoteList() {
        recyclerView.post(() -> {
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
        new MaterialAlertDialogBuilder(a)
                .setTitle(R.string.download_dir_settings_title)
                .setMessage(R.string.please_select_download_dir)
                .setPositiveButton(android.R.string.ok, (b, c) -> {
                    Intent intent = new Intent(a, SettingsActivity.class);
                    intent.putExtra("pickDir", true);
                    a.startActivity(intent);
                })
                .show();
    }

    public static boolean trySetDefaultDirectory(Activity a) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !checkWriteExternalPermission(a))
            ActivityCompat.requestPermissions(a, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);

        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Warpinator");
        Log.d(TAG, "Trying to set default directory: " + dir.getAbsolutePath());
        boolean res;
        if (!dir.exists())
            res = dir.mkdirs();
        else res = true;
        if (res)
            PreferenceManager.getDefaultSharedPreferences(a).edit().putString("downloadDir", dir.getAbsolutePath()).apply();
        Log.d(TAG, "Directory set " + (res ? "successfully" : "failed"));
        return res;
    }

    private static boolean checkWriteExternalPermission(Activity a) {
        String permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        int res = a.checkCallingOrSelfPermission(permission);
        return (res == PackageManager.PERMISSION_GRANTED);
    }
}
