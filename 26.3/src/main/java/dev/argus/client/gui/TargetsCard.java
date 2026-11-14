package dev.argus.client.gui;

import dev.argus.client.utils.InputButtons;
import dev.argus.client.utils.InputKeys;
import dev.argus.client.modules.Module;
import dev.argus.client.modules.render.targets.DefaultTargets;
import dev.argus.client.modules.render.targets.RuleSettings;
import dev.argus.client.modules.render.targets.SpecificRules;
import dev.argus.client.modules.render.targets.SpecificTargets;
import dev.argus.client.modules.render.targets.TargetLinks;
import dev.argus.client.render.ShulkerIconRenderer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.item.ItemStack;

/**
 * ESP's and Tracers' targets, all in one card:
 * <pre>
 *  Presets  ( Storage ESP )            ← adds several ready-made groups at once
 *  [ search field ..................... ] [ All ▸ ] [ Add (2) ]
 *  ┌ matches: tick as many as you like; Enter or Add adds them as one group ─┐
 *  └───────────────────────────────────────────────────────────────────────┘
 *  [Blocks] ▣ Diamond Ore, Ancient Debris         [Tracers] (link) (x)
 *     └ right-click: that group's settings drop down here
 * </pre>
 * Left-clicking a row turns the group on or off; right-clicking opens its settings. Each thing can be added once.
 */
final class TargetsCard extends InlineCard {
    private static final float PRESETS = 24, TOP = 26, MATCH = 22, ROW = 30, TYPE_W = 54, CIRCLE = 16;
    private static final SpecificRules.Category[] FILTERS = {null, SpecificRules.Category.BLOCKS, SpecificRules.Category.PLAYERS,
            SpecificRules.Category.MOBS, SpecificRules.Category.ENTITIES, SpecificRules.Category.EXTRAS};
    private static final String[] FILTER_NAMES = {"All", "Blocks", "Players", "Mobs", "Entities", "Extras"};

    private final TargetLinks links;
    private final SpecificTargets targets;
    private final boolean fromEsp;
    private final String other;
    private final Module module;
    private final UiInput field;
    private int filter;
    /** Ticked matches, in the order they were ticked. */
    private final Map<String, SpecificTargets.Candidate> selected = new LinkedHashMap<>();
    private String lastQuery;
    private int lastFilter = -1;
    private List<SpecificTargets.Candidate> matches = List.of();
    /** Open settings dropdowns by group id, with the settings object each list was made for. */
    private final Map<String, SettingsList> open = new HashMap<>();
    private final Map<String, RuleSettings> openFor = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private String armed = "";
    private long armedAt;
    private float cardX, cardW;

    private record Row(SpecificRules.Rule rule, float y) {}

    TargetsCard(UiScreen host, Module module, TargetLinks links, boolean fromEsp) {
        super(host);
        this.module = module;
        this.links = links;
        this.fromEsp = fromEsp;
        this.targets = links.specific(fromEsp);
        this.other = links.otherName(fromEsp);
        this.field = new UiInput(0, 0, 100, 16, "Search blocks, players, mobs, items...");
        this.field.setMaxLength(64);
    }

    @Override String title() { return "Targets"; }
    @Override String icon() { return "search"; }
    @Override String subtitle() {
        List<SpecificRules.Rule> all = targets.rules().all();
        if (all.isEmpty()) return "Search, tick what to show, then Add · each group gets its own settings";
        long linked = all.stream().filter(rule -> rule.mirror).count();
        return all.size() + (all.size() == 1 ? " group" : " groups") + (linked > 0 ? " · " + linked + " linked with " + other : "")
                + " · left-click on/off · right-click settings";
    }

    // ---- sizes -------------------------------------------------------------------------------------------------

    private SpecificRules.Category category() { return FILTERS[filter]; }

    private List<SpecificTargets.Candidate> matches() {
        String query = field.getValue();
        if (!query.equals(lastQuery) || filter != lastFilter) {
            lastQuery = query; lastFilter = filter;
            matches = query.isBlank() ? List.of() : search(query);
        }
        return matches;
    }

