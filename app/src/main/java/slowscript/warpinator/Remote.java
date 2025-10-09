package slowscript.warpinator;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import com.google.protobuf.ByteString;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

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
    public int authPort;
    public int api = 1;
    public String serviceName; //Zeroconf service name, also uuid
    public String userName = "";
    public String hostname;
    public String displayName;
    public String uuid;
    public Bitmap picture;
    public volatile RemoteStatus status;
    public boolean serviceAvailable;
    public boolean staticService = false;

    //Error flags
    public boolean errorGroupCode = false; //Shown once by RemotesAdapter or TransfersActivity
    public boolean errorReceiveCert = false; //Shown every time remote is opened until resolved
    public String errorText = "";

    ArrayList<Transfer> transfers = new ArrayList<>();

    ManagedChannel channel;
    WarpGrpc.WarpBlockingStub blockingStub;
    WarpGrpc.WarpStub asyncStub;

    public void connect() {
        Log.i(TAG, "Connecting to " + hostname + ", api " + api);
        status = RemoteStatus.CONNECTING;
        updateUI();
        new Thread(() -> {
            //Receive certificate
            if (!receiveCertificate()) {
                status = RemoteStatus.ERROR;
                if (errorGroupCode)
                    errorText = MainService.svc.getString(R.string.wrong_group_code);
                else
                    errorText = "Couldn't receive certificate - check firewall";
                updateUI();
                return;
            }
            Log.d(TAG, "Certificate for " + hostname + " received and saved");

            //Connect
            try {
                OkHttpChannelBuilder builder = OkHttpChannelBuilder.forAddress(address.getHostAddress(), port)
                        .sslSocketFactory(Authenticator.createSSLSocketFactory(uuid))
                        .flowControlWindow(1280*1024);
                if (api >= 2) {
                    builder.keepAliveWithoutCalls(true)
                            .keepAliveTime(11, TimeUnit.SECONDS)
                            .keepAliveTimeout(5, TimeUnit.SECONDS);
                }
                if (channel != null && !channel.isShutdown())
                    channel.shutdown(); //just in case
                channel = builder.build();
                if (api >= 2)
                    channel.notifyWhenStateChanged(channel.getState(true), this::onChannelStateChanged);
                blockingStub = WarpGrpc.newBlockingStub(channel);
                asyncStub = WarpGrpc.newStub(channel);
                // Ensure connection is created, otherwise give correct error (not "duplex failed")
                blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS).ping(WarpProto.LookupName.newBuilder().setId(Server.current.uuid).build());
            } catch (SSLException e) {
                Log.e(TAG, "Authentication with remote "+ hostname +" failed: " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                errorText = "SSLException: " + e.getLocalizedMessage();
                updateUI();
                return;
            } catch (Exception e) {
                Log.e(TAG, "Failed to connect to remote " + hostname + ". " + e.getMessage(), e);
                status = RemoteStatus.ERROR;
                errorText = e.toString();
                updateUI();
                return;
            } finally {
                // Clean up channel on failure
                if (channel != null && status == RemoteStatus.ERROR)
                    channel.shutdownNow();
            }

            status = RemoteStatus.AWAITING_DUPLEX;
            updateUI();

            //Get duplex
            if (!waitForDuplex()) {
                Log.e(TAG, "Couldn't establish duplex with " + hostname);
                status = RemoteStatus.ERROR;
                errorText = MainService.svc.getString(R.string.error_no_duplex);
                updateUI();
                channel.shutdown();
                return;
            }

            //Connection ready
            status = RemoteStatus.CONNECTED;

            //Get name
            try {
                WarpProto.RemoteMachineInfo info = blockingStub.getRemoteMachineInfo(WarpProto.LookupName.getDefaultInstance());
                displayName = info.getDisplayName();
                userName = info.getUserName();
            } catch (StatusRuntimeException ex) {
                status = RemoteStatus.ERROR;
                errorText = "Couldn't get username: " + ex.toString();
                Log.e(TAG, "connect: cannot get name: connection broken?", ex);
                updateUI();
                channel.shutdown();
                return;
            }
            //Get avatar
            try {
                Iterator<WarpProto.RemoteMachineAvatar> avatar = blockingStub.getRemoteMachineAvatar(WarpProto.LookupName.getDefaultInstance());
                ByteString bs = avatar.next().getAvatarChunk();
                while (avatar.hasNext()) {
                    WarpProto.RemoteMachineAvatar a = avatar.next();
                    bs.concat(a.getAvatarChunk());
                }
                byte[] bytes = bs.toByteArray();
                picture = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } catch (Exception e) { //Profile picture not found, etc.
                picture = null;
            }

            updateUI();
            Log.i(TAG, "Connection established with " + hostname);
        }).start();
    }

    public void disconnect() {
        Log.i(TAG, "Disconnecting " + hostname);
        try {
            channel.shutdownNow();
        } catch (Exception ignored){}
        status = RemoteStatus.DISCONNECTED;
    }

    private void onChannelStateChanged() {
        ConnectivityState state = channel.getState(false);
        Log.d(TAG, "onChannelStateChanged: " + hostname + " -> " + state);
        if (state == ConnectivityState.TRANSIENT_FAILURE || state == ConnectivityState.IDLE) {
            status = RemoteStatus.DISCONNECTED;
            updateUI();
            channel.shutdown(); //Dispose of channel so it can be recreated if device comes back
        }
        channel.notifyWhenStateChanged(state, this::onChannelStateChanged);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    @SuppressLint("CheckResult")
    public void ping() {
        try {
            //Log.v(TAG, "Pinging " + hostname);
            blockingStub.withDeadlineAfter(10L, TimeUnit.SECONDS).ping(WarpProto.LookupName.newBuilder().setId(Server.current.uuid).build());
        } catch (Exception e) {
            Log.d(TAG, "ping: Failed with exception", e);
            status = RemoteStatus.DISCONNECTED;
            updateUI();
            channel.shutdown();
        }
    }

    public boolean isFavorite() {
        return Server.current.favorites.contains(uuid);
    }

    // This does not update uuid, ip and authPort
    void updateFromServiceRegistration(WarpProto.ServiceRegistration reg) {
        hostname = reg.getHostname();
        api = reg.getApiVersion();
        port = reg.getPort();
        serviceName = reg.getServiceId();
        //r.serviceAvailable = true;
        staticService = true;
    }

    boolean sameSubnetWarning() {
        if (status == RemoteStatus.CONNECTED)
            return false;
        if (MainService.svc.currentIPInfo == null)
            return false;
        return !Utils.isSameSubnet(address, MainService.svc.currentIPInfo.address, MainService.svc.currentIPInfo.prefixLength);
    }

    public Transfer findTransfer(long timestamp) {
        for (Transfer t : transfers) {
            if(t.startTime == timestamp)
                return t;
        }
        return null;
    }

    public void addTransfer(Transfer t) {
        transfers.add(0, t);
        updateTransferIdxs();
    }

    public void clearTransfers() {
        Iterator<Transfer> txs = transfers.iterator();
        while (txs.hasNext()) {
            Transfer t = txs.next();
            if (t.getStatus() == Transfer.Status.FINISHED || t.getStatus() == Transfer.Status.DECLINED ||
                    t.getStatus() == Transfer.Status.FINISHED_WITH_ERRORS || t.getStatus() == Transfer.Status.FAILED ||
                    t.getStatus() == Transfer.Status.STOPPED)
                txs.remove();
        }
        updateTransferIdxs();
        LocalBroadcasts.updateTransfers(MainService.svc, uuid);
    }

    private void updateTransferIdxs() {
        for (int i = 0; i < transfers.size(); i++)
            transfers.get(i).privId = i;
    }

    public void startSendTransfer(Transfer t) {
        t.useCompression = Server.current.useCompression;
        WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .setUseCompression(t.useCompression)
                .build();
        WarpProto.TransferOpRequest op = WarpProto.TransferOpRequest.newBuilder()
                .setInfo(info)
                .setSenderName("Android")
                .setReceiver(uuid)
                .setSize(t.totalSize)
                .setCount(t.fileCount)
                .setNameIfSingle(t.singleName)
                .setMimeIfSingle(t.singleMime)
                .addAllTopDirBasenames(t.topDirBasenames)
                .build();
        asyncStub.processTransferOpRequest(op, new Utils.VoidObserver());
    }

    public void startReceiveTransfer(Transfer _t) {
        new Thread(() -> {
            Transfer t = _t; //_t gets garbage collected or something...
            WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                    .setIdent(Server.current.uuid)
                    .setTimestamp(t.startTime)
                    .setReadableName(Utils.getDeviceName())
                    .setUseCompression(t.useCompression).build();
            try {
                Iterator<WarpProto.FileChunk> i = blockingStub.startTransfer(info);
                boolean cancelled = false;
                while (i.hasNext() && !cancelled) {
                    WarpProto.FileChunk c = i.next();
                    cancelled = !t.receiveFileChunk(c);
                }
                if (!cancelled)
                    t.finishReceive();
            } catch (StatusRuntimeException e) {
                if (e.getStatus().getCode() == Status.Code.CANCELLED) {
                    Log.i(TAG, "Transfer cancelled", e);
                    t.setStatus(Transfer.Status.STOPPED);
                } else {
                    Log.e(TAG, "Connection error", e);
                    t.errors.add("Connection error: " + e.getLocalizedMessage());
                    t.setStatus(Transfer.Status.FAILED);
                }
                t.updateUI();
            } catch (Exception e) {
                Log.e(TAG, "Transfer error", e);
                t.errors.add("Transfer error: " + e.getLocalizedMessage());
                t.setStatus(Transfer.Status.FAILED);
                t.updateUI();
            }
        }).start();
    }

    public void declineTransfer(Transfer t) {
        WarpProto.OpInfo info = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .build();
        asyncStub.cancelTransferOpRequest(info, new Utils.VoidObserver());
    }

    public void stopTransfer(Transfer t, boolean error) {
        WarpProto.OpInfo i = WarpProto.OpInfo.newBuilder()
                .setIdent(Server.current.uuid)
                .setTimestamp(t.startTime)
                .setReadableName(Utils.getDeviceName())
                .build();
        WarpProto.StopInfo info = WarpProto.StopInfo.newBuilder()
                .setError(error)
                .setInfo(i)
                .build();
        asyncStub.stopTransfer(info, new Utils.VoidObserver());
    }

    // -- PRIVATE HELPERS --

    private boolean receiveCertificate() {
        errorGroupCode = false;
        if (api == 2) {
            if (receiveCertificateV2())
                return true; // Otherwise fall back in case of old port config etc...
            else if (errorGroupCode)
                return false;
            else
                Log.d(TAG, "Falling back to receiveCertificateV1");
        }
        return receiveCertificateV1();
    }

    private boolean receiveCertificateV1() {
        byte[] received = null;
        int tryCount = 0;
        while (tryCount < 3) {
            try (DatagramSocket sock = new DatagramSocket()) {
                Log.v(TAG, "Receiving certificate from " + address.toString() + ", try " + tryCount);
                sock.setSoTimeout(1500);

                byte[] req = CertServer.REQUEST.getBytes();
                DatagramPacket p = new DatagramPacket(req, req.length, address, port);
                sock.send(p);

                byte[] receiveData = new byte[2000];
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                sock.receive(receivePacket);

                if (receivePacket.getAddress().equals(address) && (receivePacket.getPort() == port)) {
                    received = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
                    break;
                } //Received from wrong host. Give it another shot.
            } catch (Exception e) {
                tryCount++;
                Log.d(TAG, "receiveCertificate: attempt " + tryCount + " failed: " + e.getMessage());
            }
        }
        if (tryCount == 3) {
            Log.e(TAG, "Failed to receive certificate from " + hostname);
            errorReceiveCert = true;
            return false;
        }
        byte[] decoded = Base64.decode(received, Base64.DEFAULT);
        errorGroupCode = !Authenticator.saveBoxedCert(decoded, uuid);
        if (errorGroupCode)
            return false;
        errorReceiveCert = false;
        return true;
    }

    private boolean receiveCertificateV2() {
        Log.v(TAG, "Receiving certificate (V2) from " + hostname + " at " + address.toString());
        ManagedChannel authChannel = null;
        try {
            authChannel = OkHttpChannelBuilder.forAddress(address.getHostAddress(), authPort)
                    .usePlaintext().build();
            WarpProto.RegResponse resp = WarpRegistrationGrpc.newBlockingStub(authChannel)
                    .withWaitForReady() //This will retry connection after 1s, then after exp. delay
                    .withDeadlineAfter(8, TimeUnit.SECONDS)
                    .requestCertificate(WarpProto.RegRequest.newBuilder()
                            .setHostname(Utils.getDeviceName())
                            .setIp(MainService.svc.getCurrentIPStr()).build()
                    );
            byte[] lockedCert = resp.getLockedCertBytes().toByteArray();
            byte[] decoded = Base64.decode(lockedCert, Base64.DEFAULT);
            errorGroupCode = !Authenticator.saveBoxedCert(decoded, uuid);
            if (errorGroupCode)
                return false;
            errorReceiveCert = false;
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Could not receive certificate from " + hostname, e);
            errorReceiveCert = true;
        } finally {
            if (authChannel != null)
                authChannel.shutdownNow();
        }
        return false;
    }

    private boolean waitForDuplex() {
        if (api == 2)
            return waitForDuplexV2();
        else return waitForDuplexV1();
    }

    private boolean waitForDuplexV1() {
        Log.d(TAG, "Waiting for duplex - V1");
        int tries = 0;
        while (tries < 10) {
            try {
                boolean haveDuplex = blockingStub.checkDuplexConnection(
                        WarpProto.LookupName.newBuilder()
                        .setId(Server.current.uuid).setReadableName("Android").build())
                        .getResponse();
                if (haveDuplex)
                    return true;
            } catch (Exception e) {
               Log.d(TAG, "Error while checking duplex", e);
               return false;
            }
            Log.d (TAG, "Attempt " + tries + ": No duplex");
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) { throw new RuntimeException(e); }

            tries++;
        }
        return false;
    }

    private boolean waitForDuplexV2() {
        Log.d(TAG, "Waiting for duplex - V2");
        try {
            return blockingStub.withDeadlineAfter(10, TimeUnit.SECONDS)
                    .waitingForDuplex(WarpProto.LookupName.newBuilder()
                            .setId(Server.current.uuid)
                            .setReadableName(Utils.getDeviceName()).build())
                    .getResponse();
        } catch (Exception e) {
            Log.d(TAG, "Error while waiting for duplex", e);
            return false;
        }
    }

    public void updateUI() {
        LocalBroadcasts.updateRemotes(MainService.svc);
    }
}
