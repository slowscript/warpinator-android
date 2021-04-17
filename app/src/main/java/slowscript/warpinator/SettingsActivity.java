package slowscript.warpinator;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
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
import androidx.preference.SwitchPreferenceCompat;

public class SettingsActivity extends AppCompatActivity {

    SettingsFragment fragment;
    boolean pickDir;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        fragment = new SettingsFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, fragment)
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
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
        private static final String GROUPCODE_PREF = "groupCode";
        private static final String BACKGROUND_PREF = "background";
        private static final String THEME_PREF = "theme_setting";
        private static final String PROFILE_PREF = "profile";
        public boolean pickDirOnStart = false;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            EditTextPreference gcPref = findPreference(GROUPCODE_PREF);
            SwitchPreferenceCompat bgPref = findPreference(BACKGROUND_PREF);
            Preference dlPref = findPreference(DOWNLOAD_DIR_PREF);
            Preference themePref = findPreference(THEME_PREF);
            Preference profilePref = findPreference(PROFILE_PREF);
            EditTextPreference portPref = findPreference(PORT_PREF);
            portPref.setOnBindEditTextListener((edit)-> edit.setInputType(InputType.TYPE_CLASS_NUMBER));

            //Warn about preference not being applied immediately
            for (Preference pref : new Preference[]{gcPref, bgPref}) {
                pref.setOnPreferenceChangeListener((p,v) -> {
                    Toast.makeText(getContext(), R.string.requires_restart_warning, Toast.LENGTH_SHORT).show();
                    return true;
                });
            }
            //Ensure port number is correct
            portPref.setOnPreferenceChangeListener((p, val) -> {
                int port = Integer.parseInt((String)val);
                if (port > 65535 || port < 1024) {
                    Toast.makeText(getContext(), R.string.port_range_warning, Toast.LENGTH_LONG).show();
                    return false;
                }
                Toast.makeText(getContext(), R.string.requires_restart_warning, Toast.LENGTH_SHORT).show();
                return true;
            });
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

            dlPref.setOnPreferenceClickListener((p)->{
                pickDirectory();
                return true;
            });
            profilePref.setOnPreferenceClickListener((p)->{
                Intent i = new Intent(getContext(), ProfileChooser.class);
                startActivity(i);
                return true;
            });
        }

        public void pickDirectory() {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(INIT_URI));

            startActivityForResult(intent, CHOOSE_ROOT_REQ_CODE);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == CHOOSE_ROOT_REQ_CODE && resultCode == RESULT_OK) {
                if (data == null)
                    return;
                Uri uri = data.getData();
                //Validate URI
                Log.d(TAG, "Uri authority: " + uri.getAuthority());
                if (!uri.getAuthority().equals("com.android.externalstorage.documents")) {
                    Toast.makeText(getContext(), R.string.unsupported_provider, Toast.LENGTH_LONG).show();
                    return;
                }
                getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getPreferenceManager().getSharedPreferences().edit()
                        .putString(DOWNLOAD_DIR_PREF, uri.toString())
                        .apply();

                // Close activity if started only for initial dl directory selection
                if (pickDirOnStart) {
                    pickDirOnStart = false;
                    getActivity().finish();
                }
            }
        }
    }
}
