package slowscript.warpinator;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import com.google.common.net.InetAddresses;
import com.google.protobuf.ByteString;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.Security;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;
import javax.jmdns.impl.DNSOutgoing;
import javax.jmdns.impl.DNSQuestion;
import javax.jmdns.impl.DNSRecord;
import javax.jmdns.impl.JmDNSImpl;
import javax.jmdns.impl.ServiceInfoImpl;
import javax.jmdns.impl.constants.DNSConstants;
import javax.jmdns.impl.constants.DNSRecordClass;
import javax.jmdns.impl.constants.DNSRecordType;
import javax.jmdns.impl.tasks.resolver.ServiceResolver;
import javax.jmdns.impl.tasks.state.Renewer;

import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.netty.handler.ssl.SslContextBuilder;

public class Server {
    private static final String TAG = "SRV";
    public static final String SERVICE_TYPE = "_warpinator._tcp.local.";

    public static Server current;
    public String displayName;
    public int port;
    public int authPort;
    public String uuid;
    public String profilePicture;
    public boolean allowOverwrite;
    public boolean notifyIncoming;
    public String downloadDirUri;
    public boolean running = false;
    public HashSet<String> favorites = new HashSet<>();
    public boolean useCompression;

    JmDNS jmdns;
    private Renewer renewer;
    private ServiceInfo serviceInfo;
    private final ServiceListener serviceListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener;
    private io.grpc.Server gServer;
    private io.grpc.Server regServer;
    private int apiVersion = 2;

    private final MainService svc;

    public Server(MainService _svc) {
        svc = _svc;

        current = this;
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        loadSettings();

        serviceListener = newServiceListener();

        preferenceChangeListener = (p, k) -> loadSettings();
    }

    public void Start() {
        Log.i(TAG, "--- Starting server");
        running = true;
        startGrpcServer();
        startRegistrationServer();
        CertServer.Start(port);
        new Thread(this::startMDNS).start();
        svc.prefs.registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        LocalBroadcasts.updateNetworkState(svc);
    }

    public void Stop() {
        running = false;
        CertServer.Stop();
        new Thread(this::stopMDNS).start(); //This takes a long time and we may be on the main thread
        svc.prefs.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        if (gServer != null)
            gServer.shutdownNow();
        if (regServer != null)
            regServer.shutdownNow();
        LocalBroadcasts.updateNetworkState(svc);
        Log.i(TAG, "--- Server stopped");
    }

    void startMDNS() {
        try {
            InetAddress addr = InetAddress.getByName(Utils.getIPAddress());
            jmdns = JmDNS.create(addr);

            registerService(false);
            Utils.sleep(500);
            //Start looking for others
            jmdns.addServiceListener(SERVICE_TYPE, serviceListener);
        }
        catch (Exception e) {
            running = false;
            Log.e(TAG, "Failed to init JmDNS", e);
            LocalBroadcasts.displayToast(svc, "Failed to start JmDNS", 0);
        }
    }

    void stopMDNS() {
        if (jmdns != null) {
            try {
                jmdns.unregisterAllServices();
                jmdns.removeServiceListener(SERVICE_TYPE, serviceListener);
                jmdns.close();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close JmDNS", e);
            }
        }
    }

    void loadSettings() {
        if(!svc.prefs.contains("uuid"))
            svc.prefs.edit().putString("uuid", Utils.generateServiceName()).apply();
        uuid = svc.prefs.getString("uuid", "default");
        displayName = svc.prefs.getString("displayName", "Android");
        port = Integer.parseInt(svc.prefs.getString("port", "42000"));
        authPort = Integer.parseInt(svc.prefs.getString("authPort", "42001"));
        Authenticator.groupCode = svc.prefs.getString("groupCode", Authenticator.DEFAULT_GROUP_CODE);
        allowOverwrite = svc.prefs.getBoolean("allowOverwrite", false);
        notifyIncoming = svc.prefs.getBoolean("notifyIncoming", true);
        downloadDirUri = svc.prefs.getString("downloadDir", "");
        useCompression = svc.prefs.getBoolean("useCompression", false);
        if(!svc.prefs.contains("profile"))
            svc.prefs.edit().putString("profile",  String.valueOf(new Random().nextInt(12))).apply();
        profilePicture = svc.prefs.getString("profile", "0");
        favorites.clear();
        favorites.addAll(svc.prefs.getStringSet("favorites", Collections.emptySet()));

        boolean bootStart = svc.prefs.getBoolean("bootStart", false);
        boolean autoStop = svc.prefs.getBoolean("autoStop", true);
        if (bootStart && autoStop)
            svc.prefs.edit().putBoolean("autoStop", false).apply();
    }

