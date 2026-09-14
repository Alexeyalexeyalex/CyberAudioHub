package com.cyberaudio.hub;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class AccountSessionTest {
    static final class Memory implements AccountSession.Persistence {
        final Map<String, String> data = new HashMap<>();
        public String get(String key, String fallback) { return data.getOrDefault(key, fallback); }
        public void put(Map<String, String> changes) {
            for (Map.Entry<String, String> item : changes.entrySet()) {
                if (item.getValue() == null) data.remove(item.getKey()); else data.put(item.getKey(), item.getValue());
            }
        }
    }
    @Test public void freshInstallHasDefaultServerAndNoSession() {
        AccountSession account = new AccountSession(new Memory());
        assertEquals("http://188.32.242.100:2077", account.snapshot().server);
        assertEquals("", account.snapshot().cookie);
    }
    @Test public void accountSurvivesNewApiInstanceAndRestart() {
        Memory disk = new Memory(); AccountSession first = new AccountSession(disk);
        first.accept(first.beginLogin(), "session=valid", true, "profile");
        AccountSession restored = new AccountSession(disk);
        assertEquals("session=valid", restored.snapshot().cookie);
        assertEquals("profile", restored.cachedProfile());
    }
    @Test public void equivalentAddressDoesNotLogOut() {
        AccountSession account = new AccountSession(new Memory());
        account.accept(account.beginLogin(), "session=valid", true, "profile");
        assertFalse(account.setServer(" 188.32.242.100:2077/ "));
        assertEquals("session=valid", account.snapshot().cookie);
        assertEquals("profile", account.cachedProfile());
    }
    @Test public void oldResponseCannotRestoreAccountAfterLogoutOrServerSwitch() {
        AccountSession account = new AccountSession(new Memory());
        AccountSession.Snapshot old = account.beginLogin();
        account.forget(false);
        assertFalse(account.accept(old,"session=late",true,"wrong"));
        old = account.snapshot(); account.setServer("https://other.example");
        assertFalse(account.accept(old,"session=late",true,"wrong"));
        assertEquals("", account.cachedProfile());
        assertEquals("", account.snapshot().cookie);
    }
    @Test public void lateAnonymousResponseCannotWipeNewLogin() {
        AccountSession account = new AccountSession(new Memory());
        AccountSession.Snapshot anonymous = account.snapshot();
        account.accept(account.beginLogin(),"session=new",true,"new profile");
        assertFalse(account.accept(anonymous,"",true,null));
        assertEquals("session=new", account.snapshot().cookie);
    }
    @Test public void lateRefreshCannotReplaceNewerCookie() {
        AccountSession account = new AccountSession(new Memory());
        account.accept(account.beginLogin(),"session=one",true,"profile");
        AccountSession.Snapshot request = account.snapshot();
        assertTrue(account.accept(request,"session=two",false,null));
        assertFalse(account.accept(request,"session=old",false,null));
        assertEquals("session=two", account.snapshot().cookie);
    }
    @Test public void expiredSessionRetainsProfileButDoesNotPretendToBeAuthenticated() {
        AccountSession account = new AccountSession(new Memory());
        account.accept(account.beginLogin(),"session=valid",true,"profile");
        assertTrue(account.accept(account.snapshot(),null,true,null));
        assertEquals("", account.snapshot().cookie);
        assertEquals("profile", account.cachedProfile());
    }
    @Test public void invalidSettingsCannotClearExistingSession() {
        AccountSession account = new AccountSession(new Memory());
        account.accept(account.beginLogin(),"session=valid",true,"profile");
        try { account.setServer("https://"); fail(); } catch (IllegalArgumentException expected) { }
        assertEquals("session=valid", account.snapshot().cookie);
    }

    @Test public void interruptedReauthenticationKeepsPreviousLogin() {
        Memory disk = new Memory(); AccountSession account = new AccountSession(disk);
        assertTrue(account.acceptLogin(account.beginLogin(), "session=old", "old profile"));
        account.beginLogin(); // Process disappears before the network response.
        AccountSession reopened = new AccountSession(disk);
        assertEquals("session=old", reopened.snapshot().cookie);
        assertEquals("old profile", reopened.cachedProfile());
    }

    @Test public void completeLoginWinsOverOldRefreshAndRejectsItsLateReplies() {
        AccountSession account = new AccountSession(new Memory());
        account.acceptLogin(account.beginLogin(), "session=old", "old profile");
        AccountSession.Snapshot login = account.beginLogin(), refresh = account.snapshot();
        assertTrue(account.accept(refresh, "session=refreshed", false, null));
        AccountSession.Snapshot late = account.snapshot();
        assertTrue(account.acceptLogin(login, "session=new", "new profile"));
        assertFalse(account.accept(late, "session=refreshed-again", true, "old profile"));
        assertEquals("session=new", account.snapshot().cookie);
        assertEquals("new profile", account.cachedProfile());
    }

    @Test public void incompleteLoginCannotReplaceExistingAccount() {
        AccountSession account = new AccountSession(new Memory());
        account.acceptLogin(account.beginLogin(), "session=old", "old profile");
        AccountSession.Snapshot attempt = account.beginLogin();
        assertFalse(account.acceptLogin(attempt, null, "new profile"));
        assertFalse(account.acceptLogin(attempt, "", "new profile"));
        assertFalse(account.acceptLogin(attempt, "session=new", null));
        assertEquals("session=old", account.snapshot().cookie);
        assertEquals("old profile", account.cachedProfile());
    }

    @Test public void expiredPreviousSessionDoesNotCancelPendingCredentialLogin() {
        AccountSession account = new AccountSession(new Memory());
        account.acceptLogin(account.beginLogin(), "session=expired", "old profile");
        AccountSession.Snapshot login = account.beginLogin(), oldProfileRequest = account.snapshot();
        assertTrue(account.accept(oldProfileRequest, "", true, null));
        assertEquals("", account.snapshot().cookie);
        assertFalse(account.accept(oldProfileRequest, "session=stale", true, "old profile"));
        assertTrue(account.acceptLogin(login, "session=new", "new profile"));
        assertEquals("session=new", account.snapshot().cookie);
    }

    @Test public void supersededOrLoggedOutLoginCannotResurrectTheAccount() {
        AccountSession account = new AccountSession(new Memory());
        AccountSession.Snapshot first = account.beginLogin(), second = account.beginLogin();
        assertFalse(account.acceptLogin(first, "session=first", "first profile"));
        assertTrue(account.acceptLogin(second, "session=second", "second profile"));
        AccountSession.Snapshot next = account.beginLogin();
        account.forget(false);
        assertFalse(account.acceptLogin(next, "session=late", "late profile"));
        assertEquals("", account.snapshot().cookie);
    }

    @Test public void explicitLogoutClearsOnlyAccountNotDownloadedBooksOrServer() {
        Memory disk = new Memory(); AccountSession account = new AccountSession(disk);
        account.setServer("http://127.0.0.1:2078");
        disk.data.put("downloads", "saved books");
        disk.data.put("last_playback", "saved position");
        account.acceptLogin(account.beginLogin(), "session=valid", "profile");
        account.forget(false);
        AccountSession reopened = new AccountSession(disk);
        assertEquals("", reopened.snapshot().cookie);
        assertEquals("", reopened.cachedProfile());
        assertEquals("http://127.0.0.1:2078", reopened.snapshot().server);
        assertEquals("saved books", disk.data.get("downloads"));
        assertEquals("saved position", disk.data.get("last_playback"));
    }

    @Test public void sameAccountResponseGateDoesNotCrossAccountChanges() {
        AccountSession account = new AccountSession(new Memory());
        account.acceptLogin(account.beginLogin(), "session=valid", "profile");
        AccountSession.Snapshot request = account.snapshot();
        account.accept(request, "session=refreshed", false, null);
        assertTrue(account.sameSignedInAccount(request));
        account.acceptLogin(account.beginLogin(), "session=other", "other profile");
        assertFalse(account.sameSignedInAccount(request));
        request = account.snapshot();
        account.setServer("http://127.0.0.1:2078");
        assertFalse(account.sameSignedInAccount(request));
    }
}
