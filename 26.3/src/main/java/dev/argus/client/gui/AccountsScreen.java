package dev.argus.client.gui;

import com.mojang.blaze3d.Blaze3D;
import dev.argus.client.ClientContext;
import dev.argus.client.modules.exploit.FakeVanillaModule;
import dev.argus.client.network.*;
import java.net.URI;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

public final class AccountsScreen extends ProfileManagerScreen {
    private static final long CONFIRM_NANOS = 3_000_000_000L;
    private AccountEntry.Type adding;
    private final UiInput credential = new UiInput(0, 0, 100, 12, "Username / account token");
    private final UiInput lobbyPassword = new UiInput(0, 0, 100, 12, "Password AutoLobby sends with /login").secret(true);
    /** The cracked account whose AutoLobby password is being edited, or null. */
    private AccountEntry passwordFor;
    private String link = "", deleting;
    private long deleteAt;

    public AccountsScreen(Screen parent) { super("Accounts", parent); credential.setMaxLength(8192); lobbyPassword.setMaxLength(64); }

    // ---- header, footer, toolbar ---------------------------------------------------------------

    @Override protected String status() { return "Logged in as " + minecraft.getUser().getName(); }
    @Override protected boolean statusGood() { return true; }
    @Override protected String hint() { return "Switch accounts without restarting · Disconnect from a server first"; }
    @Override protected String[] empty() { return new String[]{"player", "No saved accounts", "Add one above to switch without restarting Minecraft."}; }

    @Override protected void buildToolbar(int x, int y, int w) {
        toolbar(x, y, w, new String[]{"Microsoft", "Cracked", "Session", "Altening"}, new String[]{"plus", "plus", "plus", "plus"},
                new Runnable[]{this::addMicrosoft, () -> addForm(AccountEntry.Type.OFFLINE), () -> addForm(AccountEntry.Type.SESSION), () -> addForm(AccountEntry.Type.ALTENING)});
    }

    // ---- add form / Microsoft sign-in card ------------------------------------------------------

    private boolean signingIn() { return busy && !link.isEmpty(); }
    @Override protected boolean formOpen() { return adding != null || passwordFor != null || signingIn(); }
    @Override protected void closeForm() { adding = null; passwordFor = null; credential.setValue(""); lobbyPassword.setValue(""); }

    @Override protected void buildForm(int x, int y, int w) {
        if (signingIn()) {
            int bw = 90;
            button(x + w - 10 - bw, y + 44, bw, "Copy link", null, () -> { minecraft.keyboardHandler.setClipboard(link); info("Sign-in link copied."); }, false, true);
            button(x + w - 16 - bw * 2, y + 44, bw, "Open link", null, () -> Blaze3D.openUri(URI.create(link)), true, true);
            return;
        }
        field(passwordFor != null ? lobbyPassword : credential, x + 10, y + 50, w - 20);
    }

    @Override protected void submitForm() {
        if (passwordFor != null && !busy) {
            var account = passwordFor;
            String password = lobbyPassword.getValue();
            change(() -> { profiles.setLobbyPassword(account.id(), password); closeForm(); },
                    password.isBlank() ? "Cleared the lobby password for " + account.username() + "." : "Saved the lobby password for " + account.username() + ".");
            return;
        }
        if (adding == null || busy) return;
        try { login(adding == AccountEntry.Type.OFFLINE ? AccountEntry.offline(credential.getValue()) : adding == AccountEntry.Type.SESSION ? AccountEntry.session(credential.getValue()) : AccountEntry.altening(credential.getValue())); }
        catch (IllegalArgumentException e) { error(e.getMessage()); }
    }

