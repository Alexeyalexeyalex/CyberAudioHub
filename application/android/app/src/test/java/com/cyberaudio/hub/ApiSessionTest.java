package com.cyberaudio.hub;

import org.junit.*;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real HTTP transport against loopback only; never the configured production server. */
public class ApiSessionTest {
    private ServerSocket server;
    private ExecutorService workers;
    private AccountSessionTest.Memory disk;
    private Api api;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicBoolean expire = new AtomicBoolean(), delay = new AtomicBoolean();
    private final AtomicBoolean rejectLogin = new AtomicBoolean(), omitLoginCookie = new AtomicBoolean();
    private final AtomicReference<String> loginCookie = new AtomicReference<>();
    private final AtomicBoolean delayBrowse = new AtomicBoolean(), refreshCookie = new AtomicBoolean();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

    @Before public void setup() throws Exception {
        workers = Executors.newCachedThreadPool();
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        final ServerSocket listening = server;
        workers.submit(() -> {
            while (!listening.isClosed()) {
                try { Socket socket = listening.accept(); workers.submit(() -> handle(socket)); }
                catch (IOException e) { if (!listening.isClosed()) throw new RuntimeException(e); }
            }
        });
        disk = new AccountSessionTest.Memory();
        api = new Api(new AccountSession(disk));
        api.setServer("http://127.0.0.1:"+server.getLocalPort());
    }
    private void handle(Socket socket) {
        try (Socket connection = socket) {
            connection.setSoTimeout(5000);
            BufferedReader input = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String route = input.readLine().split(" ")[1], cookie = null, header;
            int length = 0;
            while ((header = input.readLine()) != null && !header.isEmpty()) {
                if (header.regionMatches(true,0,"Cookie:",0,7)) cookie = header.substring(7).trim();
                if (header.regionMatches(true,0,"Content-Length:",0,15)) length = Integer.parseInt(header.substring(15).trim());
            }
            for (int i = 0; i < length; i++) input.read(); // Test requests contain ASCII credentials only.
            String body;
            String setCookie = "";
            int status = 200;
            if (route.equals("/api/auth/login")) {
                loginCookie.set(cookie);
                int id = logins.incrementAndGet();
                if (rejectLogin.get()) {
                    status = 401; body = "{\"error\":\"Invalid credentials\"}";
                } else {
                    if (!omitLoginCookie.get()) setCookie = "Set-cookie: session=test"+id+"; HttpOnly; Path=/\r\n";
                    body = profile(id);
                }
            } else if (route.equals("/api/me")) {
                boolean old = delay.getAndSet(false);
                if (old) { entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                boolean invalid = old || expire.get() || cookie == null;
                if (invalid) setCookie = "Set-cookie: session=; Max-Age=0; Path=/\r\n";
                else if (refreshCookie.getAndSet(false)) setCookie = "Set-cookie: session=test01; HttpOnly; Path=/\r\n";
                body = invalid ? "{\"user\":null}" : profile(Integer.parseInt(cookie.substring("session=test".length())));
            } else if (route.equals("/api/browse")) {
                if (delayBrowse.getAndSet(false)) {
                    entered.countDown();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                setCookie = "Set-cookie: session=test1; HttpOnly; Path=/\r\n";
                body = "{\"items\":[{\"name\":\"Book\"}]}";
            } else body = "{\"ok\":true}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = connection.getOutputStream();
            out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nConnection: close\r\n"
                    +setCookie+"Content-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);out.flush();
        } catch (IOException e) { throw new RuntimeException(e); }
    }
    private static String profile(int id) { return "{\"user\":{\"id\":"+id+",\"login\":\"reader"+id+"\",\"nickname\":\"Читатель\"}}"; }
    @After public void cleanup() throws Exception { release.countDown(); if(server!=null)server.close(); workers.shutdownNow(); }

    @Test public void loginAndProfileSurviveNewClient() throws Exception {
        api.login("reader","dummy-test-password");
        Api reopened = new Api(new AccountSession(disk));
        assertTrue(reopened.isSignedIn());
        assertEquals("reader1", reopened.me().getJSONObject("user").getString("login"));
        assertEquals("Читатель", reopened.cachedProfile().getString("nickname"));
    }
    @Test public void networkFailureDoesNotEraseAccount() throws Exception {
        api.login("reader","dummy-test-password"); server.close();server=null;
        try { api.me(); fail(); } catch (java.io.IOException expected) { }
        Api reopened = new Api(new AccountSession(disk));
        assertTrue(reopened.isSignedIn());assertEquals("reader1",reopened.cachedProfile().getString("login"));
    }
    @Test public void serverRejectedSessionIsNotRestoredFromCache() throws Exception {
        api.login("reader","dummy-test-password");expire.set(true);
        assertTrue(api.me().isNull("user")); assertFalse(api.isSignedIn());
        assertNotNull(api.cachedProfile());
    }
    @Test public void failedReloginKeepsPreviousAccount() throws Exception {
        api.login("reader", "dummy-test-password"); rejectLogin.set(true);
        try { api.login("other", "wrong-password"); fail(); }
        catch (Api.ApiException expected) { assertEquals(401, expected.status); }
        Api reopened = new Api(new AccountSession(disk));
        assertTrue(reopened.isSignedIn());
        assertEquals("reader1", reopened.me().getJSONObject("user").getString("login"));
    }
    @Test public void networkLossDuringReloginKeepsPreviousAccount() throws Exception {
        api.login("reader", "dummy-test-password"); server.close(); server = null;
        try { api.login("other", "dummy-test-password"); fail(); }
        catch (IOException expected) { }
        Api reopened = new Api(new AccountSession(disk));
        assertEquals("session=test1", reopened.cookieHeader());
        assertEquals("reader1", reopened.cachedProfile().getString("login"));
    }
    @Test public void missingNewCookieMustNotPretendTheOldAccountIsTheNewLogin() throws Exception {
        api.login("reader", "dummy-test-password"); omitLoginCookie.set(true);
        try { api.login("other", "dummy-test-password"); fail(); }
        catch (Api.ApiException expected) { }
        assertEquals("session=test1", api.cookieHeader());
        assertEquals("reader1", api.cachedProfile().getString("login"));
    }
    @Test public void reloginDoesNotSendPreviousAccountCookie() throws Exception {
        api.login("reader", "dummy-test-password");
        api.login("other", "dummy-test-password");
        assertNull(loginCookie.get());
        assertEquals("reader2", api.me().getJSONObject("user").getString("login"));
    }

    @Test(timeout=10000) public void parallelCookieRefreshDoesNotBreakSuccessfulCatalogueLoad() throws Exception {
        api.login("reader", "dummy-test-password"); delayBrowse.set(true);
        Future<JSONObject> browse = workers.submit(() -> api.browse(""));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        refreshCookie.set(true); api.me(); release.countDown();
        assertEquals(1, browse.get(3, TimeUnit.SECONDS).getJSONArray("items").length());
        assertEquals("session=test01", api.cookieHeader());
        assertEquals("reader1", api.cachedProfile().getString("login"));
    }

    @Test(timeout=10000) public void oldCatalogueCannotCrossExplicitLogout() throws Exception {
        api.login("reader", "dummy-test-password"); delayBrowse.set(true);
        Future<JSONObject> browse = workers.submit(() -> api.browse(""));
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        api.forgetSession(); release.countDown();
        try { browse.get(3, TimeUnit.SECONDS); fail(); }
        catch (ExecutionException expected) { assertTrue(expected.getCause() instanceof Api.SessionChangedException); }
        assertFalse(api.isSignedIn()); assertNull(api.cachedProfile());
    }
    @Test(timeout=10000) public void delayedGuestResponseCannotUndoNewLogin() throws Exception {
        api.login("reader1","dummy-test-password");delay.set(true);
        Future<JSONObject> old = workers.submit(()->new Api(new AccountSession(disk)).me());
        assertTrue(entered.await(3,TimeUnit.SECONDS));
        api.login("reader2","dummy-test-password");release.countDown();
        assertEquals("reader2",old.get(3,TimeUnit.SECONDS).getJSONObject("user").getString("login"));
        assertEquals("reader2",api.cachedProfile().getString("login"));assertTrue(api.isSignedIn());
    }
}
