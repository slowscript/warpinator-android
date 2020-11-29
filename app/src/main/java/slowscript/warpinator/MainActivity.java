package slowscript.warpinator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.conscrypt.Conscrypt;

import java.security.Security;

public class MainActivity extends AppCompatActivity {

    private String TAG = "MAIN";

    static MainActivity current;
    RecyclerView recyclerView;
    RemotesAdapter adapter;
    TextView txtNotFound;

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
        txtNotFound = findViewById(R.id.txtNotFound);

        if (!checkWriteExternalPermission())
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        recyclerView.post(() -> { //Run when UI is ready
            if (!Utils.isMyServiceRunning(this, MainService.class))
                startService(new Intent(this, MainService.class));
        });
        updateRemoteList();
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
                //TODO: implement about activity
                /*Intent intent = new Intent(this, About.class);
                startActivity(intent);*/

                //A toast to be removed when the about function is implemented
                Toast.makeText(getApplicationContext(), getText(R.string.not_implemented_toast), Toast.LENGTH_LONG).show();
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
            txtNotFound.setVisibility(MainService.remotes.size() == 0 ? View.VISIBLE : View.INVISIBLE);
        });
    }

    private boolean checkWriteExternalPermission()
    {
        String permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        int res = checkCallingOrSelfPermission(permission);
        return (res == PackageManager.PERMISSION_GRANTED);
    }
}
