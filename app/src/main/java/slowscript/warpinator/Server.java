package slowscript.warpinator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import com.google.protobuf.ByteString;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.Security;
import java.util.Random;
import java.util.UUID;

import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.SslContextBuilder;

public class Server {
    private static final String TAG = "SRV";
    private static final String SERVICE_TYPE = "_warpinator._tcp.";

    public static Server current;
    static String displayName;
    public int port;
    public String uuid;
    public String profilePicture;
    public boolean allowOverwrite;
    public boolean notifyIncoming;
    public String downloadDirUri;

    private final NsdManager nsdManager;
    private final NsdManager.RegistrationListener registrationListener;
    private final NsdManager.DiscoveryListener discoveryListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;
    private io.grpc.Server gServer;

    private final MainService svc;

    public Server(MainService _svc) {
        svc = _svc;

        current = this;
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        loadSettings();

        nsdManager = (NsdManager) svc.getSystemService(Context.NSD_SERVICE);
        registrationListener = new RegistrationListener();
        discoveryListener = new DiscoveryListener();

        preferenceChangeListener = (p, k) -> loadSettings();
        svc.prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void Start() {
        //Start servers
        startGrpcServer();
        CertServer.Start(port);
        //Announce ourselves
        registerService();
        //Start looking for others
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void Stop() {
        CertServer.Stop();
        nsdManager.unregisterService(registrationListener);
        nsdManager.stopServiceDiscovery(discoveryListener);
        svc.prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        gServer.shutdownNow();
        Log.i(TAG, "Server stopped");
    }

    void loadSettings() {
        if(!svc.prefs.contains("uuid"))
            svc.prefs.edit().putString("uuid", UUID.randomUUID().toString()).apply();
        uuid = svc.prefs.getString("uuid", "default");
        displayName = svc.prefs.getString("displayName", "Android");
        port = Integer.parseInt(svc.prefs.getString("port", "42000"));
        Authenticator.groupCode = svc.prefs.getString("groupCode", Authenticator.DEFAULT_GROUP_CODE);
        allowOverwrite = svc.prefs.getBoolean("allowOverwrite", false);
        notifyIncoming = svc.prefs.getBoolean("notifyIncoming", true);
        downloadDirUri = svc.prefs.getString("downloadDir", "");
        if(!svc.prefs.contains("profile"))
            svc.prefs.edit().putString("profile",  String.valueOf(new Random().nextInt(12))).apply();
        profilePicture = svc.prefs.getString("profile", "0");
    }

    void startGrpcServer() {
        try {
            File cert = new File(Utils.getCertsDir(), ".self.pem");
            File key = new File(Utils.getCertsDir(), ".self.key-pem");
            SslContextBuilder ssl = GrpcSslContexts.forServer(cert, key).sslContextProvider(Conscrypt.newProvider());
            //SslContextBuilder ssl = GrpcSslContexts.configure(SslContextBuilder.forServer(cert, key), Conscrypt.newProvider());
            gServer = NettyServerBuilder.forPort(port)
                    .sslContext(ssl.build())
                    .addService(new GrpcService())
                    .build();
            gServer.start();
            Log.d(TAG, "GRPC server started");
        } catch(Exception e) {
            Log.e(TAG, "Failed to start GRPC server.", e);
            Toast.makeText(svc, "Failed to start GRPC server", Toast.LENGTH_LONG).show();
        }
    }

    void registerService() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        Log.d(TAG, "Registering as " + uuid);
        serviceInfo.setServiceName(uuid);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(port);

        serviceInfo.setAttribute("hostname", Utils.getDeviceName());

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    static class RegistrationListener implements NsdManager.RegistrationListener {
        @Override
        public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(TAG, "Failed to register zeroconf service. Error code: " + errorCode);
        }
        @Override
        public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
            Log.e(TAG, "Failed to unregister zeroconf service. Error code: " + errorCode);
        }
        @Override
        public void onServiceRegistered(NsdServiceInfo info) {
            Log.d(TAG, "Service registered: " + info.getServiceName());
        }
        @Override
        public void onServiceUnregistered(NsdServiceInfo info) {
            Log.d(TAG, "Service unregistered");
        }
    }

