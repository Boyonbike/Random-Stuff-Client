package dev.argus.client.network;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Transactional persistence shared by both managers. Never falls back to an empty store on corruption. */
public final class ConnectionProfiles {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");
    private final Path path;
    private List<ProxyEntry> proxies = new ArrayList<>();
    private List<AccountEntry> accounts = new ArrayList<>();
    private String selected;
    /** Whether the switched-on proxy stays on across restarts. Off means every launch starts on a direct connection. */
    private boolean keepProxy;
    /** AutoLobby's /login password per saved cracked account, keyed by lower-case username so re-adding the account keeps it. */
    private Map<String, String> lobbyPasswords = new HashMap<>();
    public ConnectionProfiles(Path path) throws IOException {
        this.path = path;
        if (!Files.exists(path)) return;
        restrictPermissions(path);
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException();
            for (JsonElement e : root.getAsJsonArray("proxies")) proxies.add(GSON.fromJson(e, ProxyEntry.class));
            for (JsonElement e : root.getAsJsonArray("accounts")) accounts.add(GSON.fromJson(e, AccountEntry.class));
            selected = root.has("selected") ? root.get("selected").getAsString() : null;
            keepProxy = root.has("keepProxy") && root.get("keepProxy").getAsBoolean();
            if (root.has("lobbyPasswords")) for (var e : root.getAsJsonObject("lobbyPasswords").entrySet()) lobbyPasswords.put(e.getKey(), e.getValue().getAsString());
            lobbyPasswords = pruned(lobbyPasswords, accounts);
            if (proxies.stream().anyMatch(Objects::isNull) || accounts.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw new IOException("Couldn't read saved accounts/proxies. The original file has been preserved."); }
    }
    public synchronized List<ProxyEntry> proxies() { return List.copyOf(proxies); }
    public synchronized List<AccountEntry> accounts() { return List.copyOf(accounts); }
    public synchronized ProxyEntry activeProxy() { return proxies.stream().filter(p -> p.id().equals(selected)).findFirst().orElse(null); }
    public synchronized boolean keepProxy() { return keepProxy; }
    public synchronized void setKeepProxy(boolean keep) throws IOException { write(proxies, accounts, selected, keep); }
    public synchronized void selectProxy(String id) throws IOException {
        if (id != null && proxies.stream().noneMatch(p -> p.id().equals(id))) throw new IllegalArgumentException("Proxy no longer exists.");
        write(proxies, accounts, id);
    }
    public synchronized void putProxy(ProxyEntry proxy) throws IOException {
        var next = new ArrayList<>(proxies); next.removeIf(p -> p.id().equals(proxy.id()));
        if (next.stream().anyMatch(p -> p.host().equalsIgnoreCase(proxy.host()) && p.port() == proxy.port() && p.type() == proxy.type())) throw new IllegalArgumentException("This proxy is already saved.");
        next.add(proxy); write(next, accounts, proxies.isEmpty() ? proxy.id() : selected);
    }
    /** Imports one transaction rather than writing the credentials file once per line. */
    public synchronized int importProxies(List<ProxyEntry> imported) throws IOException {
        var next = new ArrayList<>(proxies);
        var keys = new HashSet<String>();
        for (var p : next) keys.add(p.type() + ":" + p.host().toLowerCase(Locale.ROOT) + ":" + p.port());
        int added = 0;
        for (var p : imported) if (keys.add(p.type() + ":" + p.host().toLowerCase(Locale.ROOT) + ":" + p.port())) { next.add(p); added++; }
        if (added > 0) write(next, accounts, proxies.isEmpty() ? next.getFirst().id() : selected);
        return added;
    }
    public synchronized void removeProxy(String id) throws IOException { var next = new ArrayList<>(proxies); next.removeIf(p -> p.id().equals(id)); write(next, accounts, Objects.equals(id, selected) ? null : selected); }
    public synchronized void putAccount(AccountEntry account) throws IOException {
        var next = new ArrayList<>(accounts);
        next.removeIf(a -> a.id().equals(account.id()) || (a.type() == account.type() && (!account.uuid().isBlank() ? a.uuid().equalsIgnoreCase(account.uuid()) || a.username().equalsIgnoreCase(account.username()) : a.credential().equals(account.credential()))));
        next.add(account); write(proxies, next, selected);
    }
    public synchronized void removeAccount(String id) throws IOException { var next = new ArrayList<>(accounts); next.removeIf(a -> a.id().equals(id)); write(proxies, next, selected); }