    void saveFavorites() {
        svc.prefs.edit().putStringSet("favorites", favorites).apply();
    }

    void startGrpcServer() {
        try {
            File cert = new File(Utils.getCertsDir(), ".self.pem");
            File key = new File(Utils.getCertsDir(), ".self.key-pem");
            SslContextBuilder ssl = GrpcSslContexts.forServer(cert, key).sslContextProvider(Conscrypt.newProvider());
            gServer = NettyServerBuilder.forPort(port)
                    .sslContext(ssl.build())
                    .addService(new GrpcService())
                    .permitKeepAliveWithoutCalls(true)
                    .permitKeepAliveTime(10, TimeUnit.SECONDS)
                    .build();
            gServer.start();
            Log.d(TAG, "GRPC server started");
        } catch(Exception e) {
            running = false;
            Log.e(TAG, "Failed to start GRPC server.", e);
            Toast.makeText(svc, "Failed to start GRPC server. Please try rebooting your phone or changing port numbers.", Toast.LENGTH_LONG).show();
        }
    }

    void startRegistrationServer() {
        try {
            regServer = NettyServerBuilder.forPort(authPort)
                    .addService(new RegistrationService())
                    .build();
            regServer.start();
            Log.d(TAG, "Registration server started");
        } catch(Exception e) {
            apiVersion = 1;
            Log.w(TAG, "Failed to start V2 registration service.", e);
            Toast.makeText(svc, "Failed to start V2 registration service. Only V1 will be available.", Toast.LENGTH_LONG).show();
        }
    }

    void registerService(boolean flush) {
        serviceInfo = ServiceInfo.create(SERVICE_TYPE, uuid, port, "");
        Log.d(TAG, "Registering as " + uuid);

        Map<String, String> props = new HashMap<>();
        props.put("hostname", Utils.getDeviceName());
        String type = flush ? "flush" : "real";
        props.put("type", type);
        props.put("api-version", String.valueOf(apiVersion));
        props.put("auth-port", String.valueOf(authPort));
        serviceInfo.setText(props);

        // Unregister possibly leftover service info
        // -> Announcement will trigger "new service" behavior and reconnect on other clients
        unregister(); //Safe if fails
        try {
            jmdns.registerService(serviceInfo);
            renewer = new Renewer((JmDNSImpl)jmdns);
        } catch (IOException e) {
            Log.e(TAG, "Failed to register service.", e);
        }
    }

    WarpProto.ServiceRegistration getServiceRegistrationMsg() {
        return WarpProto.ServiceRegistration.newBuilder()
                .setServiceId(uuid)
                .setIp(svc.lastIP)
                .setPort(port)
                .setHostname(Utils.getDeviceName())
                .setApiVersion(apiVersion)
                .setAuthPort(authPort)
                .build();
    }

