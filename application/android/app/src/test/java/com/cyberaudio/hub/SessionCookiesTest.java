package com.cyberaudio.hub;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class SessionCookiesTest {
    @Test public void acceptsEveryHttpHeaderCasing() {
        for (String key : new String[]{"Set-Cookie", "set-cookie", "Set-cookie", "SET-COOKIE"})
            assertEquals("session=test", SessionCookies.session(Collections.singletonMap(key,
                    Arrays.asList("other=x", "session=test; HttpOnly; Path=/"))));
    }
    @Test public void handlesLogoutAndUnrelatedResponses() {
        assertEquals("", SessionCookies.session(Collections.singletonMap("Set-cookie", Arrays.asList("session=; Max-Age=0"))));
        assertNull(SessionCookies.session(Collections.singletonMap("Other", Arrays.asList("value"))));
    }
}
