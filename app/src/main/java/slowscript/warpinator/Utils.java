package slowscript.warpinator;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class Utils {

    public static String getDeviceName() {
        return BluetoothAdapter.getDefaultAdapter().getName();
    }

    public static String getIPAddress() {
        try {
            //TODO: Choose Iface
            final String ip = getIPForIfaceName("wlan0").getHostAddress();
            return ip != null ? ip : "IP Unknown";
        } catch (Exception ex) {
            Log.e("Utils", "Couldn't get IP address");
            return "Error getting IP";
        }
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
        return new File(MainActivity.ctx.getCacheDir(), "certs");
    }

    public static File getSaveDir() {
        return new File("/storage/emulated/0/Download/Warpinator");
    }

    public static byte[] readAllBytes(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        byte[] b = new byte[(int)f.length()];
        f.readFully(b);
        return b;
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
}
