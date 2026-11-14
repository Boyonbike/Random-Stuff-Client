package dev.argus.client.gui;

import dev.argus.client.ClientAccess;
import dev.argus.client.ClientContext;
import dev.argus.client.utils.movement.VolytraElytraFlyHandler;
import dev.argus.client.utils.movement.volytra.AutonavWaypoint;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;

/**
 * ElytraFly's Autonav trip card: type where to go (or pick a saved destination), then Start, Pause and Stop the trip;
 * Save keeps the typed destination in the list underneath.
 *
 * <pre>
 *  [ Name           ] [ X      ] [ Z      ]  [Here] [Save]
 *  Flying to 1200, -300 · 840 blocks left   [Start] [Pause] [Stop]
 *  ─────────────────────────────────────────────────────────
 *  Spawn base      100, 200 · 1.2k away           [Go] [Delete]
 * </pre>
 */
final class AutonavCard extends InlineCard {
    private static final float INPUTS = 30, CONTROLS = 30, LIST_HEAD = 16, ROW = 26;

    private final VolytraElytraFlyHandler volytra;
    private final UiInput name, x, z;
    private String armed = "";
    private long armedAt;

    AutonavCard(UiScreen host, VolytraElytraFlyHandler volytra) {
        super(host);
        this.volytra = volytra;
        name = new UiInput(0, 0, 100, 16, "Name (optional)");
        name.setMaxLength(32);
        x = new UiInput(0, 0, 60, 16, "X");
        z = new UiInput(0, 0, 60, 16, "Z");
        for (UiInput field : List.of(x, z)) field.setMaxLength(9);
        x.setValue(Integer.toString(volytra.autonavTypedX()));
        z.setValue(Integer.toString(volytra.autonavTypedZ()));
        // The typed destination is kept in ElytraFly's settings, so it is saved with the config.
        x.setResponder(value -> keepTyped());
        z.setResponder(value -> keepTyped());
    }

    @Override String title() { return "Autonav trip"; }
    @Override String icon() { return "compass"; }
    @Override String subtitle() {
        int saved = volytra.autonavWaypoints().size();
        String list = saved == 0 ? "No saved destinations" : saved + (saved == 1 ? " saved destination" : " saved destinations");
        return volytra.autonavTripActive() ? "Trip in progress · " + list : volytra.autonavTripPaused() ? "Trip paused · " + list : list;
    }
    @Override boolean visible() { return volytra.autonavTripShown(); }
    @Override float bodyHeight() { return INPUTS + CONTROLS + LIST_HEAD + Math.max(1, volytra.autonavWaypoints().size()) * ROW + 4; }

    private List<UiInput> fields() { return List.of(name, x, z); }

    // ---- fields ------------------------------------------------------------------------------

    private static Integer parse(String text) {
        try { return Integer.parseInt(text.trim()); } catch (NumberFormatException failure) { return null; }
    }

    private void keepTyped() {
        Integer typedX = parse(x.getValue()), typedZ = parse(z.getValue());
        if (typedX != null && typedZ != null) volytra.setAutonavTyped(typedX, typedZ);
    }

    /** The typed X and Z; throws with a reason the page shows when either isn't a whole number. */
    private int[] typed() {
        Integer typedX = parse(x.getValue()), typedZ = parse(z.getValue());
        if (typedX == null || typedZ == null) throw new IllegalArgumentException("Type whole numbers for X and Z.");
        if (Math.abs(typedX) > 30_000_000 || Math.abs(typedZ) > 30_000_000) throw new IllegalArgumentException("That's outside the world border.");
        return new int[]{typedX, typedZ};
    }

    private void fill(AutonavWaypoint waypoint) {
        name.setValue(waypoint.name());
        x.setValue(Integer.toString(waypoint.x()));
        z.setValue(Integer.toString(waypoint.z()));
        keepTyped();
    }

    private void useMyPosition() {
        LocalPlayer player = ClientAccess.argusclientMinecraft.player;
        if (player == null) throw new IllegalStateException("Join a world first.");
        x.setValue(Integer.toString(player.getBlockX()));
        z.setValue(Integer.toString(player.getBlockZ()));
        keepTyped();
    }

    private void save() {
        int[] at = typed();
        volytra.saveAutonavWaypoint(name.getValue(), at[0], at[1]);
        name.setValue("");
        saveConfig();
    }

    private void start() {
        if (!volytra.autonavTripPaused()) { int[] at = typed(); volytra.setAutonavTyped(at[0], at[1]); }
        volytra.startAutonavTrip();
    }

    private static void saveConfig() {
        if (ClientContext.configurationManager != null) ClientContext.configurationManager.saveAsync();
    }

    @Override void reattach() { for (UiInput field : fields()) host.hostAdd(field); }
    @Override void stopEditing() { for (UiInput field : fields()) field.setFocused(false); }
    @Override void hide() { for (UiInput field : fields()) { field.visible = false; field.setFocused(false); } }

