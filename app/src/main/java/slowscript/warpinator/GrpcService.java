package slowscript.warpinator;

import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.grpc.Status;
import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;

public class GrpcService extends WarpGrpc.WarpImplBase {
    static String TAG = "GRPC";

    @Override
    public void checkDuplexConnection(WarpProto.LookupName request, StreamObserver<WarpProto.HaveDuplex> responseObserver) {
        String id = request.getId();

        boolean haveDuplex = false;
        if(MainActivity.ctx.remotes.containsKey(id)) {
            Remote r = MainActivity.ctx.remotes.get(id);
            haveDuplex = (r.status == Remote.RemoteStatus.CONNECTED)
                        || (r.status == Remote.RemoteStatus.AWAITING_DUPLEX);
        }
        Log.d(TAG, "Duplex check result: " + haveDuplex);
        responseObserver.onNext(WarpProto.HaveDuplex.newBuilder().setResponse(haveDuplex).build());
        responseObserver.onCompleted();
    }

    @Override
    public void getRemoteMachineInfo(WarpProto.LookupName request, StreamObserver<WarpProto.RemoteMachineInfo> responseObserver) {
        responseObserver.onNext(WarpProto.RemoteMachineInfo.newBuilder()
                .setDisplayName("Android").setUserName("android").build()); //TODO: Set username and picture in settings
        responseObserver.onCompleted();
    }

    @Override
    public void getRemoteMachineAvatar(WarpProto.LookupName request, StreamObserver<WarpProto.RemoteMachineAvatar> responseObserver) {
        responseObserver.onError(new io.grpc.StatusException(Status.NOT_FOUND));
    }

    @Override
    public void processTransferOpRequest(WarpProto.TransferOpRequest request, StreamObserver<WarpProto.VoidType> responseObserver) {
        String remoteUUID = request.getInfo().getIdent();
        Remote r = MainActivity.ctx.remotes.get(remoteUUID);
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
        t.status = Transfer.Status.WAITING_PERMISSION;
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
        Remote r = MainActivity.ctx.remotes.get(remoteUUID);
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
