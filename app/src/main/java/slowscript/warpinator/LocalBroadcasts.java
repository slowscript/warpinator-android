package slowscript.warpinator;

import android.content.Context;
import android.content.Intent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class LocalBroadcasts {
    public static final String ACTION_UPDATE_REMOTES = "update_remotes";
    public static final String ACTION_UPDATE_TRANSFERS = "update_transfers";
    public static final String ACTION_UPDATE_TRANSFER = "update_transfer";
    public static final String ACTION_DISPLAY_MESSAGE = "display_message";
    public static final String ACTION_DISPLAY_TOAST = "display_toast";
    public static final String ACTION_CLOSE_ALL = "close_all";

    public static void updateRemotes(Context ctx) {
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(ACTION_UPDATE_REMOTES));
    }

    public static void updateTransfers(Context ctx, String remote) {
        Intent intent = new Intent(ACTION_UPDATE_TRANSFERS);
        intent.putExtra("remote", remote);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent);
    }

    public static void updateTransfer(Context ctx, String remote, int id) {
        Intent intent = new Intent(ACTION_UPDATE_TRANSFER);
        intent.putExtra("remote", remote);
        intent.putExtra("id", id);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent);
    }

    public static void displayMessage(Context ctx, String title, String msg) {
        Intent intent = new Intent(ACTION_DISPLAY_MESSAGE);
        intent.putExtra("title", title);
        intent.putExtra("msg", msg);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent);
    }

    public static void displayToast(Context ctx, String msg, int length) {
        Intent intent = new Intent(ACTION_DISPLAY_TOAST);
        intent.putExtra("msg", msg);
        intent.putExtra("length", length);
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent);
    }

    public static void closeAll(Context ctx) {
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(new Intent(ACTION_CLOSE_ALL));
    }
}
