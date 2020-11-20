package slowscript.warpinator;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import android.widget.Toast;

import org.conscrypt.Conscrypt;

import java.io.File;
import java.security.Security;
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

    private final NsdManager nsdManager;
    private final NsdManager.RegistrationListener registrationListener;
    private final NsdManager.DiscoveryListener discoveryListener;
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

        svc.prefs.registerOnSharedPreferenceChangeListener((p, k) -> loadSettings());
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

        //Put boxed cert into attributes
        /*byte[] box = Authenticator.getBoxedCertificate(serviceName);
        String encoded = Base64.encodeToString(box, Base64.DEFAULT);
        encoded = encoded.replace('=', '*'); //Attributes cannot contain '='
        //Log.d(TAG, Utils.bytesToHex(box));
        String[] enc_array = encoded.split("\n");
        int i = 0;
        for (; i < enc_array.length; i++) {
            serviceInfo.setAttribute(String.valueOf(i), enc_array[i]);
        }
        serviceInfo.setAttribute(String.valueOf(i), "");*/
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
            Log.v(TAG, "Service lost: " + info.getServiceName());
            //remotes.remove()
            //remotes.removeIf(r -> r.serviceName == info.getServiceName());
            //TODO: Remove remote
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

                svc.addRemote(remote);
            }
        };
    }
}