    /**
     * What a search shows. A generic term ("storage", "ores", "weapons"...) lists every thing it covers as its own
     * row, e.g. each chest, barrel and shulker box; ordinary name matches follow.
     */
    private List<SpecificTargets.Candidate> search(String query) {
        Map<String, SpecificTargets.Candidate> found = new LinkedHashMap<>();
        for (SpecificTargets.TagMatch tag : SpecificTargets.searchTags(query, category())) {
            for (SpecificTargets.Candidate candidate : tag.members()) found.putIfAbsent(candidate.key(), candidate);
        }
        for (SpecificTargets.Candidate candidate : SpecificTargets.search(query, category(), 8)) found.putIfAbsent(candidate.key(), candidate);
        return new ArrayList<>(found.values());
    }

    /** The matches while searching; with the search cleared, what's ticked so far (the first ten). */
    private List<SpecificTargets.Candidate> visibleMatches() {
        if (!field.getValue().isBlank()) return matches();
        return selected.values().stream().limit(10).toList();
    }

    private boolean panelShown() { return !field.getValue().isBlank() || !selected.isEmpty(); }
    private float panelHeight() {
        if (!panelShown()) return 0;
        return 16 + Math.max(1, visibleMatches().size()) * MATCH + 8;
    }

    private SettingsList listFor(SpecificRules.Rule rule) {
        if (!open.containsKey(rule.id)) return null;
        RuleSettings settings = targets.settings(rule);
        if (openFor.get(rule.id) != settings) {
            open.put(rule.id, new SettingsList(host, module, null, settings.settings()));
            openFor.put(rule.id, settings);
        }
        return open.get(rule.id);
    }

    private float dropdownHeight(SpecificRules.Rule rule) {
        SettingsList list = listFor(rule);
        return list == null ? 0 : list.contentHeight() + 10;
    }

    /** Each rule's dropdown height as last drawn, eased toward {@link #dropdownHeight}, so it opens and folds smoothly. */
    private final Map<String, Float> shownDrop = new HashMap<>();
    private float shownDropdownHeight(SpecificRules.Rule rule) { return shownDrop.getOrDefault(rule.id, dropdownHeight(rule)); }

    @Override float bodyHeight() {
        List<SpecificRules.Rule> all = targets.rules().all();
        open.keySet().removeIf(id -> targets.rules().find(id) == null);
        float height = PRESETS + TOP + panelHeight() + 4;
        if (all.isEmpty()) return height + ROW;
        for (SpecificRules.Rule rule : all) height += ROW + shownDropdownHeight(rule);
        return height + 4;
    }

    // ---- widgets -------------------------------------------------------------------------------------------------

    /** Settings lists of open dropdowns that have been drawn at least once. */
    private List<SettingsList> lists() {
        List<SettingsList> lists = new ArrayList<>();
        for (SettingsList list : open.values()) if (list != null) lists.add(list);
        return lists;
    }

    @Override void reattach() { host.hostAdd(field); for (SettingsList list : lists()) list.reattach(); }
    @Override void stopEditing() { field.setFocused(false); for (SettingsList list : lists()) list.stopEditing(); }
    @Override void hide() { field.visible = false; field.setFocused(false); }

    @Override boolean widgetClicked(MouseButtonEvent event, boolean doubled) {
        if (field.visible && field.isMouseOver(event.x(), event.y())) { host.hostFocus(field); return field.mouseClicked(event, doubled); }
        field.setFocused(false);
        for (SettingsList list : lists()) if (list.mouseClicked(event, doubled)) return true;
        return false;
    }

    @Override boolean otherClicked(MouseButtonEvent event) {
        if (InputButtons.glfw(event) != 1) return false;
        // Right-click: the filter steps back; a row opens or closes its settings.
        if (UiDraw.over(event.x(), event.y(), filterX, cardY + PRESETS + 4, filterW, 18)) { filter = Math.floorMod(filter - 1, FILTERS.length); return true; }
        for (Row row : rows) {
            if (UiDraw.over(event.x(), event.y(), cardX, row.y(), rowRight, ROW)) { toggleOpen(row.rule()); return true; }
        }
        return false;
    }

