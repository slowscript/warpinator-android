package slowscript.warpinator;

import android.util.Log;

import java.util.Arrays;

import io.grpc.Status;
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
        Log.d(TAG, "Duplex check: " + haveDuplex);
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

        Transfer t = new Transfer();
        t.direction = Transfer.Direction.RECEIVE;
        t.otherUUID = remoteUUID;
        t.startTime = request.getInfo().getTimestamp();
        t.status = Transfer.Status.WAITING_PERMISSION;
        t.totalSize = request.getSize();
        t.fileCount = request.getCount();
        t.singleMime = request.getMimeIfSingle();
        t.singleName = request.getNameIfSingle();
        Object[] a = request.getTopDirBasenamesList().toArray();
        t.topLevelDirs = Arrays.copyOf(a, a.length, String[].class);

        r.transfers.add(t);
        t.prepareReceive();

        returnVoid(responseObserver);

        //FIXME: Remove hack
        r.startReceiveTransfer(t);
    }

    @Override
    public void pauseTransferOp(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.pauseTransferOp(request, responseObserver);
    }

    @Override
    public void acceptTransferOpRequest(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.acceptTransferOpRequest(request, responseObserver);
    }

    @Override
    public void startTransfer(WarpProto.OpInfo request, StreamObserver<WarpProto.FileChunk> responseObserver) {
        super.startTransfer(request, responseObserver);
    }

    @Override
    public void cancelTransferOpRequest(WarpProto.OpInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.cancelTransferOpRequest(request, responseObserver);
    }

    @Override
    public void stopTransfer(WarpProto.StopInfo request, StreamObserver<WarpProto.VoidType> responseObserver) {
        super.stopTransfer(request, responseObserver);
    }

    @Override
    public void ping(WarpProto.LookupName request, StreamObserver<WarpProto.VoidType> responseObserver) {
        returnVoid(responseObserver);
    }

    void returnVoid(StreamObserver<WarpProto.VoidType> responseObserver) {
        responseObserver.onNext(WarpProto.VoidType.getDefaultInstance());
        responseObserver.onCompleted();
    }
}
