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
    public enum Status { INITIALIZING, WAITING_PERMISSION, DENIED_BY_SENDER, DENIED_BY_RECEIVER,
        TRANSFERRING, PAUSED, STOPPED_BY_SENDER, STOPPED_BY_RECEIVER,
        FAILED, FAILED_UNRECOVERABLE, FILE_NOT_FOUND, FINISHED
    }
    static final class FileType {
        static final int FILE = 0; static final int DIRECTORY = 1; static final int SYMLINK = 2;
    }

    static final String TAG = "TRANSFER";

    public Status status;
    public Direction direction;
    public String otherUUID;
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

    public void prepareReceive() {
        if (BuildConfig.DEBUG && direction != Direction.RECEIVE) {
            throw new AssertionError("Assertion failed");
        }
        //Check enough space

        //Check if will rewrite

        //Show in UI
        MainActivity.ctx.updateTransfers();
    }

    public void receiveFileChunk(WarpProto.FileChunk chunk) {
        if (!chunk.getRelativePath().equals(currentRelativePath)) {
            //End old file
            if (currentStream != null) {
                try {
                    currentStream.close();
                    currentStream = null;
                } catch (IOException ignored) {}
            }
            //Begin new file
            currentRelativePath = chunk.getRelativePath();
            File path = new File(Utils.getSaveDir(), currentRelativePath); //FIXME: Can this escape saveDir?
            if (chunk.getFileType() == FileType.DIRECTORY) {
                if (!path.mkdirs()) {
                    errors.add("Failed to create directory " + path.getAbsolutePath());
                    Log.w(TAG, "Failed to create directory " + path.getAbsolutePath());
                }
            }
            else if (chunk.getFileType() == FileType.SYMLINK) {
                Log.w(TAG, "Symlinks not supported.");
                errors.add("Symlinks not supported.");
            }
            else {
                try (FileOutputStream fos = new FileOutputStream(path, false)) {
                    currentStream = fos;
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
        //TODO: Update UI
    }

    public void finishReceive() {
        if(currentStream != null) {
            try {
                currentStream.close();
                currentRelativePath = "";
            } catch (Exception ignored) {}
        }
    }
}
