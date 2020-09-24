package slowscript.warpinator;

import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.Arrays;

public class CertServer implements Runnable{
    static String TAG = "CertServer";
    static int PORT;
    public static String REQUEST = "REQUEST";

    static Thread serverThread;
    static DatagramSocket serverSocket;
    static boolean running = false;

    public static void Start(int port) {
        PORT = port;
        if (serverSocket != null)
            serverSocket.close();
        running = true;
        serverThread = new Thread(new CertServer());
        serverThread.start();
    }

    public static void Stop() {
        //It's a UDP server, it doesn't lock anything so this shouldn't matter
        //Close should cancel the receive method
        serverSocket.close();
        running = false;
    }

    public void run() {
        try {
             serverSocket = new DatagramSocket(PORT);
        } catch (SocketException e){
            Log.e(TAG, "Failed to start certificate server", e);
            Toast.makeText(MainService.svc, "Failed to start certificate server. Try restarting the application.", Toast.LENGTH_LONG).show();
            return;
        }
        byte[] receiveData = new byte[1024];
        byte[] cert = Authenticator.getBoxedCertificate();
        byte[] sendData = Base64.encode(cert, Base64.DEFAULT);
        while(running)
        {
            try {
                DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                serverSocket.receive(receivePacket);
                byte[] received = Arrays.copyOfRange(receivePacket.getData(), 0, receivePacket.getLength());
                String request = new String(received);
                if (request.equals(REQUEST))
                {
                    InetAddress IPAddress = receivePacket.getAddress();
                    int port = receivePacket.getPort();
                    DatagramPacket sendPacket =
                            new DatagramPacket(sendData, sendData.length, IPAddress, port);
                    serverSocket.send(sendPacket);
                    Log.d(TAG, "Certificate sent");
                }
            } catch (Exception e) {
                Log.w (TAG, "Error while running CertServer. Restarting. || " + e.getMessage());
            }
        }
    }
}