    @Override boolean widgetClicked(MouseButtonEvent event, boolean doubled) {
        for (UiInput field : fields()) {
            if (field.visible && field.isMouseOver(event.x(), event.y())) {
                for (UiInput other : fields()) if (other != field) other.setFocused(false);
                host.hostFocus(field);
                return field.mouseClicked(event, doubled);
            }
        }
        stopEditing();
        return false;
    }

    @Override boolean keyPressed(KeyEvent event) {
        List<UiInput> fields = fields();
        int focused = -1;
        for (int i = 0; i < fields.size(); i++) if (fields.get(i).isFocused() && fields.get(i).visible) focused = i;
        if (focused < 0) return false;
        int key = event.key();
        if (key == 257 || key == 335) { attempt(this::save, "Saved the destination."); return true; }
        if (key == 258) {
            // Tab moves through Name, X and Z.
            fields.get(focused).setFocused(false);
            UiInput next = fields.get((focused + 1) % fields.size());
            host.hostFocus(next);
            next.setFocused(true);
            return true;
        }
        return false;
    }

    // ---- drawing -----------------------------------------------------------------------------

    private boolean isArmed(String key) { return key.equals(armed) && System.currentTimeMillis() - armedAt < 4000; }

    private static String distance(AutonavWaypoint waypoint) {
        LocalPlayer player = ClientAccess.argusclientMinecraft.player;
        if (player == null) return "";
        double blocks = Math.hypot(waypoint.x() + .5 - player.getX(), waypoint.z() + .5 - player.getZ());
        return " · " + (blocks >= 10_000 ? String.format("%.1fk", blocks / 1000) : Long.toString(Math.round(blocks))) + " blocks away";
    }

    /** Places a field; a label, when given, sits just left of its box. */
    private void placeField(GuiGraphicsExtractor g, int mx, int my, UiTheme t, UiInput field, String label, float fx, float cy, float fw) {
        if (label != null) {
            UiDraw.text(g, label, fx, cy, UiDraw.mix(t.muted(), t.accent(), field.isFocused() ? 1 : 0));
            fx += 10; fw -= 10;
        }
        field.place(Math.round(fx), Math.round(cy - 8), Math.round(fw));
        field.visible = inView(cy - 8, 16);
        if (field.visible) field.extractRenderState(g, mx, my, 0); else field.setFocused(false);
    }