    @Override boolean dragged(MouseButtonEvent event) { for (SettingsList list : lists()) if (list.mouseDragged(event)) return true; return false; }
    @Override boolean released() { for (SettingsList list : lists()) if (list.mouseReleased()) return true; return false; }
    @Override void popup(GuiGraphicsExtractor g, UiTheme t, int width, int height) { for (SettingsList list : lists()) list.popup(g, t, width, height); }

    @Override boolean keyPressed(KeyEvent event) {
        if (field.isFocused() && field.visible && (InputKeys.glfw(event) == 257 || InputKeys.glfw(event) == 335)) { addSelected(); return true; }
        for (SettingsList list : lists()) if (list.keyPressed(event)) return true;
        return false;
    }

    private void toggleOpen(SpecificRules.Rule rule) {
        if (open.containsKey(rule.id)) { SettingsList list = open.remove(rule.id); if (list != null) list.stopEditing(); openFor.remove(rule.id); return; }
        open.put(rule.id, null);
        openFor.remove(rule.id);
    }

    // ---- actions -------------------------------------------------------------------------------------------------

    private void tick(SpecificTargets.Candidate candidate) {
        if (selected.remove(candidate.key()) == null) selected.put(candidate.key(), candidate);
    }

    /** Adds every ticked thing as one group (or the top match when nothing is ticked). */
    private void addSelected() {
        List<SpecificTargets.Candidate> picks = new ArrayList<>(selected.values());
        if (picks.isEmpty()) {
            for (SpecificTargets.Candidate candidate : matches()) {
                if (targets.rules().owner(candidate.type(), candidate.id()) == null) { picks.add(candidate); break; }
            }
        }
        if (picks.isEmpty()) {
            fail(field.getValue().isBlank() ? "Search for something, tick it, then press Add." : "Nothing new to add: those are already in the list.");
            return;
        }
        List<SpecificRules.Member> members = new ArrayList<>();
        for (SpecificTargets.Candidate candidate : picks) members.add(candidate.member());
        SpecificRules.Rule rule = targets.rules().add(members);
        if (rule == null) { fail("Those are already in the list."); return; }
        targets.changed();
        links.syncRules();
        selected.clear();
        field.setValue("");
        lastQuery = null;
        open.put(rule.id, null);
        int count = rule.members().size();
        say("Added " + (count == 1 ? targets.label(rule, 1) : count + " things") + " as a " + rule.category().title() + " group. Its settings are open below.");
    }

    /** Adds every group of a preset, each with its own settings, leaving out things already in the list. */
    private void addPreset(DefaultTargets.Preset preset) {
        DefaultTargets.Applied applied = DefaultTargets.apply(targets.rules(), preset);
        if (applied.groups() == 0) { fail("Everything in " + preset.name() + " is already in the list."); return; }
        targets.changed();
        links.syncRules();
        selected.clear();
        field.setValue("");
        lastQuery = null;
        say("Added " + preset.name() + ": " + applied.groups() + (applied.groups() == 1 ? " group" : " groups") + ", " + applied.things() + " blocks"
                + (applied.skipped() > 0 ? " (" + applied.skipped() + " already in the list were left out)" : "") + ". Right-click a group for its settings.");
    }

    private void toggle(SpecificRules.Rule rule) {
        SpecificRules.Rule current = targets.rules().find(rule.id);
        if (current == null) return;
        current.enabled = !current.enabled;
        targets.changed();
        links.syncRules();
    }

    // ---- drawing -------------------------------------------------------------------------------------------------

    private float filterX, filterW, cardY, rowRight;

