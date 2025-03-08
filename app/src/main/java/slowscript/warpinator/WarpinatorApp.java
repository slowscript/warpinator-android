package slowscript.warpinator;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.android.material.color.DynamicColors;

public class WarpinatorApp extends Application implements Application.ActivityLifecycleCallbacks {
    static int activitiesRunning = 0;
    static final String TAG = "APP";

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(this);
        activitiesRunning = 0;

        // Clear old persisted URI permissions (except profile picture)
        String picture = PreferenceManager.getDefaultSharedPreferences(this).getString("profile", "0");
        for (var u : getContentResolver().getPersistedUriPermissions()) {
            if (u.getUri().toString().equals(picture)) {
                Log.v(TAG, "keeping permission for " + u);
                continue;
            }
            Log.v(TAG, "releasing uri permission " + u);
            getContentResolver().releasePersistableUriPermission(u.getUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        Log.d(TAG, "Started activity");
        activitiesRunning++;
        MainService.cancelAutoStop();
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        activitiesRunning--;
        Log.d(TAG, "Stopped activity -> " + activitiesRunning);
        if (activitiesRunning < 1)
            MainService.scheduleAutoStop();
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) { }
    @Override public void onActivityResumed(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
