package slowscript.warpinator;

import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;

import javax.net.ssl.SSLException;

import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;

public class Remote {
    public enum RemoteStatus {
        CONNECTED,
        DISCONNECTED,
        CONNECTING, //Connect thread running -> Don't touch data!!
        ERROR, //Failed to connect
        AWAITING_DUPLEX
    }

    static String TAG = "Remote";

    public InetAddress address;
    public int port;
    public String serviceName; //Zeroconf service name
    public String userName;
    public String hostname;
    public String displayName;
    public String uuid;

    public RemoteStatus status;

    ArrayList<Transfer> transfers = new ArrayList<>();
    Transfer t; //Current transfer

    ManagedChannel channel;
    WarpGrpc.WarpBlockingStub blockingStub;
    WarpGrpc.WarpStub asyncStub;

    public void connect() {
        Log.i(TAG, "Connecting to " + hostname);
        status = RemoteStatus.CONNECTING;
        new Thread(() -> {
            //Receive certificate
            if (!receiveCertificate()) {
                status = RemoteStatus.ERROR;
                return;
            }
            Log.d(TAG, "Certificate for " + hostname + " received and saved");

            //Authenticate
            try {
                channel = NettyChannelBuilder.forAddress(address.getHostAddress(), port)
                        .sslContext(GrpcSslContexts.forClient().trustManager(Authenticator.getCertificateFile(uuid)).build())
                        .build();
                blockingStub = WarpGrpc.newBlockingStub(channel);
                asyncStub = WarpGrpc.newStub(channel);
            } catch (SSLException e) {
                Log.e(TAG, "Authentication with remote "+ hostname +" failed: " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                return;
            } catch (Exception e) { //Is this necessary?
                Log.e(TAG, "Failed to connect to remote " + hostname + ". " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                return;
            }

            status = RemoteStatus.AWAITING_DUPLEX;

            //Get duplex
            if (!waitForDuplex()) {
                Log.e(TAG, "Couldn't establish duplex with " + hostname);
                status = RemoteStatus.ERROR;
                return;
            }

            //Good to go
            status = RemoteStatus.CONNECTED;
            WarpProto.RemoteMachineInfo info = blockingStub.getRemoteMachineInfo(WarpProto.LookupName.getDefaultInstance());
            displayName = info.getDisplayName();
            userName = info.getUserName();

            MainActivity.ctx.updateRemoteList();
            Log.i(TAG, "Connection established with " + hostname);
        }).start();
    }

    public void disconnect() {
        Log.i(TAG, "Disconnecting " + hostname);
        channel.shutdownNow();
        status = RemoteStatus.DISCONNECTED;
    }

    public void sendFiles() {

    }

    public void startReceiveTransfer(Transfer _t) {
        t = _t;
        new Thread(() -> {
            WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder().setIdent(Server.current.uuid)
                    .setTimestamp(t.startTime).setReadableName(Utils.getDeviceName()).build();
            Iterator<WarpProto.FileChunk> i = blockingStub.startTransfer(info);
            while (i.hasNext()) {
                WarpProto.FileChunk c = i.next();
                t.receiveFileChunk(c);
            }
            t.finishReceive();
            //TODO: Set UI to finished, show errors
        }).start();
    }

    // -- PRIVATE HELPERS --

    private boolean receiveCertificate() {
        byte[] received = null;
        int tryCount = 0;
        while (tryCount < 3) {
            try {
                Log.v(TAG, "Receiving certificate from " + address.toString() + ", try " + tryCount);
                DatagramSocket sock = new DatagramSocket();
                sock.setSoTimeout(1000);

                byte[] req = CertServer.REQUEST.getBytes();
                DatagramPacket p = new DatagramPacket(req, req.length, address, port);
                sock.send(p);

                byte[] receiveData = new byte[2000];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                sock.receive(receivePacket);

                if (receivePacket.getAddress().equals(address) && (receivePacket.getPort() == port)) {
                    received = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
                    sock.close();
                    break;
                } //Received from wrong host. Give it another shot.
            } catch (Exception e) {
                tryCount++;
                Log.e(TAG, "What is this?", e);
                try {
                    Thread.sleep(1000);
                } catch (Exception ignored) { }
            }
        }
        if (tryCount == 3) {
            Log.e(TAG, "Failed to receive certificate from " + hostname);
            return false;
        }
        byte[] decoded = Base64.decode(received, Base64.DEFAULT);
        Authenticator.saveBoxedCert(decoded, uuid);
        return true;
    }

    private boolean waitForDuplex() {
        int tries = 0;
        while (tries < 5) {
            try {
                boolean haveDuplex = blockingStub.checkDuplexConnection(
                        WarpProto.LookupName.newBuilder()
                        .setId(Server.current.uuid).setReadableName("Android").build())
                        .getResponse();
                if (haveDuplex)
                    return true;
            } catch (Exception e) {
                Log.d (TAG, "Attempt " + tries + ": No duplex", e);
            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) { throw new RuntimeException(e); }

            tries++;
        }
        return false;
    }
}