    @Override void draw(GuiGraphicsExtractor g, int mx, int my, UiTheme t, float x, float y, float w) {
        cardX = x; cardW = w; cardY = y;
        rows.clear();
        // Presets row: one button per preset, each adding all its groups at once.
        float px = x + 4, pcy = y + PRESETS / 2 - 2;
        UiDraw.text(g, "Presets", px, pcy, UiDraw.SMALL, t.muted());
        px += UiDraw.width("Presets", UiDraw.SMALL) + 8;
        for (DefaultTargets.Preset preset : DefaultTargets.presets()) {
            boolean done = DefaultTargets.missing(targets.rules(), preset) == 0;
            String label = done ? preset.name() + " · added" : preset.name();
            float bw = buttonWidth(label);
            button(g, mx, my, t, "preset." + preset.name(), px, pcy - 8, bw, 16, label, false,
                    done ? "Everything in this preset is already in the list."
                         : preset.description() + ". Adds " + preset.size() + " groups at once; things already in the list are left out.",
                    () -> addPreset(preset));
            px += bw + 6;
        }
        y += PRESETS;
        // Search row: field, filter, add.
        float cy = y + 12;
        String addLabel = selected.isEmpty() ? "Add" : "Add (" + selected.size() + ")";
        float addW = UiDraw.width(addLabel, UiDraw.SMALL) + 22;
        button(g, mx, my, t, "add", x + w - addW, cy - 9, addW, 18, addLabel, true,
                "Add everything ticked as one group. With nothing ticked, adds the top match.", this::addSelected);
        filterW = 0;
        for (String name : FILTER_NAMES) filterW = Math.max(filterW, UiDraw.width(name, UiDraw.SMALL) + 26);
        filterX = x + w - addW - 6 - filterW;
        button(g, mx, my, t, "filter", filterX, cy - 9, filterW, 18, FILTER_NAMES[filter] + " ›", filter != 0,
                "Search only " + (filter == 0 ? "everything" : FILTER_NAMES[filter].toLowerCase()) + ". Click for the next, right-click for the previous.",
                () -> filter = (filter + 1) % FILTERS.length);
        field.place(Math.round(x), Math.round(cy - 8), Math.round(filterX - 6 - x));
        field.visible = inView(cy - 8, 16);
        if (field.visible) field.extractRenderState(g, mx, my, 0); else field.setFocused(false);
        float top = y + TOP;

        if (panelShown()) top = drawMatches(g, mx, my, t, x, top, w) + 4;
        else top += 4;

        List<SpecificRules.Rule> all = targets.rules().all();
        if (all.isEmpty()) {
            UiDraw.text(g, "Nothing added yet. Search above, tick what you want, then press Add.", x + 4, top + ROW / 2, UiDraw.SMALL, t.muted());
            return;
        }
        // Rows fade in when added and glide when one above is removed; dropdowns ease open and shut.
        String rowsKey = "tc." + module.getName() + ".rule";
        float base = top;
        shownDrop.keySet().retainAll(all.stream().map(r -> r.id).toList());
        for (SpecificRules.Rule rule : all) {
            float shown = base + UiMotion.rowY(rowsKey, rule.id, top - base), shift = shown - top;
            float before = UiRender.opacity;
            UiRender.opacity = before * UiMotion.rowAppear(rowsKey, rule.id);
            try {
                float below = drawRow(g, mx, my, t, x, shown, w, rule);
                SettingsList list = listFor(rule);
                float target = dropdownHeight(rule);
                float h = UiAnim.approach(shownDrop.getOrDefault(rule.id, 0f), target, 16);
                shownDrop.put(rule.id, h);
                if (h > 4.5f) {
                    float open = target > 0 ? Math.min(1, h / target) : 0;
                    UiDraw.rounded(g, x + 10, below, w - 10, h - 4, 6, UiDraw.alpha(t.crust(), .7f * Math.max(open, .4f)));
                    UiDraw.rect(g, x + 4, below - 2, 1.2f, h - 4, UiDraw.alpha(t.accent(), .5f));
                    if (list != null) {
                        float listBefore = UiRender.opacity;
                        UiRender.opacity = listBefore * open;
                        list.panelEdges(x - 12, x + w + 12);
                        list.embed(x + 14, below + 3, w - 18, Math.max(clipTop(), below), Math.min(clipBottom(), below + h - 4));
                        list.render(g, mx, my, 0, t);
                        UiRender.opacity = listBefore;
                    }
                }
                top = below - shift + h;
            } finally { UiRender.opacity = before; }
        }
    }

