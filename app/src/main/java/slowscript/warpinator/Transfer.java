package slowscript.warpinator;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.provider.OpenableColumns;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;

import com.google.common.base.Strings;
import com.google.common.io.Files;
import com.google.protobuf.ByteString;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

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

    private static final String TAG = "TRANSFER";
    private static final int CHUNK_SIZE = 1024 * 512; //512 kB

    private final AtomicReference<Status> status = new AtomicReference<>();
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

    public boolean overwriteWarning = false;

    private String currentRelativePath;
    private long currentLastMod = -1;
    Uri currentUri;
    File currentFile;
    private OutputStream currentStream;
    public final ArrayList<String> errors = new ArrayList<>();
    private boolean cancelled = false;
    public long bytesTransferred;
    public long bytesPerSecond;
    public long actualStartTime;
    long lastMillis = 0;

    // -- COMMON --
    public void stop(boolean error) {
        Log.i(TAG, "Transfer stopped");
        MainService.remotes.get(remoteUUID).stopTransfer(this, error);
        onStopped(error);
    }

    public void onStopped(boolean error) {
        Log.v(TAG, "Stopping transfer");
        if (!error)
            setStatus(Status.STOPPED);
        if (direction == Transfer.Direction.RECEIVE)
            stopReceiving();
        else stopSending();
        updateUI();
    }

    public void makeDeclined() {
        setStatus(Status.DECLINED);
        updateUI();
    }

    public int getProgress() {
        return (int)((float)bytesTransferred / totalSize * 100f);
    }

    void updateUI() {
        LocalBroadcasts.updateTransfer(svc, remoteUUID, privId);
        //Update notification
        svc.updateProgress();
    }

    // -- SEND --
    public void prepareSend() {
        //Only uris and remoteUUID are set from before
        direction = Direction.SEND;
        startTime = System.currentTimeMillis();
        totalSize = getTotalSendSize();
        fileCount = uris.size();
        topDirBasenames = new ArrayList<>();
        for (Uri u : uris) {
            topDirBasenames.add(Utils.getNameFromUri(svc, u));
        }
        if (fileCount == 1) {
            singleName = Strings.nullToEmpty(topDirBasenames.get(0));
            singleMime = Strings.nullToEmpty(svc.getContentResolver().getType(uris.get(0)));
        }
        setStatus(Status.WAITING_PERMISSION);
        updateUI();
    }

    public void startSending(CallStreamObserver<WarpProto.FileChunk> observer) {
        setStatus(Status.TRANSFERRING);
        actualStartTime = System.currentTimeMillis();
        bytesTransferred = 0;
        cancelled = false;
        updateUI();
        observer.setOnReadyHandler(new Runnable() {
            int i = 0;
            InputStream is;
            byte[] chunk = new byte[CHUNK_SIZE];
            boolean first_chunk = true;

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
                            first_chunk = true;
                        }
                        int read = is.read(chunk);
                        if (read < 1) {
                            is.close();
                            is = null;
                            i++;
                            if (i >= uris.size()) {
                                observer.onCompleted();
                                setStatus(Status.FINISHED);
                                updateUI();
                            }
                            continue;
                        }
                        WarpProto.FileTime ft = WarpProto.FileTime.getDefaultInstance();
                        if (first_chunk) {
                            first_chunk = false;
                            try {
                                long lastmod = DocumentFile.fromSingleUri(svc, uris.get(i)).lastModified();
                                ft = WarpProto.FileTime.newBuilder().setMtime(lastmod / 1000).setMtimeUsec((int)(lastmod % 1000) * 1000).build();
                            } catch (Exception e) {Log.w(TAG, "Could not get lastMod", e);}
                        }
                        WarpProto.FileChunk fc = WarpProto.FileChunk.newBuilder()
                                .setRelativePath(Utils.getNameFromUri(svc, uris.get(i)))
                                .setFileType(FileType.FILE)
                                .setChunk(ByteString.copyFrom(chunk, 0, read))
                                .setFileMode(0644)
                                .setTime(ft)
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
                        setStatus(Status.FAILED);
                        updateUI();
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending files", e);
                        setStatus(Status.FAILED);
                        errors.add(e.getLocalizedMessage());
                        updateUI();
                        observer.onError(e);
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
            try {
                if (u.toString().startsWith("content:")) {
                    Cursor cursor = svc.getContentResolver().query(u, null, null, null, null);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    cursor.moveToFirst();
                    size += cursor.getLong(sizeIndex);
                    cursor.close();
                } else {
                    String p = u.getPath();
                    size += new File(p).length();
                }
            } catch (Exception e) {
                Log.w(TAG, "Bad URI: " + u);
            }
        }
        return size;
    }

    // -- RECEIVE --
    public void prepareReceive() {
        if (BuildConfig.DEBUG && direction != Direction.RECEIVE) {
            throw new AssertionError("Assertion failed");
        }
        //Check enough space

        //Check if will overwrite
        if (Server.current.allowOverwrite) {
            for (String file : topDirBasenames) {
                if (checkWillOverwrite(file)) {
                    overwriteWarning = true;
                    break;
                }
            }
        }

        boolean autoAccept = svc.prefs.getBoolean("autoAccept", false);

        //Show in UI
        if (remoteUUID.equals(TransfersActivity.topmostRemote))
            LocalBroadcasts.updateTransfers(svc, remoteUUID);
        else if (svc.server.notifyIncoming && !autoAccept) {  //Notification
            Intent intent = new Intent(svc, TransfersActivity.class);
            intent.putExtra("remote", remoteUUID);
            int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pendingIntent = PendingIntent.getActivity(svc, 0, intent, immutable);
            Uri notifSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Notification notification = new NotificationCompat.Builder(svc, MainService.CHANNEL_INCOMING)
                    .setContentTitle(svc.getString(R.string.incoming_transfer, MainService.remotes.get(remoteUUID).displayName))
                    .setContentText(fileCount == 1 ? singleName : svc.getString(R.string.num_files, fileCount))
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setSound(notifSound)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build();
            svc.notificationMgr.notify(svc.notifId++, notification);
        }
        if (autoAccept) this.startReceive();
    }

    void startReceive() {
        Log.i(TAG, "Transfer accepted");
        setStatus(Status.TRANSFERRING);
        actualStartTime = System.currentTimeMillis();
        updateUI();
        MainService.remotes.get(remoteUUID).startReceiveTransfer(this);
    }

    void declineTransfer() {
        Log.i(TAG, "Transfer declined");
        Remote r = MainService.remotes.get(remoteUUID);
        if (r != null)
            r.declineTransfer(this);
        else Log.w(TAG, "Transfer was from an unknown remote");
        makeDeclined();
    }

    public boolean receiveFileChunk(WarpProto.FileChunk chunk) {
        long chunkSize = 0;
        if (!chunk.getRelativePath().equals(currentRelativePath)) {
            //End old file
            closeStream();
            if (currentLastMod != -1) {
                setLastModified();
                currentLastMod = -1;
            }
            //Begin new file
            currentRelativePath = chunk.getRelativePath();
            if ("".equals(Server.current.downloadDirUri)) {
                errors.add(svc.getString(R.string.error_download_dir));
                failReceive();
                return false;
            }

            String sanitizedName = currentRelativePath.replaceAll("[\\\\<>*|?:\"]", "_");
            if (chunk.getFileType() == FileType.DIRECTORY) {
                createDirectory(sanitizedName);
            }
            else if (chunk.getFileType() == FileType.SYMLINK) {
                Log.w(TAG, "Symlinks not supported.");
                errors.add("Symlinks not supported."); //This one can be ignored
            }
            else {
                if (chunk.hasTime()) {
                    WarpProto.FileTime ft = chunk.getTime();
                    currentLastMod = ft.getMtime()*1000 + ft.getMtimeUsec()/1000;
                }
                try {
                    currentStream = openFileStream(sanitizedName);
                    currentStream.write(chunk.getChunk().toByteArray());
                    chunkSize = chunk.getChunk().size();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open file for writing: " + currentRelativePath, e);
                    errors.add("Failed to open file for writing: " + currentRelativePath);
                    failReceive();
                }
            }
        } else {
            try {
                currentStream.write(chunk.getChunk().toByteArray());
                chunkSize = chunk.getChunk().size();
            } catch (Exception e) {
                Log.e(TAG, "Failed to write to file " + currentRelativePath + ": " + e.getMessage());
                errors.add("Failed to write to file " + currentRelativePath);
                failReceive();
            }
        }
        bytesTransferred += chunkSize;
        long now = System.currentTimeMillis();
        bytesPerSecond = (long)(chunkSize / ((now - lastMillis) / 1000f));
        lastMillis = now;
        updateUI();
        return getStatus() == Status.TRANSFERRING; //True if not interrupted
    }

    public void finishReceive() {
        Log.d(TAG, "Finalizing transfer");
        if(errors.size() > 0)
            setStatus(Status.FINISHED_WITH_ERRORS);
        else setStatus(Status.FINISHED);
        closeStream();
        if (currentLastMod != -1)
            setLastModified();
        updateUI();
    }

    private void stopReceiving() {
        Log.v(TAG, "Stopping receiving");
        closeStream();
        //Delete incomplete file
        try {
            if (Server.current.downloadDirUri.startsWith("content:")) {
                DocumentFile f = DocumentFile.fromSingleUri(svc, currentUri);
                f.delete();
            } else {
                currentFile.delete();
            }
        } catch (Exception e) {
        	Log.w(TAG, "Could not delete incomplete file", e);
        }
    }

    private void failReceive() {
        //Don't overwrite other reason for stopping
        if (getStatus() == Status.TRANSFERRING) {
            Log.v(TAG, "Receiving failed");
            setStatus(Status.FAILED);
            stop(true); //Calls stopReceiving for us
        }
    }

    private void closeStream() {
        if(currentStream != null) {
            try {
                currentStream.close();
                currentStream = null;
            } catch (Exception ignored) {}
        }
    }

    private void setLastModified() {
        //This is apparently not possible with SAF
        if (!Server.current.downloadDirUri.startsWith("content:")) {
            File f = new File(Server.current.downloadDirUri, currentRelativePath);
            Log.d(TAG, "Setting lastMod: " + currentLastMod);
            f.setLastModified(currentLastMod);
        }
    }

    private boolean checkWillOverwrite(String relPath) {
        if (Server.current.downloadDirUri.startsWith("content:")) {
            Uri treeRoot = Uri.parse(Server.current.downloadDirUri);
            return Utils.pathExistsInTree(svc, treeRoot, relPath);
        } else {
            return new File(Server.current.downloadDirUri, relPath).exists();
        }
    }

    private File handleFileExists(File f) {
        Log.d(TAG, "File exists: " + f.getAbsolutePath());
        if(Server.current.allowOverwrite) {
            Log.v(TAG, "Overwriting");
            f.delete();
        } else {
            String name = f.getParent() + "/" + Files.getNameWithoutExtension(f.getAbsolutePath());
            String ext = Files.getFileExtension(f.getAbsolutePath());
            int i = 1;
            while (f.exists())
                f = new File(String.format("%s(%d).%s", name, i++, ext));
            Log.d(TAG, "Renamed to " + f.getAbsolutePath());
        }
        return f;
    }

    private String handleUriExists(String path) {
        Uri root = Uri.parse(Server.current.downloadDirUri);
        DocumentFile f = Utils.getChildFromTree(svc, root, path);
        Log.d(TAG, "File exists: " + f.getUri());
        if(Server.current.allowOverwrite) {
            Log.v(TAG, "Overwriting");
            f.delete();
        } else {
            String dir = path.substring(0, path.lastIndexOf("/")+1);
            String _fileName = path.substring(path.lastIndexOf("/")+1);

            String name = _fileName;
            String ext = "";
            if(_fileName.contains(".")) {
                name = _fileName.substring(0, _fileName.indexOf("."));
                ext = _fileName.substring(_fileName.indexOf("."));
            }
            int i = 1;
            while (Utils.pathExistsInTree(svc, root, path)) {
                path = dir + name + "(" + i + ")" + ext;
                i++;
            }
            Log.d(TAG, "Renamed to " + path);
        }
        return path;
    }

    private void createDirectory(String path) {
        if (Server.current.downloadDirUri.startsWith("content:")) {
            Uri rootUri = Uri.parse(Server.current.downloadDirUri);
            DocumentFile root = DocumentFile.fromTreeUri(svc, rootUri);
            createDirectories(root, path, null);
        } else {
            if (!new File(Server.current.downloadDirUri, path).mkdirs()) {
                errors.add("Failed to create directory " + path);
                Log.e(TAG, "Failed to create directory " + path);
            }
        }
    }

    private void createDirectories(DocumentFile parent, String path, @Nullable String done) {
        String dir = path;
        String rest  = null;
        if (path.contains("/")) {
            dir = path.substring(0, path.indexOf("/"));
            rest = path.substring(path.indexOf("/")+1);
        }
        String absDir = done == null ? dir : done +"/"+ dir; //Path from rootUri - just to check existence
        DocumentFile newDir = DocumentFile.fromTreeUri(svc, Utils.getChildUri(Uri.parse(Server.current.downloadDirUri), absDir));
        if (!newDir.exists()) {
            newDir = parent.createDirectory(dir);
            if (newDir == null) {
                errors.add("Failed to create directory " + absDir);
                Log.e(TAG, "Failed to create directory " + absDir);
                return;
            }
        }
        if (rest != null)
            createDirectories(newDir, rest, absDir);
    }

    private OutputStream openFileStream(String fileName) throws FileNotFoundException {
        if (Server.current.downloadDirUri.startsWith("content:")) {
            Uri rootUri = Uri.parse(Server.current.downloadDirUri);
            DocumentFile root = DocumentFile.fromTreeUri(svc, rootUri);
            if(Utils.pathExistsInTree(svc, rootUri, fileName)) {
                fileName = handleUriExists(fileName);
            }
            //Get parent
            DocumentFile parent = root;
            if (fileName.contains("/")) {
                String parentRelPath = fileName.substring(0, fileName.lastIndexOf("/"));
                fileName = fileName.substring(fileName.lastIndexOf("/")+1);
                Uri dirUri = Utils.getChildUri(rootUri, parentRelPath);
                parent = DocumentFile.fromTreeUri(svc, dirUri);
            }
            //Create file
            String mime = guessMimeType(fileName);
            DocumentFile file = parent.createFile(mime, fileName);
            currentUri = file.getUri();
            return svc.getContentResolver().openOutputStream(currentUri);
        } else {
            currentFile = new File(Server.current.downloadDirUri, fileName);
            if(currentFile.exists()) {
                currentFile = handleFileExists(currentFile);
            }
            return new FileOutputStream(currentFile, false);
        }
    }

    private String guessMimeType(String name) {
        //We don't care about knowing the EXACT mime type
        //This is only to prevent fail on some devices that reject empty mime type
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime == null)
            mime = "application/octet-stream";
        return mime;
    }

    public void setStatus(Status s) {
        status.set(s);
    }

    public Status getStatus() {
        return status.get();
    }
}
