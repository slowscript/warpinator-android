package slowscript.warpinator;

import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLDecoder;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.Enumeration;

import io.grpc.stub.StreamObserver;

import static android.content.Context.WIFI_SERVICE;

public class Utils {

    private static final String TAG = "Utils";

    public static String getDeviceName() {
        String name = null;
        try {
            name = BluetoothAdapter.getDefaultAdapter().getName();
        }catch (Exception e){
            Log.d(TAG, "This device may not support bluetooth - using default name");
        }
        if (name == null)
	        name = "Android Phone";
        return name;
    }

    public static String getIPAddress() {
        try {
            String ip = getWifiIP(); //Works for most cases
            if (ip == null) //Get IP of WiFi interface, fallback to wlan0 - works for hotspot
                ip = getIPForIfaceName(getWifiInterface()).getHostAddress();
            if (ip == null) //Get IP of some random active iface (except loopback and data)
                ip = getIPForIface(getActiveIface()).getHostAddress();
            return ip != null ? ip : "IP Unknown";
        } catch (Exception ex) {
            Log.e(TAG, "Couldn't get IP address");
            return "Error getting IP";
        }
    }

    static String getWifiIP() {
        WifiManager wifiManager = (WifiManager) MainService.svc.getSystemService(WIFI_SERVICE);
        int ip = wifiManager.getConnectionInfo().getIpAddress();
        if (ip == 0) return null;
        return Formatter.formatIpAddress(ip);
    }

    static NetworkInterface getActiveIface() throws SocketException {
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        NetworkInterface ni;
        while (nis.hasMoreElements()) {
            ni = nis.nextElement();
            if ((!ni.isLoopback()) && ni.isUp()) {
                String name = ni.getDisplayName();
                if (name.contains("dummy") || name.contains("rmnet"))
                    continue;
                Log.d(TAG, ni.getDisplayName());
                return ni;
            }
        }
        return null;
    }

    static String getWifiInterface() {
        String iface = null;
        try {
            Method m = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
            iface = (String) m.invoke(null, "wifi.interface");
        } catch(Throwable ignored) {}
        if (iface == null || iface.isEmpty())
            iface = "wlan0";
        return iface;
    }

    public static String dumpInterfaces() {
        StringBuilder res = new StringBuilder();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isUp()) {
                    res.append(ni.getDisplayName()); res.append("\n");
                }
            }
        } catch (Exception e) {res.append(e.getMessage());}
        return String.valueOf(res);
    }

    public static InetAddress getIPForIfaceName(String ifaceName) throws SocketException {
        Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
        NetworkInterface ni;
        while (nis.hasMoreElements()) {
            ni = nis.nextElement();
            if (ni.getDisplayName().equals(ifaceName)) {
                return getIPForIface(ni);
            }
        }
        return null;
    }

    static InetAddress getIPForIface(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            //filter for ipv4/ipv6
            if (ia.getAddress().getAddress().length == 4) {
                //4 for ipv4, 16 for ipv6
                return ia.getAddress();
            }
        }
        return null;
    }

    public static File getCertsDir()
    {
        return new File(MainService.svc.getCacheDir(), "certs");
    }

    public static byte[] readAllBytes(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        byte[] b = new byte[(int)f.length()];
        f.readFully(b);
        return b;
    }

    public static void displayMessage(Context ctx, String title, String msg) {
        new AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    public static String bytesToHumanReadable(long bytes) {
        long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
        if (absB < 1024) {
            return bytes + " B";
        }
        long value = absB;
        CharacterIterator ci = new StringCharacterIterator("KMGTPE");
        for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
            value >>= 10;
            ci.next();
        }
        value *= Long.signum(bytes);
        return String.format("%.1f %cB", value / 1024.0, ci.current());
    }

    public static String getNameFromUri(Context ctx, Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try {
                Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    cursor.close();
                }
            } catch(Exception ignored) {}
        }
        if (result == null) {
            String[] parts = URLDecoder.decode(uri.toString()).split("/");
            return parts[parts.length-1];
        }
        return result;
    }

    public static int getIconForRemoteStatus(Remote.RemoteStatus status) {
        switch (status) {
            case CONNECTING:
                return R.drawable.ic_status_connecting;
            case AWAITING_DUPLEX:
                return R.drawable.ic_status_awaiting_duplex;
            case CONNECTED:
                return R.drawable.ic_status_connected;
            case DISCONNECTED:
            case ERROR:
            default:
                return R.drawable.ic_error;
        }
    }

    public static Uri getChildUri(Uri treeUri, String path) {
        String rootID = DocumentsContract.getTreeDocumentId(treeUri);
        String docID = rootID + "/" + path;
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docID);
    }

    public static DocumentFile getChildFromTree(Context ctx, Uri treeUri, String path) {
        Uri childUri = getChildUri(treeUri, path);
        return DocumentFile.fromSingleUri(ctx, childUri);
    }

    //Just like DocumentFile.exists() but doesn't spam "Failed query" when file is not found
    public static boolean pathExistsInTree(Context ctx, Uri treeUri, String path) {
        ContentResolver resolver = ctx.getContentResolver();
        Uri u = getChildUri(treeUri, path);
        Cursor c;
        try {
            c = resolver.query(u, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null);
            boolean found = c.getCount() > 0;
            c.close();
            return found;
        } catch (Exception ignored) {}
        return false;
    }

    static boolean isMyServiceRunning(Context ctx, Class<?> serviceClass) {
        ActivityManager manager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        assert manager != null;
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isConnectedToWiFiOrEthernet(Context ctx) {
        ConnectivityManager connManager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        assert connManager != null;
        NetworkInfo wifi = connManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        NetworkInfo ethernet = connManager.getNetworkInfo(ConnectivityManager.TYPE_ETHERNET);
        return (wifi != null && wifi.isConnected()) || (ethernet != null && ethernet.isConnected());
    }

    public static boolean isHotspotOn(Context ctx) {
        WifiManager manager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        assert manager != null;
        try {
            final Method method = manager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true); //in the case of visibility change in future APIs
            return (Boolean) method.invoke(manager);
        } catch (Exception e) {
            Log.e(TAG, "Failed to get hotspot state", e);
        }

        return false;
    }

    public static void sleep(long millis)
    {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e){}
    }

    //FOR DEBUG PURPOSES
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    static class VoidObserver implements StreamObserver<WarpProto.VoidType> {
        @Override public void onNext(WarpProto.VoidType value) {}
        @Override public void onError(Throwable t) {
            Log.e(TAG, "Call failed with exception", t);
        }
        @Override public void onCompleted() { }
    }
}
