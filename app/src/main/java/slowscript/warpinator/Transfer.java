package slowscript.warpinator;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

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

    public Status status;
    public Direction direction;
    public String remoteUUID;
    public long startTime;
    public long totalSize;
    public long fileCount;
    public String singleName;
    public String singleMime;
    public String[] topLevelDirs;

    private String currentRelativePath;
    private FileOutputStream currentStream;
    private ArrayList<String> errors = new ArrayList<>();
    public long bytesTransferred;

    // -- COMMON --
    public void stop(boolean error) {
        Log.i(TAG, "Transfer stopped");
        ctx.remotes.get(remoteUUID).stopTransfer(this, error);
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
        if (ctx.transfersView != null)
            ctx.transfersView.updateTransfer(remoteUUID, privId);
    }


    // -- RECEIVE --
    public void prepareReceive() {
        if (BuildConfig.DEBUG && direction != Direction.RECEIVE) {
            throw new AssertionError("Assertion failed");
        }
        //Check enough space

        //Check if will rewrite

        //Show in UI
        if (ctx.transfersView != null)
            ctx.transfersView.updateTransfers(remoteUUID);
    }

    void startReceive() {
        Log.i(TAG, "Transfer accepted");
        status = Status.TRANSFERRING;
        ctx.remotes.get(remoteUUID).startReceiveTransfer(this);
    }

    void declineReceiveTransfer() {
        Log.i(TAG, "Transfer declined");
        ctx.remotes.get(remoteUUID).declineTransfer(this);
        makeDeclined();
    }

    public boolean receiveFileChunk(WarpProto.FileChunk chunk) {
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
                    bytesTransferred += chunk.getChunk().size();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open file for writing: " + currentRelativePath, e);
                    errors.add("Failed to open file for writing: " + currentRelativePath);
                }
            }
        } else {
            try {
                currentStream.write(chunk.getChunk().toByteArray());
                bytesTransferred += chunk.getChunk().size();
            } catch (Exception e) {
                Log.e(TAG, "Failed to write to file " + currentRelativePath, e);
                errors.add("Failed to write to file " + currentRelativePath);
            }
        }
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