    void registerWithHost(String host) {
        new Thread(() -> {
            Log.d(TAG, "Registering with host " + host);
            try {
                ManagedChannel channel = OkHttpChannelBuilder.forTarget(host).usePlaintext().build();
                WarpProto.ServiceRegistration resp = WarpRegistrationGrpc.newBlockingStub(channel)
                        .registerService(getServiceRegistrationMsg());
                Log.d(TAG, "registerWithHost: registration sent");
                int sep = host.lastIndexOf(':');
                // Use ip and authPort as specified by user
                String IP = host.substring(0,sep);
                int aport = Integer.parseInt(host.substring(sep+1));
                Remote r = MainService.remotes.get(resp.getServiceId());
                boolean newRemote = r == null;
                if (newRemote) {
                    r = new Remote();
                    r.uuid = resp.getServiceId();
                } else if (r.status == Remote.RemoteStatus.CONNECTED) {
                    Log.w(TAG, "registerWithHost: remote already connected");
                    LocalBroadcasts.displayToast(svc, "Device already connected", Toast.LENGTH_SHORT);
                    return;
                }
                r.address = InetAddresses.forString(IP);
                r.authPort = aport;
                r.updateFromServiceRegistration(resp);
                if (newRemote) {
                    addRemote(r);
                    Log.d(TAG, "registerWithHost: remote added");
                } else {
                    if (r.status == Remote.RemoteStatus.DISCONNECTED || r.status == Remote.RemoteStatus.ERROR)
                        r.connect();
                    else r.updateUI();
                }
            } catch (Exception e) {
                if (e instanceof StatusRuntimeException && ((StatusRuntimeException)e).getStatus() == Status.Code.UNIMPLEMENTED.toStatus()) {
                    Log.e(TAG, "Host " + host + " does not support manual connect -- " + e);
                    LocalBroadcasts.displayToast(svc, "Host " + host + " does not support manual connect", Toast.LENGTH_LONG);
                } else {
                    Log.e(TAG, "Failed to connect to " + host, e);
                    LocalBroadcasts.displayToast(svc, "Failed to connect to " + host + " - " + e, Toast.LENGTH_LONG);
                }
            }
        }).start();
    }

    void reannounce() {
        svc.executor.submit(()->{
            Log.d(TAG, "Reannouncing");
            try {
                DNSOutgoing out = new DNSOutgoing(DNSConstants.FLAGS_QR_RESPONSE | DNSConstants.FLAGS_AA);
                for (DNSRecord answer : ((JmDNSImpl)jmdns).getLocalHost().answers(DNSRecordClass.CLASS_ANY, DNSRecordClass.UNIQUE, DNSConstants.DNS_TTL)) {
                    out = renewer.addAnswer(out, null, answer);
                }
                for (DNSRecord answer : ((ServiceInfoImpl) serviceInfo).answers(DNSRecordClass.CLASS_ANY,
                        DNSRecordClass.UNIQUE, DNSConstants.DNS_TTL, ((JmDNSImpl)jmdns).getLocalHost())) {
                    out = renewer.addAnswer(out, null, answer);
                }
                ((JmDNSImpl)jmdns).send(out);
            } catch (Exception e) {
                Log.e(TAG, "Reannounce failed", e);
                LocalBroadcasts.displayToast(svc, "Reannounce failed: " + e.getMessage(), Toast.LENGTH_LONG);
            }
        });
    }

    void rescan() {
        svc.executor.submit(()->{
            Log.d(TAG, "Rescanning");
            //Need a new one every time since it can only run three times
            ServiceResolver resolver = new ServiceResolver((JmDNSImpl)jmdns, SERVICE_TYPE);
            DNSOutgoing out = new DNSOutgoing(DNSConstants.FLAGS_QR_QUERY);
            try {
                out = resolver.addQuestion(out, DNSQuestion.newQuestion(SERVICE_TYPE, DNSRecordType.TYPE_PTR, DNSRecordClass.CLASS_IN, DNSRecordClass.NOT_UNIQUE));
                //out = resolver.addQuestion(out, DNSQuestion.newQuestion(SERVICE_TYPE, DNSRecordType.TYPE_TXT, DNSRecordClass.CLASS_IN, DNSRecordClass.NOT_UNIQUE));
                ((JmDNSImpl) jmdns).send(out);
            } catch (Exception e) {
                Log.e(TAG, "Rescan failed", e);
                LocalBroadcasts.displayToast(svc, "Rescan failed: " + e.getMessage(), Toast.LENGTH_LONG);
            }
        });
    }

