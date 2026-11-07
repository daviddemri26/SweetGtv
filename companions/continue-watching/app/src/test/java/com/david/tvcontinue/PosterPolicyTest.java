package com.david.tvcontinue;

import org.junit.Test;
import java.net.InetAddress;
import java.net.URI;
import static org.junit.Assert.*;

public final class PosterPolicyTest {
    @Test public void onlyPublicHttpsCandidatesWithoutCredentials() {
        assertTrue(PosterPolicy.publicHttps(URI.create("https://image.tmdb.org/t/p/w342/a.jpg")));
        assertTrue(PosterPolicy.publicHttps(URI.create("https://cdn.example:443/p.png?width=320")));
        for (String value : new String[]{"http://cdn.example/a", "file:///etc/passwd", "data:image/png;base64,AA",
                "https://user:password@cdn.example/a", "https://cdn.example:8443/a", "https://localhost/a",
                "https://thing.local/a", "https://thing.internal/a", "https://thing.lan/a",
                "https://printer/a", "https://cdn.example/a#fragment", "https://cdn.example./a"})
            assertFalse(value, PosterPolicy.publicHttps(URI.create(value)));
    }
    @Test public void rejectsPrivateLoopbackLinkLocalReservedIpv4() throws Exception {
        for (String value : new String[]{"0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254",
                "172.16.0.1", "172.31.255.255", "192.168.1.1", "100.64.0.1", "100.127.255.255",
                "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.19.255.255",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255"})
            assertFalse(value, PosterPolicy.publicAddress(InetAddress.getByName(value)));
    }
    @Test public void rejectsPrivateAndTransitionIpv6() throws Exception {
        for (String value : new String[]{"::", "::1", "fc00::1", "fd12::1", "fe80::1", "ff02::1",
                "::ffff:127.0.0.1", "2001:db8::1", "2001:0::1", "2002:7f00:1::", "3fff::1"})
            assertFalse(value, PosterPolicy.publicAddress(InetAddress.getByName(value)));
    }
    @Test public void acceptsPublicAddressesWithoutDnsQueries() throws Exception {
        assertTrue(PosterPolicy.publicAddress(InetAddress.getByName("1.1.1.1")));
        assertTrue(PosterPolicy.publicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(PosterPolicy.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }
    @Test public void redirectsMustPassTheSameRules() {
        URI base = URI.create("https://cdn.example/poster/a.jpg");
        assertTrue(PosterPolicy.publicHttps(base.resolve("../b.jpg")));
        assertFalse(PosterPolicy.publicHttps(base.resolve("http://cdn.example/b.jpg")));
        assertFalse(PosterPolicy.publicHttps(base.resolve("https://localhost/b.jpg")));
        assertFalse(PosterPolicy.publicHttps(base.resolve("//user:pass@cdn.example/b.jpg")));
    }
}