    class DiscoveryListener implements NsdManager.DiscoveryListener {
        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(TAG, "Could not start service discovery. Error code: " + errorCode);
        }
        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
            Log.e(TAG, "Could not stop service discovery. Error code: " + errorCode);
        }
        @Override
        public void onDiscoveryStarted(String serviceType) {
            Log.v(TAG, "Started discovering services...");
        }
        @Override
        public void onDiscoveryStopped(String serviceType) {
            Log.v(TAG, "Stopped discovering services");
        }
        @Override
        public void onServiceFound(NsdServiceInfo info) {
            Log.v(TAG, "Service found: " + info.getServiceName());
            if (info.getServiceName().equals(uuid)) {
                Log.v(TAG, "That's me. Ignoring.");
                return;
            }

            nsdManager.resolveService(info, newResolveListener());
        }
        @Override
        public void onServiceLost(final NsdServiceInfo info) {
            String svcName = info.getServiceName();
            Log.v(TAG, "Service lost: " + svcName);
            if (MainService.remotes.containsKey(svcName)) {
                Remote r = MainService.remotes.get(svcName);
                r.serviceAvailable = false;
                r.updateUI();
            }
        }
    }

    NsdManager.ResolveListener newResolveListener() {
        return new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.w(TAG, "Failed to resolve service \"" + serviceInfo.getServiceName() + "\". Error code" + errorCode);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                Log.d(TAG, "*** Service resolved: " + info.getServiceName());
                //TODO: Same subnet check

                //Ignore flush registration
                if (info.getAttributes().containsKey("type") && new String(info.getAttributes().get("type")).equals("flush")) {
                    Log.v(TAG, "Ignoring \"flush\" registration");
                    return;
                }

                String svcName = info.getServiceName();
                if (MainService.remotes.containsKey(svcName)) {
                    Remote r = MainService.remotes.get(svcName);
                    Log.d(TAG, "Service already known. Status: " + r.status);
                    if ((r.status == Remote.RemoteStatus.DISCONNECTED) || (r.status == Remote.RemoteStatus.ERROR)) {
                        //Update hostname, address, port
                        r.address = info.getHost();
                        r.port = info.getPort();
                        r.serviceAvailable = true;
                        Log.d(TAG, "Reconnecting to " + r.hostname);
                        r.connect();
                    }
                    return;
                }

                Remote remote = new Remote();
                remote.address = info.getHost();
                if(info.getAttributes().containsKey("hostname"))
                    remote.hostname = new String(info.getAttributes().get("hostname"));
                remote.port = info.getPort();
                remote.serviceName = svcName;
                remote.uuid = svcName;
                remote.serviceAvailable = true;

                svc.addRemote(remote);
            }
        };
    }

    Bitmap getProfilePicture(String picture) {
        int[] colors = new int[] {0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff3f51b5, 0xff2196f3, 0xff4caf50,
                0xff8bc34a, 0xffcddc39, 0xffffeb3b, 0xffffc107, 0xffff9800, 0xffff5722};
        if (picture.startsWith("content")) {
            try {
                return MediaStore.Images.Media.getBitmap(svc.getContentResolver(), Uri.parse(picture));
            } catch (Exception e) {
                picture = "0";
            }
        }
        int i = Integer.parseInt(picture); //Could be also a content uri in the future
        Drawable foreground = ResourcesCompat.getDrawable(svc.getResources(), R.mipmap.ic_launcher_foreground, null);
        Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint();
        paint.setColor(colors[i]);
        canvas.drawCircle(48,48,48, paint);
        foreground.setBounds(0,0,96,96);
        foreground.draw(canvas);
        return bmp;
    }

    ByteString getProfilePictureBytes() {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        Bitmap bmp = getProfilePicture(profilePicture);
        bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
        return ByteString.copyFrom(os.toByteArray());
    }
}
