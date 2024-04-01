package slowscript.warpinator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.preference.PreferenceManager;

public class Autostart extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        if ((
             intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED) ||
             intent.getAction().equals(Intent.ACTION_REBOOT)         ||
             intent.getAction().equals(Intent.ACTION_MY_PACKAGE_REPLACED)
            ) && prefs.getBoolean("bootStart", false)
        ) {
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