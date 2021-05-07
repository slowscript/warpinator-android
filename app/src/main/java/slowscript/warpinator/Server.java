package slowscript.warpinator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import com.google.protobuf.ByteString;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.SslContextBuilder;

public class Server {
    private static final String TAG = "SRV";
    private static final String SERVICE_TYPE = "_warpinator._tcp.local.";

    public static Server current;
    static String displayName;
    public int port;
    public String uuid;
    public String profilePicture;
    public boolean allowOverwrite;
    public boolean notifyIncoming;
    public String downloadDirUri;

    private JmDNS jmdns;
    private final ServiceListener serviceListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;
    private io.grpc.Server gServer;

    private final MainService svc;

    public Server(MainService _svc) {
        svc = _svc;

        current = this;
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        loadSettings();

        serviceListener = newServiceListener();

        preferenceChangeListener = (p, k) -> loadSettings();
        svc.prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
    }

    public void Start() {
        //Start servers
        startGrpcServer();
        CertServer.Start(port);
        new Thread(this::startMDNS).start();
    }

    void startMDNS()
    {
        try {
            InetAddress addr = InetAddress.getByName(Utils.getIPAddress());
            jmdns = JmDNS.create(addr);

            /*Log.v(TAG, "Flush registration");
            registerService(true);
            Utils.sleep(1000);
            jmdns.unregisterAllServices();
            Utils.sleep(500);*/
            Log.v(TAG, "Real registration");
            registerService(false);
            Utils.sleep(500);
            //Start looking for others
            jmdns.addServiceListener(SERVICE_TYPE, serviceListener);
        }
        catch (Exception e) {
            Log.e(TAG, "Failed to init JmDNS", e);
        }
    }

    void stopMDNS() {
        if (jmdns != null) {
            try {
                jmdns.unregisterAllServices();
                jmdns.removeServiceListener(SERVICE_TYPE, serviceListener);
                jmdns.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close JmDNS");
            }
        }
    }

    public void Stop() {
        CertServer.Stop();
        stopMDNS();
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

    void registerService(boolean flush) {
        ServiceInfo serviceInfo = ServiceInfo.create(SERVICE_TYPE, uuid, port, "");
        Log.d(TAG, "Registering as " + uuid);

        Map<String, String> props = new HashMap<>();
        props.put("hostname", Utils.getDeviceName());
        String type = flush ? "flush" : "real";
        props.put("type", type);
        serviceInfo.setText(props);

        try {
            jmdns.registerService(serviceInfo);
        } catch (IOException e) {
            Log.e(TAG, "Failed to register service.", e);
        }
    }

    ServiceListener newServiceListener() {
        return new ServiceListener() {
            @Override
            public void serviceAdded(ServiceEvent event) {
                Log.d(TAG, "Service found: " + event.getInfo());
            }

            @Override
            public void serviceRemoved(ServiceEvent event) {
                String svcName = event.getInfo().getName();
                Log.v(TAG, "Service lost: " + svcName);
                if (MainService.remotes.containsKey(svcName)) {
                    Remote r = MainService.remotes.get(svcName);
                    r.serviceAvailable = false;
                    r.updateUI();
                }
            }

            @Override
            public void serviceResolved(ServiceEvent event) {
                ServiceInfo info = event.getInfo();
                Log.d(TAG, "*** Service resolved: " + info.getName());
                Log.d(TAG, "Details: " + info);
                if (info.getName().equals(uuid)) {
                    Log.v(TAG, "That's me. Ignoring.");
                    return;
                }
                //TODO: Same subnet check

                //Ignore flush registration
                ArrayList<String> props = Collections.list(info.getPropertyNames());
                if (props.contains("type") && "flush".equals(info.getPropertyString("type"))) {
                    Log.v(TAG, "Ignoring \"flush\" registration");
                    return;
                }

                String svcName = info.getName();
                if (MainService.remotes.containsKey(svcName)) {
                    Remote r = MainService.remotes.get(svcName);
                    Log.d(TAG, "Service already known. Status: " + r.status);
                    if(props.contains("hostname"))
                        r.hostname = info.getPropertyString("hostname");
                    r.serviceAvailable = true;
                    if ((r.status == Remote.RemoteStatus.DISCONNECTED) || (r.status == Remote.RemoteStatus.ERROR)) {
                        //Update hostname, address, port
                        r.address = info.getInetAddresses()[0];
                        r.port = info.getPort();
                        Log.d(TAG, "Reconnecting to " + r.hostname);
                        r.connect();
                    } else r.updateUI();
                    return;
                }

                Remote remote = new Remote();
                remote.address = info.getInetAddresses()[0];
                if(props.contains("hostname"))
                    remote.hostname = info.getPropertyString("hostname");
                remote.port = info.getPort();
                remote.serviceName = svcName;
                remote.uuid = svcName;
                remote.serviceAvailable = true;

                svc.addRemote(remote);
            }
        };
    }

    static Bitmap getProfilePicture(String picture, Context ctx) {
        int[] colors = new int[] {0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff3f51b5, 0xff2196f3, 0xff4caf50,
                0xff8bc34a, 0xffcddc39, 0xffffeb3b, 0xffffc107, 0xffff9800, 0xffff5722};
        if (picture.startsWith("content")) {
            try {
                return MediaStore.Images.Media.getBitmap(ctx.getContentResolver(), Uri.parse(picture));
            } catch (Exception e) {
                picture = "0";
            }
        }
        int i = Integer.parseInt(picture); //Could be also a content uri in the future
        Drawable foreground = ResourcesCompat.getDrawable(ctx.getResources(), R.mipmap.ic_launcher_foreground, null);
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
        Bitmap bmp = getProfilePicture(profilePicture, svc);
        bmp.compress(Bitmap.CompressFormat.PNG, 90, os);
        return ByteString.copyFrom(os.toByteArray());
    }
}
