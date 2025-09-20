package slowscript.warpinator;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.util.Log;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Objects;

import slowscript.warpinator.preferences.ListPreference;
import slowscript.warpinator.preferences.ResetablePreference;

public class SettingsActivity extends AppCompatActivity {

    SettingsFragment fragment;
    boolean pickDir;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        Utils.setEdgeToEdge(getWindow());
        fragment = new SettingsFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .commit();
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        Utils.setToolbarInsets(toolbar);
        Utils.setContentInsets(findViewById(R.id.settings));
        pickDir = getIntent().getBooleanExtra("pickDir", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if(pickDir) {
            pickDir = false; //Only once
            fragment.pickDirOnStart = true;
            fragment.pickDirectory();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final int CHOOSE_ROOT_REQ_CODE = 10;
        private static final String TAG = "Settings";
        private static final String INIT_URI = "content://com.android.externalstorage.documents/document/primary:";
                
        private static final String DOWNLOAD_DIR_PREF = "downloadDir";
        private static final String PORT_PREF = "port";
        private static final String AUTH_PORT_PREF = "authPort";
        private static final String GROUPCODE_PREF = "groupCode";
        private static final String THEME_PREF = "theme_setting";
        private static final String PROFILE_PREF = "profile";
        private static final String DEBUGLOG_PREF = "debugLog";
        private static final String NETIFACE_PREF = "networkInterface";
        public boolean pickDirOnStart = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            EditTextPreference gcPref = findPreference(GROUPCODE_PREF);
            SwitchPreferenceCompat debugPref = findPreference(DEBUGLOG_PREF);
            ResetablePreference dlPref = findPreference(DOWNLOAD_DIR_PREF);
            Preference themePref = findPreference(THEME_PREF);
            Preference profilePref = findPreference(PROFILE_PREF);
            ListPreference networkIfacePref = findPreference(NETIFACE_PREF);
            EditTextPreference portPref = findPreference(PORT_PREF);
            EditTextPreference authPortPref = findPreference(AUTH_PORT_PREF);
            portPref.setOnBindEditTextListener((edit)-> edit.setInputType(InputType.TYPE_CLASS_NUMBER));
            authPortPref.setOnBindEditTextListener((edit)-> edit.setInputType(InputType.TYPE_CLASS_NUMBER));

            //Warn about preference not being applied immediately
            for (Preference pref : new Preference[]{gcPref, debugPref}) {
                pref.setOnPreferenceChangeListener((p,v) -> {
                    Toast.makeText(getContext(), R.string.requires_restart_warning, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            //Ensure port number is correct
            Preference.OnPreferenceChangeListener onPortChanged = (p, val) -> {
                int port = Integer.parseInt((String)val);
                if (port > 65535 || port < 1024) {
                    Toast.makeText(getContext(), R.string.port_range_warning, Toast.LENGTH_LONG).show();
                    return false;
                }
                Toast.makeText(getContext(), R.string.requires_restart_warning, Toast.LENGTH_SHORT).show();
                return true;
            };
            portPref.setOnPreferenceChangeListener(onPortChanged);
            authPortPref.setOnPreferenceChangeListener(onPortChanged);
            //Change theme based on the new value
            themePref.setOnPreferenceChangeListener((preference, newValue) -> {
                switch (newValue.toString()){
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
                return true;
            });

            String dlDest = getPreferenceManager().getSharedPreferences().getString(DOWNLOAD_DIR_PREF, "");
            dlPref.setSummary(Uri.parse(dlDest).getPath());
            dlPref.setOnPreferenceClickListener((p)->{
                pickDirectory();
                return true;
            });
            dlPref.setOnResetListener((v)->{
                if (MainActivity.trySetDefaultDirectory(getActivity())) {
                    dlPref.setSummary(getPreferenceManager().getSharedPreferences().getString(DOWNLOAD_DIR_PREF, ""));
                    dlPref.setResetEnabled(false);
                }
            });
            dlPref.setResetEnabled(dlDest.startsWith("content"));

            var ifaces = Utils.getNetworkInterfaces();
            if (ifaces == null) ifaces = new String[] {"Failed to get network interfaces"};
            String[] newIfaces = new String[ifaces.length+1];
            newIfaces[0] = Server.NETIFACE_AUTO;
            System.arraycopy(ifaces, 0, newIfaces, 1, ifaces.length);
            networkIfacePref.setEntries(newIfaces);
            networkIfacePref.setEntryValues(newIfaces);
            String curIface = getPreferenceManager().getSharedPreferences().getString(NETIFACE_PREF, Server.NETIFACE_AUTO);
            networkIfacePref.setSummary(Server.NETIFACE_AUTO.equals(curIface) ? curIface :
                    getString(R.string.network_interface_settings_summary, curIface));
            networkIfacePref.setOnPreferenceChangeListener((pre, val) -> {
                pre.setSummary(Server.NETIFACE_AUTO.equals(val) ? (String)val :
                        getString(R.string.network_interface_settings_summary, val));
                Toast.makeText(getContext(), R.string.requires_restart_warning, Toast.LENGTH_SHORT).show();
                return true;
            });

            PreferenceScreen screen = getPreferenceScreen();
            for (int i = 0; i < screen.getPreferenceCount(); i++) {
                PreferenceGroup group = (PreferenceGroup) screen.getPreference(i);
                group.setIconSpaceReserved(false);
                for (int j = 0; j < group.getPreferenceCount(); j++)
                    group.getPreference(j).setIconSpaceReserved(false);
            }
        }

        public void pickDirectory() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(INIT_URI));
            try {
                startActivityForResult(intent, CHOOSE_ROOT_REQ_CODE);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(getContext(), R.string.required_dialog_not_found, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == CHOOSE_ROOT_REQ_CODE) {
                if (data == null)
                    return;
                Uri uri = data.getData();
                //Validate URI
                Log.d(TAG, "Selected directory: " + uri);
                if (uri == null || !Objects.equals(uri.getAuthority(), "com.android.externalstorage.documents")) {
                    Toast.makeText(getContext(), R.string.unsupported_provider, Toast.LENGTH_LONG).show();
                    return;
                }
                ResetablePreference dlPref = findPreference(DOWNLOAD_DIR_PREF);
                dlPref.setSummary(uri.getPath());
                dlPref.setResetEnabled(true);
                getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getPreferenceManager().getSharedPreferences().edit()
                        .putString(DOWNLOAD_DIR_PREF, uri.toString())
                        .apply();

                // Close activity if started only for initial dl directory selection
                if (pickDirOnStart) {
                    pickDirOnStart = false;
                    getActivity().finish();
                }
                else Toast.makeText(getContext(), R.string.warning_lastmod, Toast.LENGTH_LONG).show();
            }
        }
    }
}
