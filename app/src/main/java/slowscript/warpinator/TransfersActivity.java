package slowscript.warpinator;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;

public class TransfersActivity extends AppCompatActivity {

    static final String TAG = "TransferActivity";
    static final int SEND_FILE_REQ_CODE = 10;
    static final int SEND_FOLDER_REQ_CODE = 11;

    public static String topmostRemote;

    Remote remote;
    boolean shareMode = false;

    RecyclerView recyclerView;
    TransfersAdapter adapter;
    BroadcastReceiver receiver;

    TextView txtName;
    TextView txtRemote;
    TextView txtIP;
    ImageView imgProfile;
    ImageView imgStatus;
    FloatingActionButton fabSend;
    FloatingActionButton fabSendFiles;
    FloatingActionButton fabSendDir;
    FloatingActionButton fabClear;
    Button btnReconnect;
    ToggleButton tglStar;

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
        receiver = newBroadcastReceiver();

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
        fabSendFiles = findViewById(R.id.fabSendFile);
        fabSendDir = findViewById(R.id.fabSendDir);
        fabSend.setOnClickListener((v) -> {
            int vis = fabSendFiles.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            setFabVisibility(vis);
        });
        fabSendFiles.setOnClickListener((v) -> openFiles());
        fabSendDir.setOnClickListener((v) -> openFolder());
        fabClear = findViewById(R.id.fabClear);
        fabClear.setOnClickListener((v) -> remote.clearTransfers());
        btnReconnect = findViewById(R.id.btnReconnect);
        btnReconnect.setOnClickListener((v) -> reconnect());
        tglStar = findViewById(R.id.tglStar);
        tglStar.setChecked(remote.isFavorite());
        tglStar.setOnCheckedChangeListener((v, checked) -> onFavoritesCheckChanged(checked));

        //Connection status toast
        imgStatus.setOnClickListener(view -> {
            String s = getResources().getStringArray(R.array.connected_states)[remote.status.ordinal()];
            if (!remote.serviceAvailable)
                s += getString(R.string.service_unavailable);
            if (remote.status == Remote.RemoteStatus.ERROR)
                s += " (" + remote.errorText + ")";
            CoordinatorLayout cdView = findViewById(R.id.activityTransfersRoot);
            Snackbar.make(cdView, s, Snackbar.LENGTH_LONG).setAnimationMode(Snackbar.ANIMATION_MODE_SLIDE).show();
        });

