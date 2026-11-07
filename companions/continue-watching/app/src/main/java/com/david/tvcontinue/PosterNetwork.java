package com.david.tvcontinue;

import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;

/** Platform TLS and HTTP implementation, restricted to public HTTPS images. */
final class PosterNetwork {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT = 2500;
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(0, 1, 5, TimeUnit.SECONDS,
            new SynchronousQueue<>());

    static byte[] fetch(String value, CancellationSignal signal) throws IOException {
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException bad) { throw new IOException("Invalid image URI"); }
        long deadline = SystemClock.elapsedRealtime() + 10000;
        for (int redirects = 0; redirects <= 2; redirects++) {
            check(signal, deadline);
            if (!PosterPolicy.publicHttps(uri)) throw new IOException("Unsupported image origin");
            String host = uri.getHost();
            if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
            InetAddress[] addresses = resolve(host, signal);
            if (addresses.length == 0) throw new IOException("Image origin unavailable");
            for (InetAddress address : addresses) if (!PosterPolicy.publicAddress(address))
                throw new IOException("Non-public image origin");
            check(signal, deadline);
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "image/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "TVContinue/1.1");
            // This process has no cookie store or authenticator. Never send credentials or a referrer.
            connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("Authorization", "");
            connection.setRequestProperty("Proxy-Authorization", "");
            signal.setOnCancelListener(connection::disconnect);
            try {
                int code = connection.getResponseCode();
                check(signal, deadline);
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (redirects == 2 || location == null) throw new IOException("Too many image redirects");
                    try { uri = uri.resolve(location); }
                    catch (IllegalArgumentException bad) { throw new IOException("Invalid image redirect"); }
                    continue;
                }
                String type = connection.getContentType();
                if (code != 200 || type == null || !type.toLowerCase(Locale.ROOT).startsWith("image/"))
                    throw new IOException("Not an image response");
                String encoding = connection.getContentEncoding();
                if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    throw new IOException("Unsupported image encoding");
                long length = connection.getContentLengthLong();
                if (length > MAX_BYTES) throw new IOException("Image body too large");
                try (InputStream stream = connection.getInputStream()) {
                    return read(stream, length >= 0 ? (int) length : -1, signal, deadline);
                }
            } finally { signal.setOnCancelListener(null); connection.disconnect(); }
        }
        throw new IOException("Image unavailable");
    }

    private static InetAddress[] resolve(String host, CancellationSignal signal) throws IOException {
        Future<InetAddress[]> task;
        try { task = DNS.submit(() -> InetAddress.getAllByName(host)); }
        catch (RuntimeException busy) { throw new IOException("Resolver busy"); }
        signal.setOnCancelListener(() -> task.cancel(true));
        try { return task.get(1500, TimeUnit.MILLISECONDS); }
        catch (Exception failed) { signal.throwIfCanceled(); throw new IOException("Image DNS unavailable"); }
        finally { task.cancel(true); signal.setOnCancelListener(null); }
    }
    static byte[] read(InputStream input, int length, CancellationSignal signal, long deadline) throws IOException {
        if (length > MAX_BYTES || length < -1) throw new IOException("Image body too large");
        ByteArrayOutputStream out = new ByteArrayOutputStream(length > 0 ? length : 8192);
        byte[] buffer = new byte[8192];
        while (length < 0 || out.size() < length) {
            check(signal, deadline);
            int max = length < 0 ? buffer.length : Math.min(buffer.length, length - out.size());
            int count = input.read(buffer, 0, max);
            if (count < 0) { if (length >= 0) throw new IOException("Truncated image"); break; }
            if (out.size() + count > MAX_BYTES) throw new IOException("Image body too large");
            out.write(buffer, 0, count);
        }
        check(signal, deadline);
        return out.toByteArray();
    }
    private static void check(CancellationSignal signal, long deadline) throws IOException {
        signal.throwIfCanceled();
        if (SystemClock.elapsedRealtime() > deadline) throw new IOException("Image time limit");
    }
}
