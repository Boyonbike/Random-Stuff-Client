package dev.argus.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A card on a module's page that holds a module's own tools (presets, combos, shortcuts) right alongside
 * its settings, instead of sending the player to another screen. Tool cards look and behave like settings
 * cards (header, fold, same buttons) and are built from two reusable kinds:
 * <ul>
 *   <li>{@link Rows}: labelled rows that each end in a button or a choice, for shortcuts and preferences;</li>
 *   <li>{@link Items}: a list of things the player manages, with per-item buttons and an optional "add" field.</li>
 * </ul>
 * Which module gets which card, and where, is declared in {@link ModuleTools}.
 */
abstract class InlineCard {
    private record Hit(float x, float y, float w, float h, Runnable run) {}

    protected final UiScreen host;
    private final List<Hit> hits = new ArrayList<>();
    private float clipTop = -Float.MAX_VALUE, clipBottom = Float.MAX_VALUE;
    private String error = "", notice = "";
    private long messageAt;

    InlineCard(UiScreen host) { this.host = host; }

    abstract String title();
    abstract String icon();
    abstract String subtitle();
    abstract float bodyHeight();
    /** Draws the body; {@code y} is the top of the body, below the card header. */
    abstract void draw(GuiGraphics g, int mx, int my, UiTheme t, float x, float y, float w);

    /** Whether the card is on the page right now (e.g. only while the settings it works with are shown). */
    boolean visible() { return true; }

    /** Whether the card is unfolded. */
    boolean open = true;
    private float shown = -1;
    /** Animated body height: eases to the full body when open and to nothing when folded. */
    final float shown() { return shown < 0 ? (open ? bodyHeight() : 0) : shown; }
    final void animate(boolean open) { shown = UiAnim.approach(shown < 0 ? (open ? bodyHeight() : 0) : shown, open ? bodyHeight() : 0, 16); }
    /** Called while the body is folded away, so a text field doesn't linger. */
    void hide() {}

    void reattach() {}
    void stopEditing() {}
    boolean keyPressed(KeyEvent event) { return false; }

    final void render(GuiGraphics g, int mx, int my, UiTheme t, float x, float y, float w, float clipTop, float clipBottom) {
        hits.clear();
        this.clipTop = clipTop; this.clipBottom = clipBottom;
        draw(g, mx, my, t, x, y, w);
    }

    /** For cards holding a text field: gets the click first. */
    boolean widgetClicked(MouseButtonEvent event, boolean doubled) { return false; }
    /** Right and middle clicks inside the visible part of the card. */
    boolean otherClicked(MouseButtonEvent event) { return false; }
    boolean dragged(MouseButtonEvent event) { return false; }
    boolean released() { return false; }
    /** Draws hover popups above everything else. */
    void popup(GuiGraphics g, UiTheme t, int width, int height) {}

    protected final float clipTop() { return clipTop; }
    protected final float clipBottom() { return clipBottom; }