    private float drawMatches(GuiGraphicsExtractor g, int mx, int my, UiTheme t, float x, float y, float w) {
        List<SpecificTargets.Candidate> list = visibleMatches();
        float h = panelHeight() - 4;
        UiDraw.rounded(g, x, y, w, h, 6, t.crust());
        UiDraw.outline(g, x, y, w, h, 6, .6f, UiDraw.alpha(t.accent(), .35f));
        String head = selected.isEmpty() ? "Click to tick · tick several to add them as one group"
                : selected.size() + " ticked" + (field.getValue().isBlank() && selected.size() > 10 ? " (first 10 shown)" : "")
                + " · Enter or Add puts them in one group";
        UiDraw.text(g, UiDraw.fit(head, w - 60, UiDraw.SMALL), x + 8, y + 9, UiDraw.SMALL, t.muted());
        if (!selected.isEmpty()) {
            float cw = UiDraw.width("Clear", UiDraw.SMALL) + 14;
            button(g, mx, my, t, "clear", x + w - cw - 4, y + 2, cw, 14, "Clear", false, "Untick everything", selected::clear);
        }
        float top = y + 16;
        if (list.isEmpty()) {
            UiDraw.text(g, UiDraw.fit("No matches. Online players show by name; type player:Name for anyone else.", w - 16, UiDraw.SMALL),
                    x + 8, top + MATCH / 2, UiDraw.SMALL, t.muted());
        }
        for (int i = 0; i < list.size(); i++) {
            SpecificTargets.Candidate candidate = list.get(i);
            float ry = top + i * MATCH, cy = ry + MATCH / 2;
            boolean added = targets.rules().owner(candidate.type(), candidate.id()) != null;
            boolean ticked = selected.containsKey(candidate.key());
            boolean hover = !added && hovering(mx, my, x + 2, ry, w - 4, MATCH);
            float hv = UiAnim.smooth("tc." + module.getName() + ".m" + i, hover ? 1 : 0, 18);
            if (ticked || hv > 0) UiDraw.rounded(g, x + 2, ry, w - 4, MATCH, 5, UiDraw.alpha(t.accent(), (ticked ? .12f : 0) + hv * .1f));
            // Tick box.
            float bx = x + 7, by = cy - 5;
            UiDraw.rounded(g, bx, by, 10, 10, 3, ticked ? t.accent() : UiDraw.alpha(t.surface(), added ? .4f : 1));
            UiDraw.outline(g, bx, by, 10, 10, 3, .6f, UiDraw.alpha(t.overlay(), .8f));
            if (ticked) UiDraw.icon(g, "check", bx + 1, by + 1, 8, t.crust());
            float tx = x + 22;
            if (candidate.icon() != null) {
                ItemStack icon = candidate.icon();
                ShulkerIconRenderer.drawAt(g, icon, null, tx, ry + (MATCH - 16) / 2, 1);
            }
            tx += 20;
            String detail = added ? candidate.kind() + " · already added" : candidate.kind();
            float dw = UiDraw.width(detail, UiDraw.SMALL);
            int colour = added ? t.muted() : ticked || hover ? t.accent() : t.text();
            UiDraw.text(g, UiDraw.fit(candidate.name(), x + w - tx - dw - 16), tx, cy, colour);
            UiDraw.right(g, detail, x + w - 8, cy, UiDraw.SMALL, t.muted());
            if (!added) hit(x + 2, ry, w - 4, MATCH, () -> tick(candidate));
        }
        return y + h;
    }