    void unregister() {
        svc.executor.submit(()->{
            Log.d(TAG, "Unregistering");
            try {
                DNSOutgoing out = new DNSOutgoing(DNSConstants.FLAGS_QR_RESPONSE | DNSConstants.FLAGS_AA);
                for (DNSRecord answer : ((ServiceInfoImpl) serviceInfo).answers(DNSRecordClass.CLASS_ANY,
                        DNSRecordClass.UNIQUE, 0, ((JmDNSImpl)jmdns).getLocalHost())) {
                    out = renewer.addAnswer(out, null, answer);
                }
                ((JmDNSImpl)jmdns).send(out);
            } catch (Exception e) {
                Log.e(TAG, "Unregistering failed", e);
                LocalBroadcasts.displayToast(svc, "Unregistering failed: " + e.getMessage(), Toast.LENGTH_LONG);
            }
        });
    }

    void addRemote(Remote remote) {
        //Add to remotes list
        MainService.remotes.put(remote.uuid, remote);
        svc.notifyDeviceCountUpdate();
        if (favorites.contains(remote.uuid)) //Add favorites at the beginning
            MainService.remotesOrder.add(0, remote.uuid);
        else
            MainService.remotesOrder.add(remote.uuid);
        //Connect to it
        remote.connect();
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
                    if(props.contains("auth-port"))
                        r.authPort = Integer.parseInt(info.getPropertyString("auth-port"));
                    InetAddress addr = getIPv4Address(info.getInetAddresses());
                    if (addr != null)
                        r.address = addr;
                    r.port = info.getPort();
                    r.serviceAvailable = true;
                    if ((r.status == Remote.RemoteStatus.DISCONNECTED) || (r.status == Remote.RemoteStatus.ERROR)) {
                        Log.d(TAG, "Reconnecting to " + r.hostname);
                        r.connect();
                    } else r.updateUI();
                    return;
                }

                Remote remote = new Remote();
                InetAddress addr = getIPv4Address(info.getInetAddresses());
                if (addr != null)
                    remote.address = addr;
                else remote.address = info.getInetAddresses()[0];
                if(props.contains("hostname"))
                    remote.hostname = info.getPropertyString("hostname");
                if(props.contains("api-version"))
                    remote.api = Integer.parseInt(info.getPropertyString("api-version"));
                if(props.contains("auth-port"))
                    remote.authPort = Integer.parseInt(info.getPropertyString("auth-port"));
                remote.port = info.getPort();
                remote.serviceName = svcName;
                remote.uuid = svcName;
                remote.serviceAvailable = true;

                addRemote(remote);
            }
        };
    }

    public static Bitmap getProfilePicture(String picture, Context ctx) {
        int[] colors = new int[]{0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff3f51b5, 0xff2196f3, 0xff4caf50,
                0xff8bc34a, 0xffcddc39, 0xffffeb3b, 0xffffc107, 0xffff9800, 0xffff5722};
        if (picture.startsWith("content")) {
            // Legacy: load from persisted uri
            try {
                return MediaStore.Images.Media.getBitmap(ctx.getContentResolver(), Uri.parse(picture));
            } catch (Exception e) {
                picture = "0";
            }
        } else if ("profilePic.png".equals(picture)) {
            try {
                var is = ctx.openFileInput("profilePic.png");
                return BitmapFactory.decodeStream(is);
            } catch (Exception e) {
                Log.e(TAG, "Could not load profile pic", e);
                picture = "0";
            }
        }
        int i = Integer.parseInt(picture); //Could be also a content uri in the future
        Drawable foreground = ResourcesCompat.getDrawable(ctx.getResources(), R.drawable.ic_warpinator, null);
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

    private InetAddress getIPv4Address(InetAddress[] addresses) {
        for (InetAddress a : addresses){
            if (a instanceof Inet4Address)
                return a;
        }
        return null;
    }
}
