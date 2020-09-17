package slowscript.warpinator;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.conscrypt.Conscrypt;

import java.io.File;
import java.security.Security;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;

import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyServerBuilder;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;

public class Server {
    private String TAG = "SRV";

    public int PORT;
    public static final String SERVICE_TYPE = "_warpinator._tcp.";
    public static Server current;
    public String uuid;

    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;
    private io.grpc.Server gServer;

    private MainActivity app;

    public Server(int port, MainActivity _app) {
        PORT = port;
        app = _app;

        current = this;
        Security.insertProviderAt(Conscrypt.newProvider(), 1);
        uuid = "android-uuid";//UUID.randomUUID().toString(); //FIXME: Save UUID and reuse!!

        nsdManager = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        registrationListener = new RegistrationListener();
        discoveryListener = new DiscoveryListener();
    }

    public void Start() {
        Authenticator.getServerCertificate(); //Generate cert on start if doesn't exist
        Utils.getSaveDir().mkdirs();
        CertServer.Start(PORT);
        registerService();
        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
        startGrpcServer();
    }

    public void Stop() {
        CertServer.Stop();
        nsdManager.unregisterService(registrationListener);
        nsdManager.stopServiceDiscovery(discoveryListener);
        gServer.shutdownNow();
        Log.i(TAG, "Server stopped");
    }

    void startGrpcServer() {
        try {
            File cert = new File(Utils.getCertsDir(), ".self.pem");
            File key = new File(Utils.getCertsDir(), ".self.key-pem");
            SslContextBuilder ssl = GrpcSslContexts.forServer(cert, key).sslContextProvider(Conscrypt.newProvider());
            //SslContextBuilder ssl = GrpcSslContexts.configure(SslContextBuilder.forServer(cert, key), Conscrypt.newProvider());
            gServer = NettyServerBuilder.forPort(PORT)
                    .sslContext(ssl.build())
                    .addService(new GrpcService())
                    .build();
            gServer.start();
            Log.d(TAG, "GRPC server started");
        } catch(Exception e) {
            Log.e(TAG, "Failed to start GRPC server.", e);
            Toast.makeText(app, "Failed to start GRPC server", Toast.LENGTH_LONG).show();
        }
    }

    void registerService() {
        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        Log.d(TAG, "Registering as " + uuid);
        serviceInfo.setServiceName(uuid);
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(PORT);

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

    class RegistrationListener implements NsdManager.RegistrationListener {
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
                if (new String(info.getAttributes().get("type")).equals("flush")) {
                    Log.v(TAG, "Ignoring \"flush\" registration");
                    return;
                }

                //TODO: Check if remote exists

                Remote remote = new Remote();
                remote.address = info.getHost();
                remote.hostname = new String(info.getAttributes().get("hostname"));
                remote.port = info.getPort();
                remote.serviceName = info.getServiceName();
                remote.uuid = info.getServiceName();

                app.addRemote(remote);
            }
        };
    }
}
