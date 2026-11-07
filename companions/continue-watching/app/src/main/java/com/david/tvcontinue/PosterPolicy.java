package com.david.tvcontinue;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Network destinations are public HTTPS only, checked again on every redirect. */
final class PosterPolicy {
    static boolean publicHttps(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.toString().length() > 8192) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return !host.equals("localhost") && !host.endsWith(".localhost") && !host.endsWith(".local")
                && !host.endsWith(".internal") && !host.endsWith(".lan") && !host.endsWith(".")
                && (host.contains(".") || host.contains(":"));
    }
    static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] raw = address.getAddress();
        int a = raw[0] & 255, b = raw[1] & 255;
        if (raw.length == 4) {
            int c = raw[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224
                    && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && b == 168) && !(a == 198 && (b == 18 || b == 19))
                    && !(a == 192 && b == 0 && (c == 0 || c == 2))
                    && !(a == 192 && b == 88 && c == 99)
                    && !(a == 198 && b == 51 && c == 100)
                    && !(a == 203 && b == 0 && c == 113);
        }
        // Global unicast only; exclude transition mechanisms and documentation ranges.
        return raw.length == 16 && (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x20 && b == 0x01 && raw[2] == 0 && raw[3] == 0)
                && !(a == 0x20 && b == 0x01 && (raw[2] & 255) == 0x0d && (raw[3] & 255) == 0xb8)
                && !(a == 0x3f && b == 0xff);
    }
}