    @Override void draw(GuiGraphicsExtractor g, int mx, int my, UiTheme t, float left, float y, float w) {
        // Destination: name, X, Z, then fill from where you are and save to the list.
        float cy = y + INPUTS / 2 + 2, right = left + w;
        float saveW = buttonWidth("Save") + 4, hereW = buttonWidth("Here") + 4;
        button(g, mx, my, t, "save", right - saveW, cy - 9, saveW, 18, "Save", true, "Save these coordinates to the list below (Enter)",
                () -> attempt(this::save, "Saved the destination."));
        right -= saveW + 4;
        button(g, mx, my, t, "here", right - hereW, cy - 9, hereW, 18, "Here", false, "Fill X and Z with where you are now",
                () -> attempt(this::useMyPosition, ""));
        right -= hereW + 6;
        float space = right - left, coordW = Math.max(56, Math.min(96, space * .24f));
        float nameW = Math.max(40, space - coordW * 2 - 12);
        placeField(g, mx, my, t, name, null, left, cy, nameW);
        placeField(g, mx, my, t, x, "X", left + nameW + 6, cy, coordW);
        placeField(g, mx, my, t, z, "Z", left + nameW + coordW + 12, cy, coordW);

        // Trip controls: Start (or Resume), Pause and Stop, with how the trip is going.
        cy = y + INPUTS + CONTROLS / 2;
        right = left + w;
        boolean active = volytra.autonavTripActive(), paused = volytra.autonavTripPaused();
        String startLabel = paused ? "Resume" : "Start";
        float stopW = buttonWidth("Stop") + 4, pauseW = buttonWidth("Pause") + 4, startW = buttonWidth("Resume") + 4;
        button(g, mx, my, t, "stop", right - stopW, cy - 9, stopW, 18, "Stop", false,
                active || paused ? "End the trip and hand the flight back to you" : "No trip to stop",
                () -> attempt(volytra::stopAutonavTrip, "Trip stopped."));
        right -= stopW + 4;
        button(g, mx, my, t, "pause", right - pauseW, cy - 9, pauseW, 18, "Pause", false,
                active ? "Hand the flight back to you; Resume carries on to the same place" : "No trip to pause",
                () -> attempt(volytra::pauseAutonavTrip, "Trip paused."));
        right -= pauseW + 4;
        button(g, mx, my, t, "start", right - startW, cy - 9, startW, 18, startLabel, !active,
                paused ? "Carry on to where the paused trip was going" : "Fly to the X and Z above (turns Autonav on)",
                () -> attempt(this::start, paused ? "Trip resumed." : "Trip started."));
        right -= startW + 8;
        int dot = active ? t.accent() : paused ? UiDraw.mix(t.muted(), t.accent(), .5f) : UiDraw.alpha(t.muted(), .6f);
        UiDraw.circle(g, left + 6, cy, 3, dot);
        UiDraw.text(g, UiDraw.fit(volytra.autonavTripStatus(), right - left - 16, UiDraw.SMALL), left + 14, cy, UiDraw.SMALL, active ? t.text() : t.muted());

        // Saved destinations.
        float listTop = y + INPUTS + CONTROLS;
        UiDraw.rect(g, left + 4, listTop - .3f, w - 8, .6f, UiDraw.alpha(t.overlay(), .4f));
        List<AutonavWaypoint> saved = volytra.autonavWaypoints();
        UiDraw.text(g, "Saved destinations" + (saved.isEmpty() ? "" : " · " + saved.size()), left + 4, listTop + LIST_HEAD / 2 + 1, UiDraw.SMALL, t.muted());
        float rowsTop = listTop + LIST_HEAD;
        if (saved.isEmpty()) {
            UiDraw.text(g, UiDraw.fit("None yet · type a name and coordinates above, then Save", w - 8, UiDraw.SMALL), left + 4, rowsTop + ROW / 2, UiDraw.SMALL, UiDraw.alpha(t.muted(), .8f));
            return;
        }
        int typedX = volytra.autonavTypedX(), typedZ = volytra.autonavTypedZ();
        for (int i = 0; i < saved.size(); i++) {
            AutonavWaypoint waypoint = saved.get(i);
            String rowKey = "ic.autonav.row." + waypoint.name();
            float ry = rowsTop + UiMotion.rowY(rowKey, "", i * ROW), rcy = ry + ROW / 2;
            float before = UiRender.opacity;
            UiRender.opacity = before * UiMotion.rowAppear(rowKey, "");
            float rowRight = left + w - 2;
            String deleteKey = "del." + waypoint.name();
            boolean waiting = isArmed(deleteKey);
            float delW = buttonWidth("Confirm") + 4, goW = buttonWidth("Go") + 8, deleteX = rowRight - delW;
            button(g, mx, my, t, deleteKey, deleteX, rcy - 8, delW, 16, waiting ? "Confirm" : "Delete", waiting, "Remove from the list", () -> {
                if (!isArmed(deleteKey)) { armed = deleteKey; armedAt = System.currentTimeMillis(); say("Click Confirm to delete " + waypoint.name() + "."); return; }
                armed = "";
                attempt(() -> { volytra.removeAutonavWaypoint(waypoint.name()); saveConfig(); }, "Deleted " + waypoint.name() + ".");
            });
            rowRight -= delW + 4;
            button(g, mx, my, t, "go." + waypoint.name(), rowRight - goW, rcy - 8, goW, 16, "Go", true, "Fly here now",
                    () -> attempt(() -> { fill(waypoint); volytra.flyAutonavTo(waypoint.x(), waypoint.z()); }, "Flying to " + waypoint.name() + "."));
            rowRight -= goW + 4;
            float rowW = rowRight - left;
            boolean current = waypoint.x() == typedX && waypoint.z() == typedZ;
            boolean hover = hovering(mx, my, left, ry, rowW, ROW - 2);
            float hv = UiAnim.smooth(rowKey + ".h", hover ? 1 : 0, 18), act = UiAnim.smooth(rowKey + ".act", current ? 1 : 0, 16);
            int idle = hv > 0 ? UiDraw.alpha(t.surface(), hv * .8f) : i % 2 == 0 ? UiDraw.alpha(t.surface(), .3f) : UiDraw.alpha(t.surface(), 0f);
            UiDraw.rounded(g, left, ry + 1, rowW, ROW - 2, 6, UiDraw.mix(idle, UiDraw.alpha(t.accent(), .14f + hv * .06f), act));
            if (act > .01f) UiDraw.rounded(g, left + 2, ry + 6 + (ROW - 12) * (1 - act) / 2, 2, (ROW - 12) * act, 1, t.accent());
            UiDraw.text(g, UiDraw.fit(waypoint.name(), rowW - 16), left + 10, rcy - 5, UiDraw.mix(t.text(), t.accent(), act));
            UiDraw.text(g, UiDraw.fit(waypoint.x() + ", " + waypoint.z() + distance(waypoint), rowW - 16, UiDraw.SMALL), left + 10, rcy + 6, UiDraw.SMALL, t.muted());
            UiRender.opacity = before;
            hit(left, ry, rowW, ROW - 2, () -> fill(waypoint));
            if (waiting) UiMotion.drain(g, deleteX + 6, rcy + 6, delW - 12, System.nanoTime() - (System.currentTimeMillis() - armedAt) * 1_000_000L, 4_000_000_000L, UiDraw.alpha(t.accent(), .8f));
        }
    }
}
