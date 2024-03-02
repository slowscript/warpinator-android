package slowscript.warpinator;

import android.os.Binder;

public class MainServiceBinder extends Binder {

    private final MainService service;

    public MainServiceBinder(MainService service) {
        this.service = service;
    }

    public MainService getService() {
        return service;
    }
}