    @Override protected void renderForm(GuiGraphicsExtractor g, int x, int y, int w, int mx, int my) {
        var t = GuiState.theme();
        if (signingIn()) {
            UiDraw.card(g, x, y, w, 74, "player", "Sign in with Microsoft", "Finish signing in in your browser, then come back here", 0, t);
            UiDraw.text(g, UiDraw.fit(link, w - 220, UiDraw.SMALL), x + 10, y + 55, UiDraw.SMALL, t.blue());
            return;
        }
        if (passwordFor != null) {
            UiDraw.card(g, x, y, w, 72, "shield", "Lobby password · " + passwordFor.username(), "AutoLobby sends /login with this when you play as this account", 120, t);
            float right = x + w - 10;
            right -= pill(g, "Cancel", right, y + 15, mx, my, t.muted(), false, !busy, () -> { closeForm(); rebuildWidgets(); }) + 4;
            pill(g, "Save", right, y + 15, mx, my, t.accent(), true, !busy, this::submitForm);
            label(g, "Stored on this computer · leave empty and save to clear it", x + 10, y + 38);
            return;
        }
        String title = switch (adding) { case OFFLINE -> "Add cracked account"; case SESSION -> "Add session account"; default -> "Add Altening account"; };
        String sub = switch (adding) {
            case OFFLINE -> "Plays on servers with online mode turned off";
            case SESSION -> "Uses a Minecraft access token you already have";
            default -> "Uses an alt token from TheAltening";
        };
        UiDraw.card(g, x, y, w, 72, "player", title, sub, 120, t);
        float right = x + w - 10;
        right -= pill(g, "Cancel", right, y + 15, mx, my, t.muted(), false, !busy, () -> { closeForm(); rebuildWidgets(); }) + 4;
        pill(g, "Add & log in", right, y + 15, mx, my, t.accent(), true, !busy, this::submitForm);
        label(g, adding == AccountEntry.Type.OFFLINE ? "Username · 1–16 letters, digits or underscores" : "Account token · stored on this computer", x + 10, y + 38);
    }

    private void passwordForm(AccountEntry account) {
        adding = null; passwordFor = account; message = "";
        String saved = profiles.lobbyPassword(account);
        lobbyPassword.setValue(saved == null ? "" : saved);
        rebuildWidgets(); setFocused(lobbyPassword);
    }

    private void addForm(AccountEntry.Type type) {
        passwordFor = null; adding = type; credential.secret(type != AccountEntry.Type.OFFLINE); credential.setValue(""); message = "";
        rebuildWidgets(); setFocused(credential);
    }

    // ---- "Appear as vanilla" card (moved here from the multiplayer screen's Vanilla checkbox) --------

    private static final int CARD_Y = TOOLBAR_Y + 28, CARD_H = 30;
    private static FakeVanillaModule vanilla() { return ClientContext.getModule(FakeVanillaModule.class); }
    @Override protected int listY() { return vanilla() == null ? LIST_Y : LIST_Y + CARD_H + 8; }
    @Override protected void renderAboveList(GuiGraphicsExtractor g, int mx, int my) {
        var module = vanilla();
        if (module == null) return;
        var t = GuiState.theme();
        float x = left + 12, y = top + CARD_Y, w = panelWidth - 24;
        UiDraw.card(g, x, y, w, CARD_H, "shield", "Appear as vanilla", "Tells servers you're playing unmodded Minecraft · applies when you next join", 60, t);
        boolean hover = UiDraw.over(mx, my, x + w - 70, y + 6, 70, 18);
        float on = UiAnim.smooth("acc.vanilla", module.isToggled() ? 1 : 0, 16);
        UiDraw.right(g, module.isToggled() ? "On" : "Off", x + w - UiDraw.CARD_PAD - 24, y + 15, UiDraw.SMALL, UiDraw.mix(t.muted(), t.accent(), on));
        UiDraw.toggle(g, x + w - UiDraw.CARD_PAD - 18, y + 10, on, hover, t);
        hit(x + w - 70, y + 6, 70, 18, () -> module.setToggled(!module.isToggled()));
    }

    // ---- list ---------------------------------------------------------------------------------

    @Override protected int rowCount() { return profiles.accounts().size(); }
    @Override protected Object rowId(int index) { return profiles.accounts().get(index).id(); }

    private static String typeName(AccountEntry.Type type) {
        return switch (type) { case MICROSOFT -> "Microsoft"; case OFFLINE -> "Cracked"; case SESSION -> "Session"; case ALTENING -> "Altening"; };
    }
    private static int typeColour(AccountEntry.Type type, UiTheme t) {
        return switch (type) { case MICROSOFT -> t.blue(); case OFFLINE -> t.yellow(); case SESSION -> t.green(); case ALTENING -> t.accent(); };
    }

