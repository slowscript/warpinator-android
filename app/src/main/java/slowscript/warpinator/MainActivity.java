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
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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

public class MainActivity extends AppCompatActivity implements ActivityCompat.OnRequestPermissionsResultCallback {

    private static final String TAG = "MAIN";
    private static final String helpUrl = "https://slowscript.xyz/warpinator-android/connection-issues/";

    RecyclerView recyclerView;
    RemotesAdapter adapter;
    LinearLayout layoutNotFound;
    TextView txtError, txtNoNetwork, txtOutgroup;
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
        txtOutgroup = findViewById(R.id.txtOutgroup);

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
        updateNetworkStateUI();

        IntentFilter f = new IntentFilter();
        f.addAction(LocalBroadcasts.ACTION_UPDATE_REMOTES);
        f.addAction(LocalBroadcasts.ACTION_UPDATE_NETWORK);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_MESSAGE);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_TOAST);
        f.addAction(LocalBroadcasts.ACTION_CLOSE_ALL);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Log.d(TAG, "recv action " + intent.getAction() + " -- " + intent.getData());
            String host = intent.getData().getAuthority();
            Server.current.registerWithHost(host);
        }
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
        } else if (itemID == R.id.manual_connect) {
            LayoutInflater inflater = LayoutInflater.from(this);
            View v = inflater.inflate(R.layout.dialog_manual_connect, null);
            AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(v)
                    .setPositiveButton(R.string.initiate_connection, (o,w) -> initiateConnection())
                    .setNeutralButton(android.R.string.cancel, null)
                    .create();
            String host = MainService.svc.lastIP + ":" + Server.current.authPort;
            ((TextView)v.findViewById(R.id.txtHost)).setText(host);
            ((ImageView)v.findViewById(R.id.imgQR)).setImageBitmap(Utils.getQRCodeBitmap(host));
            dialog.show();
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

    void initiateConnection() {
        FrameLayout layout = new FrameLayout(this);
        layout.setPadding(16,16,16,16);
        EditText editText = new EditText(this);
        editText.setSingleLine();
        layout.addView(editText);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Enter IP and port")
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (a,b)->{
                    String host = editText.getText().toString();
                    Log.d(TAG, "initiateConnection: " + host);
                    Server.current.registerWithHost(host);
                })
                .show();
    }

    private void updateRemoteList() {
        recyclerView.post(() -> {
            adapter.notifyDataSetChanged();
            layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
            int numOutgroup = 0;
            for (Remote r : MainService.remotes.values())
                if (r.errorGroupCode)
                    numOutgroup++;
            txtOutgroup.setText(numOutgroup > 0 ? getString(R.string.devices_outside_group, numOutgroup) : "");
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

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && requestCode == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Got storage permission");
            if (!trySetDefaultDirectory(this))
                askForDirectoryAccess(this);
        } else {
            Log.d(TAG, "Storage permission denied");
            askForDirectoryAccess(this);
        }
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
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q && !checkWriteExternalPermission(a)) {
            ActivityCompat.requestPermissions(a, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return true; //Don't fallback to SAF, wait for permission being granted
        }

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
