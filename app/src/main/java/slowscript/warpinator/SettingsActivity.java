package slowscript.warpinator;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.settings, new SettingsFragment())
                .commit();
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            // Respond to the action bar's Up/Home button
            case android.R.id.home:
                super.onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private static final int CHOOSE_ROOT_REQ_CODE = 10;
        private static final String DOWNLOAD_DIR_PREF = "downloadDir";

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);

            EditTextPreference pref = findPreference("port");
            if (pref != null) {
                pref.setOnBindEditTextListener((edit)-> edit.setInputType(InputType.TYPE_CLASS_NUMBER));
            }

            //gives back feedback on change
            EditTextPreference[] prefs = {findPreference("displayName"), findPreference("picture"), findPreference("downloadDir"), findPreference("groupcode"), findPreference("port")};

            for (EditTextPreference editTextPreference : prefs) {
                assert editTextPreference != null;
                editTextPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        Toast.makeText(getContext(), getString(R.string.setting_changed_successfully_toast, preference.toString()), Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });
            }

            Preference dlPref = findPreference(DOWNLOAD_DIR_PREF);
            dlPref.setOnPreferenceClickListener((p)->{
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

                startActivityForResult(intent, CHOOSE_ROOT_REQ_CODE);
                return true;
            });
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode == CHOOSE_ROOT_REQ_CODE && resultCode == RESULT_OK) {
                if (data == null)
                    return;
                Uri uri = data.getData();
                getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                getPreferenceManager().getSharedPreferences().edit()
                        .putString(DOWNLOAD_DIR_PREF, uri.toString())
                        .apply();
            }
        }
    }
}