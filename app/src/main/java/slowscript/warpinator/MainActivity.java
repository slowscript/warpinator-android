package slowscript.warpinator;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

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

        recyclerView.post(() -> { //Run when UI is ready
            if (!Utils.isMyServiceRunning(this, MainService.class))
                startService(new Intent(this, MainService.class));
        });

        if (!checkWriteExternalPermission())
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
    }

    public static void updateRemoteList() {
        if (current == null)
            return;
        current.runOnUiThread(() -> current.adapter.notifyDataSetChanged());
    }

    private boolean checkWriteExternalPermission()
    {
        String permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        int res = checkCallingOrSelfPermission(permission);
        return (res == PackageManager.PERMISSION_GRANTED);
    }
}
