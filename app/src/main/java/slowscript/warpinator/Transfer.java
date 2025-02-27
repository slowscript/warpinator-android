package slowscript.warpinator;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
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

import static java.util.zip.Deflater.DEFAULT_COMPRESSION;
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
    private static final long UI_UPDATE_LIMIT = 250;

    private final AtomicReference<Status> status = new AtomicReference<>();
    public Direction direction;
    public String remoteUUID;
    public long startTime;
    public long totalSize;
    public long fileCount;
    public String singleName = "";
    public String singleMime = "";
    public List<String> topDirBasenames;
    public boolean useCompression = false;
    int privId;
    //SEND only
    public ArrayList<Uri> uris;
    private ArrayList<MFile> files;
    private ArrayList<MFile> dirs;

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
    long lastUiUpdate = 0;

    // -- COMMON --
    public void stop(boolean error) {
        Log.i(TAG, "Transfer stopped");
        try {
            MainService.remotes.get(remoteUUID).stopTransfer(this, error);
        } catch (NullPointerException ignored) {} //Service stopped and remotes cleared -> there must be a better solution
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
        long now = System.currentTimeMillis();
        if (getStatus() == Status.TRANSFERRING && (now - lastUiUpdate) < UI_UPDATE_LIMIT)
            return;

        lastUiUpdate = now;
        LocalBroadcasts.updateTransfer(svc, remoteUUID, privId);
        //Update notification
        svc.updateProgress();
    }

    // -- SEND --
    public void prepareSend(boolean isdir) {
        //Only uris and remoteUUID are set from before
        direction = Direction.SEND;
        startTime = System.currentTimeMillis();
        fileCount = uris.size();
        topDirBasenames = new ArrayList<>();
        files = new ArrayList<>();
        dirs = new ArrayList<>();
        for (Uri u : uris) {
            String name = Utils.getNameFromUri(svc, u);
            topDirBasenames.add(name);
            if (isdir) {
                String docId = DocumentsContract.getTreeDocumentId(u);
                MFile topdir = new MFile();
                topdir.relPath = topdir.name = name;
                topdir.isDirectory = true;
                dirs.add(topdir);
                resolveTreeUri(u, docId, name); //Get info about all child files
            } else files.addAll(resolveUri(u)); //Get info about single file
        }
        fileCount = files.size() + dirs.size();
        if (fileCount == 1) {
            singleName = Strings.nullToEmpty(topDirBasenames.get(0));
            singleMime = Strings.nullToEmpty(svc.getContentResolver().getType(uris.get(0)));
        }
        totalSize = getTotalSendSize();
        setStatus(Status.WAITING_PERMISSION);
        updateUI();
    }

    // Gets all children of a document and adds them to files and dirs
    private void resolveTreeUri(Uri rootUri, String docId, String parent) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, docId);
        ArrayList<MFile> items = resolveUri(childrenUri);
        for (MFile f : items) {
            if (f.documentID == null)
                break; //Provider is broken, what can we do...
            f.uri = DocumentsContract.buildDocumentUriUsingTree(rootUri, f.documentID);
            f.relPath = parent + "/" + f.name;
            if (f.isDirectory) {
                dirs.add(f);
                resolveTreeUri(rootUri, f.documentID, f.relPath);
            }
            else files.add(f);
        }
    }

    // Get info about all documents represented by uri - could be just a single document
    // or all children in case of special uri
    private ArrayList<MFile> resolveUri(Uri u) {
        ArrayList<MFile> mfs = new ArrayList<>();
        try (Cursor c = svc.getContentResolver().query(u, null, null, null, null)) {
            if (c == null) {
                Log.w(TAG, "Could not resolve uri: " + u);
                return mfs;
            }
            int idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int mTimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
            int sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);

            while (c.moveToNext()) {
                MFile f = new MFile();
                if (idCol != -1)
                    f.documentID = c.getString(idCol);
                else Log.w(TAG, "Could not get document ID");
                f.name = c.getString(nameCol); //Name is mandatory
                if (mimeCol != -1)
                    f.mime = c.getString(mimeCol);
                else {
                    Log.w(TAG, "Could not get MIME type");
                    f.mime = "application/octet-stream";
                }
                if (mTimeCol != -1)
                    f.lastMod = c.getLong(mTimeCol);
                else
                    f.lastMod = -1;
                f.length = c.getLong(sizeCol); //Size is mandatory
                f.isDirectory = f.mime.endsWith("directory");
                f.uri = u;
                f.relPath = f.name;
                mfs.add(f);
            }
        } catch(SecurityException sec) {
            Log.e(TAG, "Could not query resolver: ", sec);
        }
        return mfs;
    }

    public void startSending(CallStreamObserver<WarpProto.FileChunk> observer) {
        setStatus(Status.TRANSFERRING);
        Log.d(TAG, "Sending, compression " + useCompression);
        actualStartTime = System.currentTimeMillis();
        bytesTransferred = 0;
        cancelled = false;
        MainService.cancelAutoStop();
        Log.i(TAG, "Acquiring wake lock for " + MainService.WAKELOCK_TIMEOUT + " min");
        svc.wakeLock.acquire(MainService.WAKELOCK_TIMEOUT*60*1000L);
        updateUI();
        observer.setOnReadyHandler(new Runnable() {
            int i, iDir = 0;
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
                        if (iDir < dirs.size()) {
                            WarpProto.FileChunk fc = WarpProto.FileChunk.newBuilder()
                                    .setRelativePath(dirs.get(iDir).relPath)
                                    .setFileType(FileType.DIRECTORY)
                                    .setFileMode(0755)
                                    .build();
                            observer.onNext(fc);
                            iDir++;
                            continue;
                        }
                        if (is == null) {
                            is = svc.getContentResolver().openInputStream(files.get(i).uri);
                            first_chunk = true;
                        }
                        int read = is.read(chunk);
                        if (read < 1) {
                            is.close();
                            is = null;
                            i++;
                            if (i >= files.size()) {
                                observer.onCompleted();
                                setStatus(Status.FINISHED);
                                unpersistUris();
                                updateUI();
                            }
                            continue;
                        }
                        WarpProto.FileTime ft = WarpProto.FileTime.getDefaultInstance();
                        if (first_chunk) {
                            first_chunk = false;
                            long lastmod = files.get(i).lastMod;
                            if (lastmod > 0) // lastmod 0 is likely invalid
                                ft = WarpProto.FileTime.newBuilder().setMtime(lastmod / 1000).setMtimeUsec((int)(lastmod % 1000) * 1000).build();
                            else Log.w(TAG, "File doesn't have lastmod");
                        }
                        byte[] toSend = chunk;
                        int chunkLen = read;
                        if (useCompression) {
                            toSend = ZlibCompressor.compress(chunk, read, DEFAULT_COMPRESSION);
                            chunkLen = toSend.length;
                        }
                        WarpProto.FileChunk fc = WarpProto.FileChunk.newBuilder()
                                .setRelativePath(files.get(i).relPath)
                                .setFileType(FileType.FILE)
                                .setChunk(ByteString.copyFrom(toSend, 0, chunkLen))
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
                        errors.add("Not found: " + e.getMessage());
                        setStatus(Status.FAILED);
                        updateUI();
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending files", e);
                        setStatus(Status.FAILED);
                        errors.add("Unknown error: " + e.getMessage());
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

    private void unpersistUris() {
        for (Uri u : uris) {
            svc.getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }

    long getTotalSendSize() {
        long size = 0;
        for (MFile f : files) {
            size += f.length;
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
        else if (Server.current.notifyIncoming && !autoAccept) {  //Notification
            Intent intent = new Intent(svc, TransfersActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra("remote", remoteUUID);
            int immutable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pendingIntent = PendingIntent.getActivity(svc, svc.notifId, intent, immutable);
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
        Log.i(TAG, "Transfer accepted, compression " + useCompression);
        setStatus(Status.TRANSFERRING);
        actualStartTime = System.currentTimeMillis();
        updateUI();
        MainService.remotes.get(remoteUUID).startReceiveTransfer(this);
        MainService.cancelAutoStop(); //startRecv is asynchronous and may fail -> do this after
        Log.i(TAG, "Acquiring wake lock for " + MainService.WAKELOCK_TIMEOUT + " min");
        svc.wakeLock.acquire(MainService.WAKELOCK_TIMEOUT*60*1000L);
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
                    byte[] data = chunk.getChunk().toByteArray();
                    if (useCompression)
                        data = ZlibCompressor.decompress(data);
                    currentStream.write(data);
                    chunkSize = data.length;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open file for writing: " + currentRelativePath, e);
                    errors.add("Failed to open file for writing: " + currentRelativePath);
                    failReceive();
                }
            }
        } else {
            try {
                byte[] data = chunk.getChunk().toByteArray();
                if (useCompression)
                    data = ZlibCompressor.decompress(data);
                currentStream.write(data);
                chunkSize = data.length;
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
        if (currentLastMod > 0)
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
            Log.d(TAG, "Setting lastMod: " + currentLastMod);
            currentFile.setLastModified(currentLastMod);
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
            createDirectories(root, path, null); // Note: .. segment is created as (invalid)
        } else {
            File dir = new File(Server.current.downloadDirUri, path);
            if (!validateFile(dir))
                throw new IllegalArgumentException("The dir path leads outside download dir");
            if (!dir.mkdirs()) {
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
            //Get parent - createFile will substitute / with _ and checks if parent is descendant of tree root
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
            if (!validateFile(currentFile))
                throw new IllegalArgumentException("The file name leads to a file outside download dir");
            return new FileOutputStream(currentFile, false);
        }
    }

    private boolean validateFile(File f) {
        boolean res = false;
        try {
            res = (f.getCanonicalPath() + "/").startsWith(Server.current.downloadDirUri);
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve canonical path for " + f.getAbsolutePath() + ": " + e.getMessage());
        }
        return res;
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

    static class MFile {
        String documentID;
        String name;
        String mime;
        String relPath;
        Uri uri;
        long length;
        long lastMod;
        boolean isDirectory;
    }
}