    /** The AutoLobby password saved for a cracked account, or null. */
    public synchronized String lobbyPassword(AccountEntry account) {
        return account.type() == AccountEntry.Type.OFFLINE ? lobbyPasswords.get(lobbyKey(account.username())) : null;
    }
    /** The AutoLobby password for the cracked account with this username, or null when it has none. */
    public synchronized String lobbyPasswordFor(String username) {
        if (username == null) return null;
        String key = lobbyKey(username);
        return accounts.stream().anyMatch(a -> a.type() == AccountEntry.Type.OFFLINE && lobbyKey(a.username()).equals(key)) ? lobbyPasswords.get(key) : null;
    }
    /** Saves, or with a blank password clears, the AutoLobby password for a saved cracked account. */
    public synchronized void setLobbyPassword(String accountId, String password) throws IOException {
        AccountEntry account = accounts.stream().filter(a -> a.id().equals(accountId)).findFirst().orElse(null);
        if (account == null) throw new IllegalArgumentException("Account no longer exists.");
        if (account.type() != AccountEntry.Type.OFFLINE) throw new IllegalArgumentException("Only cracked accounts use a lobby password.");
        var next = new HashMap<>(lobbyPasswords);
        if (password == null || password.isBlank()) next.remove(lobbyKey(account.username()));
        else {
            if (password.length() > 64 || password.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
                throw new IllegalArgumentException("Use a password of up to 64 characters with no spaces.");
            next.put(lobbyKey(account.username()), password);
        }
        write(proxies, accounts, selected, keepProxy, next);
    }
    private static String lobbyKey(String username) { return username.strip().toLowerCase(Locale.ROOT); }
    /** Drops passwords whose cracked account has been removed. */
    private static Map<String, String> pruned(Map<String, String> passwords, List<AccountEntry> a) {
        var names = new HashSet<String>();
        for (var account : a) if (account.type() == AccountEntry.Type.OFFLINE) names.add(lobbyKey(account.username()));
        var next = new HashMap<>(passwords); next.keySet().retainAll(names);
        return next;
    }

    private void write(List<ProxyEntry> p, List<AccountEntry> a, String s) throws IOException { write(p, a, s, keepProxy); }
    private void write(List<ProxyEntry> p, List<AccountEntry> a, String s, boolean keep) throws IOException { write(p, a, s, keep, lobbyPasswords); }
    private void write(List<ProxyEntry> p, List<AccountEntry> a, String s, boolean keep, Map<String, String> passwords) throws IOException {
        passwords = pruned(passwords, a);
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.addProperty("keepProxy", keep); root.add("proxies", GSON.toJsonTree(p)); root.add("accounts", GSON.toJsonTree(a)); if (s != null) root.addProperty("selected", s);
        if (!passwords.isEmpty()) root.add("lobbyPasswords", GSON.toJsonTree(new TreeMap<>(passwords)));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temp = Files.createTempFile(path.toAbsolutePath().getParent(), ".connections-", ".tmp");
        try {
            restrictPermissions(temp);
            Files.writeString(temp, GSON.toJson(root));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            proxies = new ArrayList<>(p); accounts = new ArrayList<>(a); selected = s; keepProxy = keep; lobbyPasswords = passwords;
        } finally { Files.deleteIfExists(temp); }
    }

    private static void restrictPermissions(Path path) throws IOException {
        var attributes = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (attributes != null && !attributes.readAttributes().permissions().equals(PRIVATE_PERMISSIONS)) {
            attributes.setPermissions(PRIVATE_PERMISSIONS);
        }
    }
}
