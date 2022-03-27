package slowscript.warpinator;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.DynamicColors;

public class WarpinatorApp extends Application implements Application.ActivityLifecycleCallbacks {
    private int activitiesRunning = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        registerActivityLifecycleCallbacks(this);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        Log.d("APP", "Start");
        activitiesRunning++;
        MainService.cancelAutoStop();
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        Log.d("APP", "Stop");
        activitiesRunning--;
        if (activitiesRunning < 1)
            MainService.scheduleAutoStop();
    }

    @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle bundle) { }
    @Override public void onActivityResumed(@NonNull Activity activity) {}
    @Override public void onActivityPaused(@NonNull Activity activity) {}
    @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle bundle) { }
    @Override public void onActivityDestroyed(@NonNull Activity activity) { }
}
