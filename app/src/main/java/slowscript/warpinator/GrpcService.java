package slowscript.warpinator;

import android.util.Log;

import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public class GrpcService extends WarpGrpc.WarpImplBase {
    static String TAG = "GRPC";

    @Override
    public void checkDuplexConnection(WarpProto.LookupName request, StreamObserver<WarpProto.HaveDuplex> responseObserver) {
        String id = request.getId();

        boolean haveDuplex = false;
        if(MainService.remotes.containsKey(id)) {
            Remote r = MainService.remotes.get(id);
            haveDuplex = (r.status == Remote.RemoteStatus.CONNECTED)
                        || (r.status == Remote.RemoteStatus.AWAITING_DUPLEX);
            //The other side is trying to connect with use after a connection failed
            if (r.status == Remote.RemoteStatus.ERROR || r.status == Remote.RemoteStatus.DISCONNECTED) {
                // Update IP address
                r.address = Server.current.jmdns.getServiceInfo(Server.SERVICE_TYPE, r.uuid).getInetAddresses()[0];
                r.port = Server.current.jmdns.getServiceInfo(Server.SERVICE_TYPE, r.uuid).getPort();
                Log.v(TAG, "new ip for remote: " + r.address);
                r.connect(); //Try reconnecting
            }
        }
        Log.d(TAG, "Duplex check result: " + haveDuplex);
        responseObserver.onNext(WarpProto.HaveDuplex.newBuilder().setResponse(haveDuplex).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRemoteMachineInfo(WarpProto.LookupName request, StreamObserver<WarpProto.RemoteMachineInfo> responseObserver) {
        responseObserver.onNext(WarpProto.RemoteMachineInfo.newBuilder()
                .setDisplayName(Server.current.displayName).setUserName("android").build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRemoteMachineAvatar(WarpProto.LookupName request, StreamObserver<WarpProto.RemoteMachineAvatar> responseObserver) {
        responseObserver.onNext(WarpProto.RemoteMachineAvatar.newBuilder()
                .setAvatarChunk(Server.current.getProfilePictureBytes()).build());
        responseObserver.onCompleted();
    }

    @Override
    public void processTransferOpRequest(WarpProto.TransferOpRequest request, StreamObserver<WarpProto.VoidType> responseObserver) {
        String remoteUUID = request.getInfo().getIdent();
        Remote r = MainService.remotes.get(remoteUUID);
        if (r == null) {
            Log.w(TAG, "Received transfer request from unknown remote");
            returnVoid(responseObserver);
            return;
        }
        Log.i(TAG, "Receiving transfer from " + r.userName);

        Transfer t = new Transfer();
        t.direction = Transfer.Direction.RECEIVE;
        t.remoteUUID = remoteUUID;
        t.startTime = request.getInfo().getTimestamp();
        t.setStatus(Transfer.Status.WAITING_PERMISSION);
        t.totalSize = request.getSize();
        t.fileCount = request.getCount();
        t.singleMime = request.getMimeIfSingle();
        t.singleName = request.getNameIfSingle();
        t.topDirBasenames = request.getTopDirBasenamesList();

        r.transfers.add(t);
        t.privId = r.transfers.size()-1;
        t.prepareReceive();

        returnVoid(responseObserver);
    }

    @Override
    public void pauseTransferOp(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.pauseTransferOp(request, responseObserver); //Not implemented in upstream either
    }

    @Override
    public void acceptTransferOpRequest(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.acceptTransferOpRequest(request, responseObserver); //Not implemented in upstream either
    }

    @Override
    public void startTransfer(WarpProto.OpInfo request, StreamObserver<WarpProto.FileChunk> responseObserver) {
        Log.d(TAG, "Transfer started by the other side");
        Transfer t = getTransfer(request);
        if (t == null)
            return;
        t.startSending((ServerCallStreamObserver<WarpProto.FileChunk>) responseObserver);
    }

    @Override
    public void cancelTransferOpRequest(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        Log.d(TAG, "Transfer cancelled by the other side");
        Transfer t = getTransfer(request);
        if (t == null) {
            returnVoid(responseObserver);
            return;
        }
        t.makeDeclined();

        returnVoid(responseObserver);
    }

    @Override
    public void stopTransfer(WarpProto.StopInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        Log.d(TAG, "Transfer stopped by the other side");
        Transfer t = getTransfer(request.getInfo());
        if (t == null) {
            returnVoid(responseObserver);
            return;
        }
        t.onStopped(request.getError());

        returnVoid(responseObserver);
    }

    @Override
    public void ping(WarpProto.LookupName request, StreamObserver<WarpProto.VoidType> responseObserver) {
        returnVoid(responseObserver);
    }

    Transfer getTransfer(WarpProto.OpInfo info) {
        String remoteUUID = info.getIdent();
        Remote r = MainService.remotes.get(remoteUUID);
        if (r == null) {
            Log.w(TAG, "Could not find corresponding remote");
            return null;
        }
        Transfer t = r.findTransfer(info.getTimestamp());
        if (t == null) {
            Log.w(TAG, "Could not find corresponding transfer");
        }
        return t;
    }

    void returnVoid(StreamObserver<WarpProto.VoidType> responseObserver) {
        responseObserver.onNext(WarpProto.VoidType.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
