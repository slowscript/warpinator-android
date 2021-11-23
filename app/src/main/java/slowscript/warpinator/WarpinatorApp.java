package slowscript.warpinator;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class WarpinatorApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
