package slowscript.warpinator;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.grpc.StatusException;
import io.grpc.stub.CallStreamObserver;

import static slowscript.warpinator.MainService.svc;

public class Transfer {
    public enum Direction { SEND, RECEIVE }
    public enum Status { INITIALIZING, WAITING_PERMISSION, DECLINED,
        TRANSFERRING, PAUSED, STOPPED,
        FAILED, FAILED_UNRECOVERABLE, FILE_NOT_FOUND, FINISHED, FINISHED_WITH_ERRORS
    }
    static final class FileType {
        static final int FILE = 1; static final int DIRECTORY = 2; static final int SYMLINK = 3;
    }

    static final String TAG = "TRANSFER";
    static int CHUNK_SIZE = 1024 * 512; //512 kB

    public Status status;
    public Direction direction;
    public String remoteUUID;
    public long startTime;
    public long totalSize;
    public long fileCount;
    public String singleName = "";
    public String singleMime = "";
    public List<String> topDirBasenames;
    int privId;
    //SEND only
    public ArrayList<Uri> uris;

    private String currentRelativePath;
    private FileOutputStream currentStream;
    private ArrayList<String> errors = new ArrayList<>();
    private boolean cancelled = false;
    public long bytesTransferred;
    public long bytesPerSecond;
    long lastMillis = 0;

    // -- COMMON --
    public void stop(boolean error) {
        Log.i(TAG, "Transfer stopped");
        MainService.remotes.get(remoteUUID).stopTransfer(this, error);
        onStopped(error);
    }

    public void onStopped(boolean error) {
        if (direction == Transfer.Direction.RECEIVE)
            stopReceiving();
        else stopSending();
        if (!error)
            status = Status.STOPPED;
        updateUI();
    }

    public void makeDeclined() {
        status = Status.DECLINED;
        updateUI();
    }

    public int getProgress() {
        return (int)((float)bytesTransferred / totalSize * 100f);
    }

    void updateUI() {
        if (svc.transfersView != null)
            svc.transfersView.updateTransfer(remoteUUID, privId);
        //TODO: update notification
    }

    // -- SEND --
    public void prepareSend() {
        //Only uris and remoteUUID are set from before
        status = Status.WAITING_PERMISSION;
        direction = Direction.SEND;
        startTime = System.currentTimeMillis();
        totalSize = getTotalSendSize();
        fileCount = uris.size();
        topDirBasenames = new ArrayList<>();
        for (Uri u : uris) {
            topDirBasenames.add(Utils.getNameFromUri(svc, u));
        }
        if (fileCount == 1) {
            singleName = topDirBasenames.get(0);
            singleMime = svc.getContentResolver().getType(uris.get(0));
        }
    }

    public void startSending(CallStreamObserver<WarpProto.FileChunk> observer) {
        status = Status.TRANSFERRING;
        updateUI();
        observer.setOnReadyHandler(new Runnable() {
            int i = 0;
            InputStream is;
            byte[] chunk = new byte[CHUNK_SIZE];

            @Override
            public void run() {
                while (observer.isReady()) {
                    try {
                        if (cancelled) { //Exit if cancelled
                            observer.onError(new StatusException(io.grpc.Status.CANCELLED));
                            is.close();
                            return;
                        }
                        if (is == null) {
                            is = svc.getContentResolver().openInputStream(uris.get(i));
                        }
                        if (is.available() < 1) {
                            is.close();
                            is = null;
                            i++;
                            if (i >= uris.size()) {
                                observer.onCompleted();
                                status = Status.FINISHED;
                                updateUI();
                            }
                            continue;
                        }
                        int read = is.read(chunk);
                        WarpProto.FileChunk fc = WarpProto.FileChunk.newBuilder()
                                .setRelativePath(Utils.getNameFromUri(svc, uris.get(i)))
                                .setFileType(FileType.FILE)
                                .setChunk(ByteString.copyFrom(chunk, 0, read))
                                .setFileMode(644)
                                .build();
                        observer.onNext(fc);
                        bytesTransferred += read;
                        long now = System.currentTimeMillis();
                        bytesPerSecond = (long)(read / ((now - lastMillis) / 1000f));
                        lastMillis = now;
                        updateUI();
                    } catch (FileNotFoundException e) {
                        observer.onError(new StatusException(io.grpc.Status.NOT_FOUND));
                        errors.add(e.getLocalizedMessage());
                        status = Status.FAILED;
                        updateUI();
                        return;
                    } catch (Exception e) {
                        observer.onError(e);
                        status = Status.FAILED;
                        errors.add(e.getLocalizedMessage());
                        updateUI();
                        return;
                    }
                }
            }
        });
    }