    final boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (widgetClicked(event, doubled)) return true;
        if (event.y() < clipTop || event.y() >= clipBottom) return false;
        if (event.button() != 0) return otherClicked(event);
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (UiDraw.over(event.x(), event.y(), hit.x(), hit.y(), hit.w(), hit.h())) { hit.run().run(); return true; }
        }
        return false;
    }

    final String error() { return error.isEmpty() || System.nanoTime() - messageAt > 4_500_000_000L ? "" : error; }
    final String notice() { return notice.isEmpty() || System.nanoTime() - messageAt > 3_500_000_000L ? "" : notice; }
    protected final void say(String text) { notice = text; error = ""; messageAt = System.nanoTime(); }
    protected final void fail(String text) { error = text; notice = ""; messageAt = System.nanoTime(); }

    /** Runs a change to the module, reporting success or the reason it failed. */
    protected final void attempt(Runnable change, String success) {
        try { change.run(); say(success); }
        catch (RuntimeException failure) { fail(failure.getMessage() == null ? "That didn't work." : failure.getMessage()); }
    }

    // ---- helpers -----------------------------------------------------------------------------

    protected final boolean hovering(int mx, int my, float x, float y, float w, float h) {
        return UiDraw.over(mx, my, x, y, w, h) && my >= clipTop && my < clipBottom;
    }

    protected final boolean inView(float y, float h) { return y >= clipTop - 2 && y + h <= clipBottom + 2; }

    protected final void hit(float x, float y, float w, float h, Runnable run) {
        if (y + h / 2 >= clipTop && y + h / 2 < clipBottom) hits.add(new Hit(x, y, w, h, run));
    }

    /** A pill button; {@code accent} marks the card's main action. */
    protected final void button(GuiGraphics g, int mx, int my, UiTheme t, String key, float x, float y, float w, float h,
                                String label, boolean accent, String tip, Runnable run) {
        boolean hover = hovering(mx, my, x, y, w, h);
        float hv = UiAnim.smooth("ic." + title() + "." + key, hover ? 1 : 0, 18), ac = UiAnim.smooth("ic." + title() + "." + key + ".acc", accent ? 1 : 0, 16);
        int fill = UiDraw.mix(UiDraw.mix(t.surface(), t.overlay(), hv * .7f), UiDraw.alpha(t.accent(), .16f + hv * .1f), ac);
        UiDraw.rounded(g, x, y, w, h, h / 2, fill);
        UiDraw.outline(g, x, y, w, h, h / 2, .6f, UiDraw.mix(UiDraw.alpha(t.overlay(), .5f + hv * .3f), UiDraw.alpha(t.accent(), .4f + hv * .3f), ac));
        int colour = UiDraw.mix(UiDraw.mix(t.muted(), t.text(), hv), t.accent(), ac);
        // Label changes (e.g. to Confirm) crossfade rather than swap.
        UiMotion.crossfade(g, "ic." + title() + "." + key + ".label", label, l -> UiDraw.center(g, UiDraw.fit(l, w - 8, UiDraw.SMALL), x, y + h / 2, w, UiDraw.SMALL, colour));
        hit(x, y, w, h, run);
        if (hover && tip != null) host.tooltip(title() + "." + key, label, tip);
    }

    protected static float buttonWidth(String label) { return UiDraw.width(label, UiDraw.SMALL) + 18; }

    /** A segmented choice with a sliding thumb. */
    protected final void segmented(GuiGraphics g, int mx, int my, UiTheme t, String key, String[] labels, int chosen,
                                   float x, float y, float segment, float h, IntConsumer pick) {
        UiDraw.rounded(g, x, y, segment * labels.length, h, h / 2, t.crust());
        float thumb = UiAnim.smooth("ic." + title() + "." + key, chosen, 16);
        UiDraw.rounded(g, x + 2 + thumb * segment, y + 2, segment - 4, h - 4, (h - 4) / 2, UiDraw.alpha(t.accent(), .22f));
        for (int i = 0; i < labels.length; i++) {
            boolean hover = hovering(mx, my, x + i * segment, y, segment, h);
            int colour = i == chosen ? t.accent() : UiDraw.mix(t.muted(), t.text(), UiAnim.smooth("ic." + title() + "." + key + i, hover ? 1 : 0, 18));
            UiDraw.center(g, labels[i], x + i * segment, y + h / 2, segment, UiDraw.SMALL, colour);
            int index = i;
            hit(x + i * segment, y, segment, h, () -> pick.accept(index));
        }
    }

    // ---- rows of links and choices -----------------------------------------------------------

    /** A card of labelled rows, each with a button or a choice on its right. */
    static final class Rows extends InlineCard {
        private static final float ROW = 34;
        private final String title, icon;
        private final Supplier<String> subtitle;
        private final List<Row> rows = new ArrayList<>();
        private java.util.function.BooleanSupplier visible = () -> true;

        private record Row(String label, Supplier<String> detail, String[] choices, IntSupplier chosen, IntConsumer pick, Supplier<String> button, Runnable run) {}

        Rows(UiScreen host, String title, String icon, String subtitle) { this(host, title, icon, () -> subtitle); }
        /** With a subtitle read every frame, e.g. one showing what the module is doing. */
        Rows(UiScreen host, String title, String icon, Supplier<String> subtitle) { super(host); this.title = title; this.icon = icon; this.subtitle = subtitle; }

        /** Shows the card only while {@code when} holds. */
        Rows visibleWhen(java.util.function.BooleanSupplier when) { this.visible = when; return this; }

        /** A row with a button that opens another screen. */
        Rows open(String label, String detail, String button, Runnable run) { return open(label, detail, () -> button, run); }
        /** As above, with a button label that is read every frame, e.g. one showing progress. */
        Rows open(String label, String detail, Supplier<String> button, Runnable run) { rows.add(new Row(label, () -> detail, null, null, null, button, run)); return this; }
        /**
         * A row whose button acts on the module: its detail is read every frame, and the button reports {@code done},
         * or the reason the action threw, at the bottom of the page.
         */
        Rows action(String label, Supplier<String> detail, String button, String done, Runnable run) {
            rows.add(new Row(label, detail, null, null, null, () -> button, () -> attempt(run, done))); return this;
        }
        /** A row with a segmented choice. */
        Rows choice(String label, String detail, String[] choices, IntSupplier chosen, IntConsumer pick) { rows.add(new Row(label, () -> detail, choices, chosen, pick, null, null)); return this; }

        @Override boolean visible() { return visible.getAsBoolean(); }
        @Override String title() { return title; }
        @Override String icon() { return icon; }
        @Override String subtitle() { return subtitle.get(); }
        @Override float bodyHeight() { return rows.size() * ROW + 4; }

        @Override void draw(GuiGraphics g, int mx, int my, UiTheme t, float x, float y, float w) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                float ry = y + i * ROW, cy = ry + ROW / 2f;
                String buttonLabel = row.button() == null ? "" : row.button().get();
                float control = row.choices() != null ? row.choices().length * 56f : buttonWidth(buttonLabel) + 14;
                UiDraw.text(g, UiDraw.fit(row.label(), w - control - 24), x + 4, cy - 6, t.text());
                String detail = row.detail().get();
                UiDraw.text(g, UiDraw.fit(detail, w - control - 24, UiDraw.SMALL), x + 4, cy + 7, UiDraw.SMALL, t.muted());
                if (row.choices() != null) {
                    int chosen = Math.clamp(row.chosen().getAsInt(), 0, row.choices().length - 1);
                    segmented(g, mx, my, t, "row" + i, row.choices(), chosen, x + w - control - 4, cy - 9, 56, 18, row.pick());
                } else {
                    button(g, mx, my, t, "row" + i, x + w - control - 4, cy - 9, control, 18, buttonLabel, true, detail, row.run());
                }
                if (i < rows.size() - 1) UiDraw.rect(g, x + 4, ry + ROW - .3f, w - 8, .6f, UiDraw.alpha(t.overlay(), .3f));
            }
        }
    }

    // ---- a managed list ----------------------------------------------------------------------

    /**
     * A list of things the player manages (presets, combos): an optional "add" row on top, then one row per item
     * with its own buttons. Clicking an item's name runs its main action; a destructive action asks a second click.
     */
    static final class Items extends InlineCard {
        /** Draws a 16-unit square at the row's left. */
        interface Icon { void draw(GuiGraphics g, float x, float y); }
        /** A button; {@code done} is the message shown after it runs, or null. */
        record Action(String label, String tip, boolean accent, boolean confirm, String done, Runnable run) {
            static Action of(String label, String tip, String done, Runnable run) { return new Action(label, tip, false, false, done, run); }
            static Action main(String label, String tip, String done, Runnable run) { return new Action(label, tip, true, false, done, run); }
            static Action on(String label, String tip, boolean lit, Runnable run) { return new Action(label, tip, lit, false, null, run); }
            static Action danger(String label, String tip, String done, Runnable run) { return new Action(label, tip, false, true, done, run); }
        }
        record Entry(String label, String detail, boolean active, Icon icon, Runnable select, String selectDone, List<Action> actions) {}

        private final String title, icon, empty;
        private final Supplier<String> subtitle;
        private final Supplier<List<Entry>> entries;
        private final List<Action> headerActions = new ArrayList<>();
        private String hint = "";
        private UiInput field;
        private String submitLabel = "Add", submitTip;
        private Function<String, String> submitDone = text -> "Added " + text + ".";
        private Consumer<String> submit;
        private String armed = "";
        private long armedAt;

        Items(UiScreen host, String title, String icon, Supplier<String> subtitle, String empty, Supplier<List<Entry>> entries) {
            super(host); this.title = title; this.icon = icon; this.subtitle = subtitle; this.empty = empty; this.entries = entries;
        }

        /** A line of help shown at the left of the top row, when there is no field. */
        Items hint(String text) { hint = text; return this; }
        /** Buttons at the right of the top row. */
        Items header(Action action) { headerActions.add(action); return this; }
        /** A text field with a button to add what was typed. */
        Items field(String placeholder, int max, String button, String tip, Function<String, String> done, Consumer<String> submit) {
            field = new UiInput(0, 0, 100, 16, placeholder); field.setMaxLength(max);
            submitLabel = button; submitTip = tip; submitDone = done; this.submit = submit;
            return this;
        }

        @Override String title() { return title; }
        @Override String icon() { return icon; }
        @Override String subtitle() { return subtitle.get(); }

        private boolean hasTop() { return field != null || !headerActions.isEmpty() || !hint.isEmpty(); }
        private float rowHeight(List<Entry> list) { for (Entry e : list) if (e.icon() != null || !e.detail().isEmpty()) return 28; return 24; }
        @Override float bodyHeight() { List<Entry> list = entries.get(); return (hasTop() ? 26 : 0) + Math.max(1, list.size()) * rowHeight(list) + 4; }

        @Override void reattach() { if (field != null) host.hostAdd(field); }
        @Override void stopEditing() { if (field != null) field.setFocused(false); }
        @Override void hide() { if (field != null) { field.visible = false; field.setFocused(false); } }
        @Override boolean widgetClicked(MouseButtonEvent event, boolean doubled) {
            if (field == null) return false;
            if (field.visible && field.isMouseOver(event.x(), event.y())) { host.hostFocus(field); return field.mouseClicked(event, doubled); }
            field.setFocused(false);
            return false;
        }
        @Override boolean keyPressed(KeyEvent event) {
            if (field != null && field.isFocused() && field.visible && (event.key() == 257 || event.key() == 335)) { add(); return true; }
            return false;
        }

        private void add() {
            String text = field.getValue().trim();
            attempt(() -> submit.accept(text), submitDone.apply(text));
            if (error().isEmpty()) field.setValue("");
        }

        private boolean isArmed(String key) { return key.equals(armed) && System.currentTimeMillis() - armedAt < 4000; }

        /** Draws one button right-to-left from {@code right}; returns the new right edge. */
        private float actionButton(GuiGraphics g, int mx, int my, UiTheme t, String key, float right, float cy, Action a) {
            boolean waiting = a.confirm() && isArmed(key);
            String label = waiting ? "Confirm" : a.label();
            float bw = Math.max(a.label().length() <= 2 ? 20 : 0, buttonWidth(a.confirm() ? "Confirm" : label) + 4);
            button(g, mx, my, t, key, right - bw, cy - 8, bw, 16, label, a.accent() || waiting, a.tip(), () -> {
                if (a.confirm() && !isArmed(key)) { armed = key; armedAt = System.currentTimeMillis(); say("Click Confirm to " + a.label().toLowerCase() + "."); return; }
                armed = "";
                attempt(a.run(), a.done() == null ? "" : a.done());
            });
            if (waiting) UiMotion.drain(g, right - bw + 6, cy + 6, bw - 12, System.nanoTime() - (System.currentTimeMillis() - armedAt) * 1_000_000L, 4_000_000_000L, UiDraw.alpha(t.accent(), .8f));
            return right - bw - 4;
        }

        @Override void draw(GuiGraphics g, int mx, int my, UiTheme t, float x, float y, float w) {
            float top = 0;
            if (hasTop()) {
                float cy = y + 12, right = x + w;
                for (int i = headerActions.size() - 1; i >= 0; i--) right = actionButton(g, mx, my, t, "h" + i, right, cy, headerActions.get(i));
                if (field != null) {
                    float bw = buttonWidth(submitLabel) + 8;
                    button(g, mx, my, t, "add", right - bw, cy - 9, bw, 18, submitLabel, true, submitTip, this::add);
                    float fieldW = Math.max(40, right - bw - 6 - x);
                    field.place(Math.round(x), Math.round(cy - 8), Math.round(fieldW));
                    field.visible = inView(cy - 8, 16);
                    if (field.visible) field.render(g, mx, my, 0); else field.setFocused(false);
                } else UiDraw.text(g, UiDraw.fit(hint, right - x - 4, UiDraw.SMALL), x + 4, cy, UiDraw.SMALL, t.muted());
                top = 26;
            }
            List<Entry> list = entries.get();
            float rh = rowHeight(list);
            if (list.isEmpty()) UiDraw.text(g, empty, x + 4, y + top + rh / 2, UiDraw.SMALL, t.muted());
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                // Rows are keyed by their entry, so they fade in when added, glide up when one above is
                // removed, and hover/confirm states stay with the right row.
                String rowKey = "ic." + title + ".row." + e.label();
                float ry = y + top + UiMotion.rowY(rowKey, "", i * rh), cy = ry + rh / 2f;
                float rowBefore = UiRender.opacity;
                UiRender.opacity = rowBefore * UiMotion.rowAppear(rowKey, "");
                float right = x + w - 2;
                for (int a = e.actions().size() - 1; a >= 0; a--) right = actionButton(g, mx, my, t, "r." + e.label() + "." + a, right, cy, e.actions().get(a));
                float rowW = right - x;
                boolean hover = e.select() != null && hovering(mx, my, x, ry, rowW, rh - 2);
                float hv = UiAnim.smooth(rowKey + ".h", hover ? 1 : 0, 18), act = UiAnim.smooth(rowKey + ".act", e.active() ? 1 : 0, 16);
                int idle = hv > 0 ? UiDraw.alpha(t.surface(), hv * .8f) : i % 2 == 0 ? UiDraw.alpha(t.surface(), .3f) : UiDraw.alpha(t.surface(), 0f);
                UiDraw.rounded(g, x, ry + 1, rowW, rh - 2, 6, UiDraw.mix(idle, UiDraw.alpha(t.accent(), .14f + hv * .06f), act));
                if (act > .01f) UiDraw.rounded(g, x + 2, ry + 6 + (rh - 12) * (1 - act) / 2, 2, (rh - 12) * act, 1, t.accent());
                float tx = x + 10;
                if (e.icon() != null) { e.icon().draw(g, x + 6, ry + (rh - 16) / 2); tx = x + 30; }
                int colour = UiDraw.mix(t.text(), t.accent(), act);
                String tag = e.active() ? "In use" : hover && e.select() != null ? "Click to use" : "";
                float tagW = tag.isEmpty() ? 0 : UiDraw.width(tag, UiDraw.SMALL) + 10;
                if (e.detail().isEmpty()) UiDraw.text(g, UiDraw.fit(e.label(), x + rowW - tx - tagW - 6), tx, cy, colour);
                else {
                    UiDraw.text(g, UiDraw.fit(e.label(), x + rowW - tx - tagW - 6), tx, cy - 5, colour);
                    UiDraw.text(g, UiDraw.fit(e.detail(), x + rowW - tx - 6, UiDraw.SMALL), tx, cy + 6, UiDraw.SMALL, t.muted());
                }
                float tagRight = x + rowW - 8, tagY = e.detail().isEmpty() ? cy : cy - 5;
                UiMotion.text(g, rowKey + ".tag", tag, l -> UiDraw.right(g, l, tagRight, tagY, UiDraw.SMALL, l.equals("In use") ? t.accent() : t.muted()));
                UiRender.opacity = rowBefore;
                if (e.select() != null) hit(x, ry, rowW, rh - 2, () -> attempt(e.select(), e.selectDone() == null ? "" : e.selectDone()));
            }
        }
    }
}
