package com.cyberaudio.hub;
import org.junit.Test;
import static org.junit.Assert.*;
public class ServerAddressTest {
    @Test public void defaultsAndEquivalentForms() {
        assertEquals(ServerAddress.DEFAULT, ServerAddress.normalize(""));
        assertEquals(ServerAddress.DEFAULT, ServerAddress.normalize(null));
        assertEquals(ServerAddress.DEFAULT, ServerAddress.normalize(" 188.32.242.100:2077/ "));
        assertEquals("https://example.com", ServerAddress.normalize("HTTPS://EXAMPLE.COM:443/"));
        assertEquals("http://example.com/base", ServerAddress.normalize("http://example.com/base///"));
    }
    @Test public void rejectsCredentialsQueryAndNonHttp() {
        for (String bad : new String[]{"https://", "ftp://example.com", "http://u:p@example.com", "http://example.com?x=1", "http://example.com#x", "http://example.com:99999"}) {
            try { ServerAddress.normalize(bad); fail(bad); } catch (IllegalArgumentException expected) { }
        }
    }
}