    private boolean isArmed(String key) { return key.equals(armed) && System.currentTimeMillis() - armedAt < 4000; }

    private float drawRow(GuiGraphicsExtractor g, int mx, int my, UiTheme t, float x, float y, float w, SpecificRules.Rule rule) {
        RuleSettings settings = targets.settings(rule);
        boolean on = rule.enabled;
        boolean linked = rule.mirror;
        boolean inOther = links.onInOther(fromEsp, rule);
        boolean dropped = open.containsKey(rule.id);
        float cy = y + ROW / 2;

        // Right side, right to left: remove (circle), link (circle), other module (pill).
        float right = x + w - 2;
        String removeKey = "rm." + rule.id;
        boolean removeArmed = isArmed(removeKey);
        right = circle(g, mx, my, t, "x." + rule.id, right, cy, "close", removeArmed, removeArmed ? t.red() : 0,
                removeArmed ? "Click again to remove" : linked ? "Remove from both pages" : "Remove", () -> {
                    if (!isArmed(removeKey)) { armed = removeKey; armedAt = System.currentTimeMillis(); say("Click the X again to remove it."); return; }
                    armed = "";
                    links.remove(fromEsp, rule);
                    open.remove(rule.id);
                    say("Removed " + targets.label(rule, 2) + ".");
                });
        right = circle(g, mx, my, t, "ln." + rule.id, right, cy, "link", linked, 0,
                linked ? "Linked with " + other + ": changes on either page apply to both. Click to unlink."
                       : "Link with " + other + ": copy this group there and keep both the same",
                () -> { if (rule.mirror) links.breakLink(rule); else links.mirror(fromEsp, rule); });
        float pw = UiDraw.width(other, UiDraw.SMALL) + 18;
        right -= pw;
        button(g, mx, my, t, "ot." + rule.id, right, cy - 8, pw, 16, other, inOther,
                inOther ? "Turn this group off in " + other : "Turn this group on in " + other + (linked ? "" : ", starting from these settings"),
                () -> links.toggleInOther(fromEsp, rule));
        right -= 6;
        rowRight = right - x;

        // Row body: left click toggles, right click opens settings.
        boolean hover = hovering(mx, my, x, y + 1, right - x, ROW - 2);
        String rowKey = "tc." + module.getName() + ".r." + rule.id;
        float hv = UiAnim.smooth(rowKey, hover ? 1 : 0, 18), drop = UiAnim.smooth(rowKey + ".drop", dropped ? 1 : 0, 16), lit = UiAnim.smooth(rowKey + ".on", on ? 1 : 0, 16);
        int base = UiDraw.mix(UiDraw.alpha(t.surface(), .35f), UiDraw.alpha(t.accent(), .1f), drop);
        UiDraw.rounded(g, x, y + 1, right - x, ROW - 2, 6, UiDraw.mix(base, UiDraw.alpha(t.surface(), .9f), hv * .6f));

        // Type tag at the far left, in the group's colour while on.
        int swatch = settings.swatch() | 0xFF000000;
        UiDraw.rounded(g, x + 4, cy - 8, TYPE_W, 16, 8, UiDraw.mix(UiDraw.alpha(t.overlay(), .35f), UiDraw.alpha(swatch, .28f), lit));
        UiDraw.outline(g, x + 4, cy - 8, TYPE_W, 16, 8, .6f, UiDraw.mix(UiDraw.alpha(t.overlay(), .6f), UiDraw.alpha(swatch, .8f), lit));
        UiDraw.center(g, rule.category().title(), x + 4, cy, TYPE_W, UiDraw.SMALL, UiDraw.mix(t.muted(), t.text(), lit));

        float tx = x + TYPE_W + 10;
        SpecificRules.Member first = rule.members().get(0);
        ItemStack icon = targets.describe(first).icon();
        if (icon != null) { ShulkerIconRenderer.drawAt(g, icon, null, tx, cy - 8, 1); tx += 20; }
        String label = targets.label(rule, 3);
        // Tracers can't draw light levels: say so rather than look broken.
        boolean espOnly = !fromEsp && rule.members().stream().allMatch(member -> member.type() == SpecificRules.Type.EXTRA && member.id().equals(SpecificRules.LIGHT_LEVELS));
        // When there are more matching blocks than the scanner keeps (netherrack, stone), only the nearest are shown.
        boolean capped = on && dev.argus.client.modules.render.targets.BlockTargetIndex.get().capped()
                && rule.members().stream().anyMatch(member -> member.type() == SpecificRules.Type.BLOCK);
        String state = (espOnly ? "Shown by ESP only · " : (on ? "On" : "Off") + " · " + settings.rangeLabel() + " · ")
                + (capped ? "too many to show all, nearest shown · " : "")
                + (linked ? "linked with " + other : inOther ? "also on in " + other : "this page only");
        String hint = hover ? (on ? "Click: turn off · Right-click: settings" : "Click: turn on · Right-click: settings") : state;
        UiDraw.text(g, UiDraw.fit(label, right - tx - 18), tx, cy - 5, UiDraw.mix(t.muted(), t.text(), lit));
        float hintX = tx, hintRoom = right - tx - 18;
        UiMotion.text(g, rowKey + ".hint", UiDraw.fit(hint, hintRoom, UiDraw.SMALL), l -> UiDraw.text(g, l, hintX, cy + 6, UiDraw.SMALL, t.muted()));
        // The chevron turns with the dropdown instead of swapping.
        if (drop < .99f) UiDraw.icon(g, "chevron_right", right - 14 + drop * 1.5f, cy - 4, 8, UiDraw.alpha(t.muted(), .8f * (1 - drop)));
        if (drop > .01f) UiDraw.icon(g, "chevron_down", right - 14, cy - 4 - (1 - drop) * 1.5f, 8, UiDraw.alpha(t.muted(), .8f * drop));
        hit(x, y + 1, right - x, ROW - 2, () -> toggle(rule));
        rows.add(new Row(rule, y));
        return y + ROW;
    }