    @Override protected void renderRow(GuiGraphicsExtractor g, int index, float x, float y, float w, int mx, int my, boolean hover) {
        var t = GuiState.theme();
        var account = profiles.accounts().get(index);
        boolean active = !account.uuid().isEmpty() && account.uuid().equalsIgnoreCase(minecraft.getUser().getProfileId().toString());
        String key = "acc." + account.id();
        rowFrame(g, key, x, y, w, hover, active);
        float cy = y + (ROW - 3) / 2f;
        badge(g, "player", x + 15, cy, active ? t.accent() : typeColour(account.type(), t));
        // Actions, right to left.
        float right = x + w - 8;
        boolean confirm = account.id().equals(deleting) && System.nanoTime() - deleteAt < CONFIRM_NANOS;
        right -= confirmPill(g, key + ".del", "Delete", "Really delete?", confirm, deleteAt, CONFIRM_NANOS, right, cy, mx, my, t.red(), !busy, () -> {
            if (account.id().equals(deleting) && System.nanoTime() - deleteAt < CONFIRM_NANOS) { deleting = null; change(() -> profiles.removeAccount(account.id()), "Removed " + account.username() + "."); }
            else { deleting = account.id(); deleteAt = System.nanoTime(); }
        }) + 4;
        String loginLabel = active ? "Log in again" : "Log in";
        right -= pill(g, loginLabel, right, cy, mx, my, t.accent(), true, !busy, () -> login(account)) + 4;
        boolean hasLobbyPassword = profiles.lobbyPassword(account) != null;
        if (account.type() == AccountEntry.Type.OFFLINE)
            right -= pill(g, hasLobbyPassword ? "Lobby password" : "Set lobby password", right, cy, mx, my, t.muted(), false, !busy, () -> passwordForm(account)) + 4;
        float textX = x + 30, room = right - textX - 6;
        UiDraw.bold(g, UiDraw.fit(account.username(), room), textX, cy - 5, UiDraw.TEXT, active ? t.accent() : t.text());
        float cw = chip(g, typeName(account.type()), textX, cy + 6.5f, typeColour(account.type(), t));
        if (hasLobbyPassword) cw += chip(g, "AutoLobby", textX + cw + 4, cy + 6.5f, t.green()) + 4;
        if (!account.uuid().isEmpty()) UiDraw.text(g, UiDraw.fit(account.uuid(), room - cw - 6, 5.5f), textX + cw + 5, cy + 6.5f, 5.5f, UiDraw.alpha(t.muted(), .7f));
    }

    // ---- actions ------------------------------------------------------------------------------

    private void addMicrosoft() {
        link = "";
        var proxy = minecraft.getProxy(); var directory = minecraft.gameDirectory;
        background("Waiting for Microsoft sign-in…", () -> {
            var login = AccountAuthenticator.microsoft(url -> progress(screen -> { link = url; Blaze3D.openUri(URI.create(url)); rebuildWidgets(); }));
            return AccountSession.prepare(login, proxy, directory);
        }, this::completeLogin);
    }
    private void login(AccountEntry account) {
        if (minecraft.level != null || minecraft.getConnection() != null) { error("Disconnect before switching accounts."); return; }
        var proxy = minecraft.getProxy(); var directory = minecraft.gameDirectory;
        background("Logging in as " + account.username() + "…", () -> AccountSession.prepare(AccountAuthenticator.authenticate(account), proxy, directory), this::completeLogin);
    }
    private void completeLogin(AccountSession.Prepared prepared) {
        link = "";
        try {
            if (minecraft.level != null || minecraft.getConnection() != null) throw new IllegalStateException("Disconnect before switching accounts.");
            profiles.putAccount(prepared.login().account());
            AccountSession.apply(minecraft, prepared); closeForm();
            ok("Logged in as " + prepared.user().getName() + ".");
        } catch (IllegalStateException e) { error(e.getMessage()); }
        catch (Exception e) { error(safeError(e)); }
    }
    @Override protected void cancelTask() { link = ""; super.cancelTask(); }
}