    private void stopSending() {
        cancelled = true;
    }

    long getTotalSendSize() {
        long size = 0;
        for (Uri u : uris) {
            Cursor cursor = svc.getContentResolver().query(u, null, null, null, null);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            cursor.moveToFirst();
            size += cursor.getLong(sizeIndex);
            cursor.close();
        }
        return size;
    }

    // -- RECEIVE --
    public void prepareReceive() {
        if (BuildConfig.DEBUG && direction != Direction.RECEIVE) {
            throw new AssertionError("Assertion failed");
        }
        //Check enough space

        //Check if will rewrite

        //Show in UI
        if (svc.transfersView != null && remoteUUID.equals(svc.transfersView.remote.uuid) && svc.transfersView.isTopmost)
            svc.transfersView.updateTransfers(remoteUUID);
        else {  //Notification
            Intent intent = new Intent(svc, TransfersActivity.class);
            intent.putExtra("remote", remoteUUID);
            PendingIntent pendingIntent = PendingIntent.getActivity(svc, 0, intent, 0);
            Notification notification = new NotificationCompat.Builder(svc, MainService.CHANNEL_INCOMING)
                    .setContentTitle("Incoming transfer from " + MainService.remotes.get(remoteUUID).displayName)
                    .setContentText(fileCount == 1 ? singleName : fileCount + " files")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            svc.notificationMgr.notify(svc.notifId++, notification);
        }
    }

    void startReceive() {
        Log.i(TAG, "Transfer accepted");
        status = Status.TRANSFERRING;
        MainService.remotes.get(remoteUUID).startReceiveTransfer(this);
    }

    void declineReceiveTransfer() {
        Log.i(TAG, "Transfer declined");
        MainService.remotes.get(remoteUUID).declineTransfer(this);
        makeDeclined();
    }

    public boolean receiveFileChunk(WarpProto.FileChunk chunk) {
        long chunkSize = 0;
        if (!chunk.getRelativePath().equals(currentRelativePath)) {
            //End old file
            closeStream();
            //Begin new file
            currentRelativePath = chunk.getRelativePath();
            File path = new File(Utils.getSaveDir(), currentRelativePath); //FIXME: Can this escape saveDir?
            if (chunk.getFileType() == FileType.DIRECTORY) {
                if (!path.mkdirs()) {
                    errors.add("Failed to create directory " + path.getAbsolutePath());
                    Log.e(TAG, "Failed to create directory " + path.getAbsolutePath());
                }
            }
            else if (chunk.getFileType() == FileType.SYMLINK) {
                Log.e(TAG, "Symlinks not supported.");
                errors.add("Symlinks not supported.");
            }
            else {
                try {
                    if(path.exists())
                        path.delete();
                    currentStream = new FileOutputStream(path, false);
                    currentStream.write(chunk.getChunk().toByteArray());
                    chunkSize = chunk.getChunk().size();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open file for writing: " + currentRelativePath, e);
                    errors.add("Failed to open file for writing: " + currentRelativePath);
                }
            }
        } else {
            try {
                currentStream.write(chunk.getChunk().toByteArray());
                chunkSize = chunk.getChunk().size();
            } catch (Exception e) {
                Log.e(TAG, "Failed to write to file " + currentRelativePath, e);
                errors.add("Failed to write to file " + currentRelativePath);
                //failReceive();
            }
        }
        bytesTransferred += chunkSize;
        long now = System.currentTimeMillis();
        bytesPerSecond = (long)(chunkSize / ((now - lastMillis) / 1000f));
        lastMillis = now;
        updateUI();
        return status == Status.TRANSFERRING; //True if not interrupted
        //TODO: Transfer lastMod
    }

    public void finishReceive() {
        Log.d(TAG, "Finalizing transfer");
        closeStream();
        if(errors.size() > 0)
            status = Status.FINISHED_WITH_ERRORS;
        else status = Status.FINISHED;
        updateUI();
    }

    private void stopReceiving() {
        closeStream();
        File f = new File(Utils.getSaveDir(), currentRelativePath);
        f.delete(); //Delete incomplete file
    }

    private void failReceive() {
        //TODO: Avoid looping STOP command before using
        closeStream();
        //Don't overwrite other reason for stopping
        if (status == Status.TRANSFERRING)
            status = Status.FAILED;
        stop(true);
    }

    private void closeStream() {
        if(currentStream != null) {
            try {
                currentStream.close();
                currentStream = null;
            } catch (Exception ignored) {}
        }
    }
}