    /** A round icon button drawn right to left; returns the new right edge. */
    private float circle(GuiGraphicsExtractor g, int mx, int my, UiTheme t, String key, float right, float cy, String icon,
                         boolean lit, int tint, String tip, Runnable run) {
        float r = CIRCLE / 2, cx = right - r;
        boolean hover = hovering(mx, my, cx - r, cy - r, CIRCLE, CIRCLE);
        String base = "tc." + module.getName() + "." + key;
        float hv = UiAnim.smooth(base, hover ? 1 : 0, 18), on = UiAnim.smooth(base + ".lit", lit ? 1 : 0, 16), warn = UiAnim.smooth(base + ".tint", tint != 0 ? 1 : 0, 16);
        int colour = tint != 0 ? UiDraw.mix(t.accent(), tint, warn) : UiDraw.mix(t.accent(), t.red(), warn);
        // An armed (tinted) circle pulses its ring while it waits for the second click.
        float pulse = tint != 0 ? .3f * UiAnim.pulse(1.5e8) : 0;
        UiDraw.circle(g, cx, cy, r, UiDraw.mix(UiDraw.mix(t.surface(), t.overlay(), hv * .7f), UiDraw.alpha(colour, .22f + hv * .1f), on));
        UiDraw.outline(g, cx - r, cy - r, CIRCLE, CIRCLE, r, .6f, UiDraw.mix(UiDraw.alpha(t.overlay(), .6f + hv * .3f), UiDraw.alpha(colour, .6f + pulse), on));
        UiDraw.icon(g, icon, cx - 4, cy - 4, 8, UiDraw.mix(UiDraw.mix(t.muted(), t.text(), hv), colour, on));
        hit(cx - r, cy - r, CIRCLE, CIRCLE, run);
        if (hover) host.tooltip("tc." + key, icon.equals("link") ? "Link" : "Remove", tip);
        return right - CIRCLE - 4;
    }
}
