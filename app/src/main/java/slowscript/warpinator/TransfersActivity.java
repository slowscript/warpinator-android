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
    static final int SEND_FILE_REQ_CODE = 10;

    public Remote remote;

    RecyclerView recyclerView;
    TransfersAdapter adapter;

    TextView txtRemote;
    TextView txtIP;
    TextView txtStatus;
    ImageView imgProfile;
    ImageView imgStatus;
    Button btnSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfers);
        String id = getIntent().getStringExtra("remote");
        remote = MainService.remotes.get(id);
        MainService.svc.transfersView = this;

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
        imgStatus = findViewById(R.id.imgStatus);
        imgStatus.setImageResource(Utils.getIconForRemoteStatus(remote.status));
        imgProfile = findViewById(R.id.imgProfile);
        imgProfile.setImageBitmap(remote.picture);
        btnSend = findViewById(R.id.btnSend);
        btnSend.setOnClickListener((v) -> openFiles());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        MainService.svc.transfersView = null;
    }

    void openFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        startActivityForResult(i, SEND_FILE_REQ_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == SEND_FILE_REQ_CODE && resultCode == Activity.RESULT_OK) {
            if (data == null)
                return;
            Transfer t = new Transfer();
            t.uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd == null) {
                Uri u = data.getData();
                if (u == null) {
                    Log.w(TAG, "No uri to send");
                }
                Log.d(TAG, u.toString());
                t.uris.add(u);
            } else {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    t.uris.add(cd.getItemAt(i).getUri());
                    Log.d(TAG, cd.getItemAt(i).getUri().toString());
                }
            }
            t.remoteUUID = remote.uuid;
            t.prepareSend();

            remote.transfers.add(t);
            t.privId = remote.transfers.size()-1;
            updateTransfers(remote.uuid);

            remote.startSendTransfer(t);
        }
        super.onActivityResult(requestCode, resultCode, data);
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