        updateUI();
    }


    @Override
    protected void onResume() {
        super.onResume();
        if (!Utils.isMyServiceRunning(this, MainService.class)) {
            finish();
            return;
        }
        topmostRemote = remote.uuid;
        updateTransfers(remote.uuid);
        updateUI();
        if (MainService.svc.runningTransfers == 0)
            MainService.svc.notificationMgr.cancel(MainService.PROGRESS_NOTIFICATION_ID);
        if (remote.errorReceiveCert)
            Utils.displayMessage(this, getString(R.string.connection_error), getString(R.string.cert_not_received, remote.hostname, remote.port));

        IntentFilter f = new IntentFilter(LocalBroadcasts.ACTION_UPDATE_REMOTES);
        f.addAction(LocalBroadcasts.ACTION_UPDATE_TRANSFERS);
        f.addAction(LocalBroadcasts.ACTION_UPDATE_TRANSFER);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_MESSAGE);
        f.addAction(LocalBroadcasts.ACTION_DISPLAY_TOAST);
        f.addAction(LocalBroadcasts.ACTION_CLOSE_ALL);
        LocalBroadcastManager.getInstance(this).registerReceiver(receiver, f);
    }

    @Override
    protected void onPause() {
        super.onPause();
        topmostRemote = null;
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
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

    private BroadcastReceiver newBroadcastReceiver() {
        Context ctx = this;
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                switch (action) {
                    case LocalBroadcasts.ACTION_UPDATE_REMOTES:
                        updateUI();
                        break;
                    case LocalBroadcasts.ACTION_UPDATE_TRANSFERS:
                        String r = intent.getStringExtra("remote");
                        if (r != null) updateTransfers(r);
                        break;
                    case LocalBroadcasts.ACTION_UPDATE_TRANSFER:
                        r = intent.getStringExtra("remote");
                        int id = intent.getIntExtra("id", -1);
                        if (r != null) updateTransfer(r, id);
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

            int color = Utils.getAndroidAttributeColor(this, android.R.attr.textColorTertiary);

            if (remote.picture != null) {
                imgProfile.setImageTintList(null);
                imgProfile.setImageBitmap(remote.picture);
            } else {
                imgProfile.setImageTintList(ColorStateList.valueOf(color));
            }

            if (remote.status == Remote.RemoteStatus.ERROR || remote.status == Remote.RemoteStatus.DISCONNECTED) {
                if (!remote.serviceAvailable)
                    imgStatus.setImageResource(R.drawable.ic_unavailable);
                else
                    color = Utils.getAttributeColor(getTheme(), R.attr.colorError);
            }
            imgStatus.setImageTintList(ColorStateList.valueOf(color));

            fabSend.setEnabled(remote.status == Remote.RemoteStatus.CONNECTED);
            btnReconnect.setVisibility((remote.status == Remote.RemoteStatus.ERROR)
                    || (remote.status == Remote.RemoteStatus.DISCONNECTED)
                    ? View.VISIBLE : View.INVISIBLE);
        });
    }

    void reconnect() {
        remote.connect();
    }

    private void onFavoritesCheckChanged(boolean checked) {
        MainService.remotesOrder.remove(remote.uuid);
        if (checked) {
            Server.current.favorites.add(remote.uuid);
            MainService.remotesOrder.add(0, remote.uuid);
        } else {
            Server.current.favorites.remove(remote.uuid);
            MainService.remotesOrder.add(remote.uuid);
        }
        Server.current.saveFavorites();
    }

    private void setFabVisibility(int vis) {
        fabSendFiles.setVisibility(vis);
        fabSendDir.setVisibility(vis);
        fabSend.setImageResource(vis == View.VISIBLE ? R.drawable.ic_decline : R.drawable.ic_upload);
    }

    private void openFiles() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Log.d(TAG, "Starting file browser activty (prevent autostop)");
        WarpinatorApp.activitiesRunning++; //Prevent autostop
        startActivityForResult(i, SEND_FILE_REQ_CODE);
        setFabVisibility(View.GONE);
    }

    private void openFolder() {
        Toast.makeText(this, R.string.send_folder_toast, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            Log.d(TAG, "Starting folder browser activity (prevent auto-stop)");
            startActivityForResult(i, SEND_FOLDER_REQ_CODE);
            WarpinatorApp.activitiesRunning++; //Prevent auto-stop only if actually opened
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.required_dialog_not_found, Toast.LENGTH_LONG).show();
        }
        setFabVisibility(View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SEND_FILE_REQ_CODE || requestCode == SEND_FOLDER_REQ_CODE) {
            Log.d(TAG, "File browser activity finished"); //We return to this activity
            WarpinatorApp.activitiesRunning--;
            if ((resultCode != Activity.RESULT_OK) || (data == null))
                return;
            Transfer t = new Transfer();
            t.uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (requestCode == SEND_FOLDER_REQ_CODE || cd == null) {
                Uri u = data.getData();
                if (u == null) {
                    Log.w(TAG, "No uri to send");
                    return;
                }
                Log.v(TAG, u.toString());
                t.uris.add(u);
            } else {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    t.uris.add(cd.getItemAt(i).getUri());
                    Log.v(TAG, cd.getItemAt(i).getUri().toString());
                }
            }
            t.remoteUUID = remote.uuid;

            remote.addTransfer(t);
            t.setStatus(Transfer.Status.INITIALIZING);
            updateTransfers(remote.uuid);
            new Thread(() -> {
                t.prepareSend(requestCode == SEND_FOLDER_REQ_CODE);
                remote.startSendTransfer(t);
            }).start();
        }
    }

    private void updateTransfer(String r, int i) {
        if (!r.equals(remote.uuid))
            return;
        runOnUiThread(() -> adapter.notifyItemChanged(i));
    }

    private void updateTransfers(String r) {
        if (!r.equals(remote.uuid))
            return;
        runOnUiThread(() -> {
            adapter.notifyDataSetChanged();
            fabClear.setVisibility(remote.transfers.size() > 0 ? View.VISIBLE : View.INVISIBLE);
        });
    }
}
