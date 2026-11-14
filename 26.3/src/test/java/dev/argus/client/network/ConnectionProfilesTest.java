package dev.argus.client.network;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ConnectionProfilesTest {
    @TempDir Path dir;
    @Test void importsSupportedFormatsWithoutLosingCredentials() {
        var p = ProxyEntry.parse("socks5://user:secret@127.0.0.1:1080");
        assertEquals("user", p.username()); assertEquals("secret", p.password());
        assertEquals(ProxyEntry.Type.SOCKS5, p.type());
        assertEquals(ProxyEntry.Type.SOCKS4, ProxyEntry.parse("Office=127.0.0.1:1080@socks4").type());
        assertEquals("pass", ProxyEntry.parse("127.0.0.1:1080:user:pass").password());
        assertEquals("localhost", ProxyEntry.parse("localhost:1080").host());
        assertFalse(p.toString().contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> ProxyEntry.parse("http://127.0.0.1:1080"));
        assertThrows(IllegalArgumentException.class, () -> ProxyEntry.parse("127.0.0.1:70000"));
    }
    @Test void selectionAndRemovalSurviveReload() throws Exception {
        var store = new ConnectionProfiles(dir.resolve("connections.json"));
        var first = ProxyEntry.parse("127.0.0.1:1080"); var second = ProxyEntry.parse("127.0.0.2:1080");
        store.putProxy(first); store.putProxy(second); store.selectProxy(second.id());
        var loaded = new ConnectionProfiles(dir.resolve("connections.json"));
        assertEquals(second.id(), loaded.activeProxy().id());
        loaded.removeProxy(second.id()); assertNull(loaded.activeProxy());
        assertEquals(1, new ConnectionProfiles(dir.resolve("connections.json")).proxies().size());
    }
    @Test void keepProxyDefaultsOffAndSurvivesReload() throws Exception {
        Path path = dir.resolve("connections.json");
        var store = new ConnectionProfiles(path);
        assertFalse(store.keepProxy());
        var proxy = ProxyEntry.parse("127.0.0.1:1080"); store.putProxy(proxy);
        store.setKeepProxy(true);
        var loaded = new ConnectionProfiles(path);
        assertTrue(loaded.keepProxy());
        assertEquals(proxy.id(), loaded.activeProxy().id());
        // Files written before the setting existed start on a direct connection.
        Files.writeString(path, Files.readString(path).replace("\"keepProxy\": true,", ""));
        assertFalse(new ConnectionProfiles(path).keepProxy());
    }
    @Test void lobbyPasswordsFollowCrackedAccountsAcrossReloadAndRemoval() throws Exception {
        Path path = dir.resolve("connections.json");
        var store = new ConnectionProfiles(path);
        var alt = AccountEntry.offline("AltOne"); var main = AccountEntry.offline("MainTwo");
        store.putAccount(alt); store.putAccount(main);
        store.setLobbyPassword(alt.id(), "hunter2");
        assertEquals("hunter2", store.lobbyPasswordFor("altone"));
        assertNull(store.lobbyPasswordFor("MainTwo"));
        assertThrows(IllegalArgumentException.class, () -> store.setLobbyPassword(main.id(), "two words"));
        var loaded = new ConnectionProfiles(path);
        assertEquals("hunter2", loaded.lobbyPasswordFor("AltOne"));
        // Re-adding the same cracked name gets a new id but keeps its password.
        var readded = AccountEntry.offline("AltOne"); loaded.putAccount(readded);
        assertEquals("hunter2", loaded.lobbyPassword(readded));
        loaded.setLobbyPassword(readded.id(), "");
        assertNull(loaded.lobbyPasswordFor("AltOne"));
        loaded.setLobbyPassword(readded.id(), "again");
        loaded.removeAccount(readded.id());
        assertNull(new ConnectionProfiles(path).lobbyPasswordFor("AltOne"));
        assertFalse(Files.readString(path).contains("again"));
    }
    @Test void malformedStorageIsNeverSilentlyOverwritten() throws Exception {
        Path path = dir.resolve("connections.json"); java.nio.file.Files.writeString(path, "broken");
        assertThrows(java.io.IOException.class, () -> new ConnectionProfiles(path));
        assertEquals("broken", java.nio.file.Files.readString(path));
    }
    @Test void openingProfilesRepairsPermissionsLoosenedByOldMigration() throws Exception {
        assumeTrue(Files.getFileStore(dir).supportsFileAttributeView("posix"));
        Path path = dir.resolve("connections.json");
        var store = new ConnectionProfiles(path);
        store.putProxy(ProxyEntry.parse("127.0.0.1:1080:user:eggclient-secret"));
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"));
        String previous = Files.readString(path);

        var loaded = new ConnectionProfiles(path);

        assertEquals("eggclient-secret", loaded.proxies().getFirst().password());
        assertEquals(previous, Files.readString(path));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
    }
    @Test void storesAccountsAndReplacesDuplicateIdentity() throws Exception {
        var store = new ConnectionProfiles(dir.resolve("connections.json"));
        var account = AccountEntry.offline("Player_1"); store.putAccount(account);
        store.putAccount(AccountEntry.offline("player_1"));
        assertEquals(1, store.accounts().size());
        assertEquals(AccountEntry.Type.OFFLINE, new ConnectionProfiles(dir.resolve("connections.json")).accounts().getFirst().type());
        assertThrows(IllegalArgumentException.class, () -> AccountEntry.offline("bad name"));
        assertFalse(AccountEntry.session("private-token").toString().contains("private-token"));
    }
}
