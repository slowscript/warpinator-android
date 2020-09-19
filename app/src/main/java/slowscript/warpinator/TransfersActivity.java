package slowscript.warpinator;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;

public class TransfersActivity extends AppCompatActivity {

    static final String TAG = "TransferActivity";

    public Remote remote;

    RecyclerView recyclerView;
    TransfersAdapter adapter;

    TextView txtRemote;
    TextView txtIP;
    TextView txtStatus;
    ImageView imgProfile;
    Button btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfers);
        String id = getIntent().getStringExtra("remote");
        remote = MainActivity.ctx.remotes.get(id);
        MainActivity.ctx.transfersView = this;

        recyclerView = findViewById(R.id.recyclerView2);
        adapter = new TransfersAdapter(this);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        txtRemote = findViewById(R.id.txtRemote);
        txtRemote.setText(remote.displayName + " (" + remote.userName + "@" + remote.hostname + ")");
        txtIP = findViewById(R.id.txtIP);
        txtIP.setText(remote.address.getHostAddress());
        txtStatus = findViewById(R.id.txtStatus);
        txtStatus.setText(remote.status.toString());
        imgProfile = findViewById(R.id.imgProfile);
        imgProfile.setImageBitmap(remote.picture);
        btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener((v) -> openFiles());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MainActivity.ctx.transfersView = null;
    }

    public void updateTransfer(String r, int i) {
        if (!r.equals(remote.uuid))
            return;
        runOnUiThread(() -> adapter.notifyItemChanged(i));
    }

    public void updateTransfers(String r) {
        if (!r.equals(remote.uuid))
            return;
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }
}
