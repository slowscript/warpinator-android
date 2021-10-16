package slowscript.warpinator;

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.X509Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;
import org.openjax.security.nacl.TweetNaclFast;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

public class Authenticator {
    private static String TAG = "AUTH";
    public static String DEFAULT_GROUP_CODE = "Warpinator";

    static long day = 1000L * 60L * 60L * 24;

    public static long expireTime = 30L * day;
    public static String groupCode = DEFAULT_GROUP_CODE;

    static String cert_begin = "-----BEGIN CERTIFICATE-----\n";
    static String cert_end = "-----END CERTIFICATE-----";
    static Exception certException = null;

    public static byte[] getBoxedCertificate() {
        byte[] bytes = new byte[0];
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            final byte[] key = md.digest(groupCode.getBytes(StandardCharsets.UTF_8));
            TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
            byte[] nonce = TweetNaclFast.makeSecretBoxNonce();
            byte[] res = box.box(getServerCertificate(), nonce);

            bytes = new byte[24 + res.length];
            System.arraycopy(nonce, 0, bytes, 0, 24);
            System.arraycopy(res, 0, bytes, 24, res.length);
        } catch (Exception e) {
            Log.wtf(TAG, "WADUHEK", e);
        } //This shouldn't fail
        return bytes;
    }

    public static byte[] getServerCertificate() {
        //Try loading it first
        try {
            Log.d(TAG, "Loading server certificate...");
            certException = null;
            File f = getCertificateFile(".self");
            X509Certificate cert = getX509fromFile(f);
            cert.checkValidity(); //Will throw if expired (and we generate a new one)
            String ip = (String)((List<?>)cert.getSubjectAlternativeNames().toArray()[0]).get(1);
            if (!ip.equals(Utils.getIPAddress()))
                throw new Exception(); //Throw if IPs don't match (and regenerate cert)
            
            return Utils.readAllBytes(f);
        } catch (Exception ignored) {}

        //Create new one if doesn't exist yet
        byte[] cert = createCertificate(Utils.getDeviceName());
        if (cert != null)
            saveCertOrKey(".self.pem", cert, false);
        return cert;
    }

    public static File getCertificateFile(String hostname) {
        File certsDir = Utils.getCertsDir();
        return new File(certsDir, hostname + ".pem");
    }

    static byte[] createCertificate(String hostname) {
        try {
            Log.d(TAG, "Creating new server certificate...");

            String ip = Utils.getIPAddress();
            Security.addProvider(new BouncyCastleProvider());
            //Create KeyPair
            KeyPair kp = createKeyPair("RSA", 2048);

            long now = System.currentTimeMillis();

            //Only allowed chars
            hostname = hostname.replaceAll("[^a-zA-Z0-9]", "");
            if (hostname.trim().isEmpty())
                hostname = "android";
            //Build certificate
            X500Name name = new X500Name("CN="+hostname);
            BigInteger serial = new BigInteger(Long.toString(now)); //Use current time as serial num
            Date notBefore = new Date(now - day);
            Date notAfter = new Date(now + expireTime);

            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    name, serial, notBefore, notAfter, name, kp.getPublic());
            builder.addExtension(X509Extensions.SubjectAlternativeName, true, new GeneralNames(new GeneralName(GeneralName.iPAddress, ip)));

            //Sign certificate
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(kp.getPrivate());
            X509CertificateHolder cert = builder.build(signer);

            //Save private key
            byte[] privKeyBytes = kp.getPrivate().getEncoded();
            saveCertOrKey(".self.key-pem", privKeyBytes, true);

            return cert.getEncoded();
        }
        catch(Exception e) {
            Log.e(TAG, "Failed to create certificate", e);
            certException = e;
            return null;
        }
    }

    public static boolean saveBoxedCert(byte[] bytes, String remoteUuid) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            final byte[] key = md.digest(groupCode.getBytes("UTF-8"));
            TweetNaclFast.SecretBox box = new TweetNaclFast.SecretBox(key);
            byte[] nonce = new byte[24];
            byte[] ciph = new byte[bytes.length - 24];
            System.arraycopy(bytes, 0, nonce, 0, 24);
            System.arraycopy(bytes, 24, ciph, 0, bytes.length - 24);
            byte[] cert = box.open(ciph, nonce);
            if (cert == null) {
                Log.w(TAG, "Failed to unbox cert. Wrong group code?");
                return false;
            }

            saveCertOrKey(remoteUuid + ".pem", cert, false);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbox and save certificate", e);
            return false;
        }
    }

    private static void saveCertOrKey(String filename, byte[] bytes, boolean isPrivateKey) {
        File certsDir = Utils.getCertsDir();
        if (!certsDir.exists())
            certsDir.mkdir();
        File cert = new File(certsDir, filename);

        String begin = cert_begin;
        String end = cert_end;
        if (isPrivateKey) {
            begin = "-----BEGIN PRIVATE KEY-----\n";
            end = "-----END PRIVATE KEY-----";
        }
        String cert64 = Base64.encodeToString(bytes, Base64.DEFAULT);
        String certString = begin + cert64 + end;
        try (FileOutputStream stream = new FileOutputStream(cert, false)) {
            stream.write(certString.getBytes());
        } catch (Exception e) {
            Log.w(TAG, "Failed to save certificate or private key: " + filename);
            e.printStackTrace();
        }
    }

    private static byte[] loadCertificate(String hostname) throws IOException {
        File cert = getCertificateFile(hostname);
        return Utils.readAllBytes(cert);
    }

    private static X509Certificate getX509fromFile(File f) throws GeneralSecurityException, IOException {
        FileReader fileReader = new FileReader(f);
        PemReader pemReader = new PemReader(fileReader);
        PemObject obj = pemReader.readPemObject();
        pemReader.close();
        X509Certificate result;
        try (InputStream in = new ByteArrayInputStream(obj.getContent());) {
            result = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
        return result;
    }

    private static KeyPair createKeyPair(String algorithm, int bitCount) throws NoSuchAlgorithmException
    {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(algorithm);
        keyPairGenerator.initialize(bitCount, new SecureRandom());

        return keyPairGenerator.genKeyPair();
    }

    public static SSLSocketFactory createSSLSocketFactory(String name) throws GeneralSecurityException, IOException {
        File crtFile = getCertificateFile(name);

        SSLContext sslContext = SSLContext.getInstance("SSL");

        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);

        // Read the certificate from disk
        X509Certificate cert = getX509fromFile(crtFile);

        // Add it to the trust store
        trustStore.setCertificateEntry(crtFile.getName(), cert);

        // Convert the trust store to trust managers
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        TrustManager[] trustManagers = tmf.getTrustManagers();

        sslContext.init(null, trustManagers, null);
        return sslContext.getSocketFactory();
    }
}
