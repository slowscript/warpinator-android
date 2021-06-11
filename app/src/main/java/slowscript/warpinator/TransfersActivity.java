package slowscript.warpinator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;

public class TransfersActivity extends AppCompatActivity {

    static final String TAG = "TransferActivity";
    static final int SEND_FILE_REQ_CODE = 10;

    public Remote remote;
    public boolean isTopmost = false;
    boolean shareMode = false;

    RecyclerView recyclerView;
    TransfersAdapter adapter;

    TextView txtName;
    TextView txtRemote;
    TextView txtIP;
    ImageView imgProfile;
    ImageView imgStatus;
    FloatingActionButton fabSend;
    ImageButton btnReconnect;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(R.anim.anim_push_up, R.anim.anim_null);
        setContentView(R.layout.activity_transfers);
        String id = getIntent().getStringExtra("remote");
        shareMode = getIntent().getBooleanExtra("shareMode", false);
        if (!MainService.remotes.containsKey(id)) {
            finish();
            return;
        }
        remote = MainService.remotes.get(id);
        MainService.svc.transfersView = this;

        recyclerView = findViewById(R.id.recyclerView2);
        adapter = new TransfersAdapter(this);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        //Prevent blinking on update
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        txtName = findViewById(R.id.txtDisplayName);
        txtRemote = findViewById(R.id.txtRemote);
        txtIP = findViewById(R.id.txtIP);
        imgStatus = findViewById(R.id.imgStatus);
        imgProfile = findViewById(R.id.imgProfile);
        fabSend = findViewById(R.id.fabSend);
        fabSend.setOnClickListener((v) -> openFiles());
        btnReconnect = findViewById(R.id.btnReconnect);
        btnReconnect.setOnClickListener((v) -> reconnect());

        //Connection status toast
        imgStatus.setOnClickListener(view -> {
            String s = getResources().getStringArray(R.array.connected_states)[remote.status.ordinal()];
            if (!remote.serviceAvailable)
                s += getString(R.string.service_unavailable);
            Toast.makeText(getBaseContext(), s, Toast.LENGTH_LONG).show();
        });


        updateUI();
    }


    @Override
    protected void onResume() {
        super.onResume();
        isTopmost = true;
        updateTransfers(remote.uuid);
        updateUI();
        if (MainService.svc.runningTransfers == 0)
            MainService.svc.notificationMgr.cancel(MainService.PROGRESS_NOTIFICATION_ID);
    }

    @Override
    protected void onPause() {
        super.onPause();
        isTopmost = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (MainService.svc != null) //Sometimes it crashes here when MainService finishes first
            MainService.svc.transfersView = null;
    }

    @Override
    public void onBackPressed(){
        if (shareMode) {
            if (transferInProgress())
                Toast.makeText(this, R.string.dont_close_when_sharing, Toast.LENGTH_LONG).show();
            else finishAffinity(); //Close everything incl. ShareActivity
        } else {
            super.onBackPressed();
            TransfersActivity.this.overridePendingTransition(R.anim.anim_null, R.anim.anim_push_down);
        }
    }
    private boolean transferInProgress() {
        for (Transfer t : remote.transfers) {
            if (t.direction == Transfer.Direction.SEND && (t.getStatus() == Transfer.Status.WAITING_PERMISSION || t.getStatus() == Transfer.Status.TRANSFERRING))
                return true;
        }
        return false;
    }

    @SuppressLint("SetTextI18n")
    public void updateUI() {
        runOnUiThread(() -> { //Will run immediately if on correct thread already
            txtName.setText(remote.displayName);
            txtRemote.setText(remote.userName + "@" + remote.hostname);
            txtIP.setText(remote.address.getHostAddress());
            imgStatus.setImageResource(Utils.getIconForRemoteStatus(remote.status));
            if (remote.status == Remote.RemoteStatus.ERROR || remote.status == Remote.RemoteStatus.DISCONNECTED) {
                if (!remote.serviceAvailable)
                    imgStatus.setImageResource(R.drawable.ic_unavailable);
                else imgStatus.setImageTintList(null);
            } else {
                imgStatus.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.iconsOrTextTint)));
            }
            if (remote.picture != null) {
                imgProfile.setImageBitmap(remote.picture);
                imgProfile.setImageTintList(null);
            } else { //Keep default, apply tint
                imgProfile.setImageTintList(ColorStateList.valueOf(getResources().getColor(R.color.iconsOrTextTint)));
            }
            fabSend.setEnabled(remote.status == Remote.RemoteStatus.CONNECTED);
            btnReconnect.setVisibility((remote.status == Remote.RemoteStatus.ERROR)
                    || (remote.status == Remote.RemoteStatus.DISCONNECTED)
                    ? View.VISIBLE : View.INVISIBLE);
            if (remote.errorGroupCode) {
                remote.errorGroupCode = false;
                Toast.makeText(this, R.string.wrong_group_code, Toast.LENGTH_LONG).show();
            }
        });
    }

    void reconnect() {
        remote.connect();
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
        super.onActivityResult(requestCode, resultCode, data);
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
                    return;
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
