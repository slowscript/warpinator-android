package slowscript.warpinator;

import android.app.Activity;
import android.content.Intent;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.conscrypt.Conscrypt;

import java.security.Security;

public class MainActivity extends AppCompatActivity {

    private final String TAG = "MAIN";
    private final String helpUrl = "https://github.com/slowscript/warpinator-android/blob/master/connection-issues.md";

    static MainActivity current;
    RecyclerView recyclerView;
    RemotesAdapter adapter;
    LinearLayout layoutNotFound;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Security.insertProviderAt(Conscrypt.newProvider(), 1);

        current = this;

        recyclerView = findViewById(R.id.recyclerView);
        adapter = new RemotesAdapter(this);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        layoutNotFound = findViewById(R.id.layoutNotFound);
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
    }

    void startMainService() {
        if (!Utils.isConnectedToWiFiOrEthernet(this) && !Utils.isHotspotOn(this)) {
            Utils.displayMessage(this, getString(R.string.connection_error), getString(R.string.not_connected_to_wifi));
            return;
        }
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

    public void updateRemoteList() {
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            layoutNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
        });
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
