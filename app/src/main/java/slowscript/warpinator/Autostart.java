package slowscript.warpinator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.preference.PreferenceManager;

public class Autostart extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if ((
             Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) ||
             Intent.ACTION_REBOOT.equals(intent.getAction())         ||
             Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())
            ) && prefs.getBoolean("bootStart", false)
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                Log.e("Autostart", "Autostart not possible on Android 15+");
                return;
            }
            Intent i = new Intent(context, MainService.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i);
            } else {
                context.startService(i);
            }
        }
    }
}