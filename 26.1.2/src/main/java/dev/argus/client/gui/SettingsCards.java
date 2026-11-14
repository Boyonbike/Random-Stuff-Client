package dev.argus.client.gui;

import dev.argus.client.ClientContext;
import dev.argus.client.modules.misc.BetterChatModule;
import dev.argus.client.modules.misc.ChatToolsModule;

import dev.argus.client.modules.Module;
import dev.argus.client.settings.Setting;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * A module's page as a stack of foldable cards: a General card for settings before the first group, one card per
 * settings group (membership follows the module's setting order), and the module's own tools (see {@link InlineCard}),
 * each placed where it belongs next to the settings it works with.
 */
final class SettingsCards {
    private static final float GAP = 8, TABS = 22;
    /** The tab picked on each module's page, kept while the game runs. */
    private static final java.util.Map<String, String> PICKED_TAB = new java.util.HashMap<>();

    private static final class Section {
        final Setting<?> group;
        final SettingsList list;
        final List<Setting<?>> members;
        final int size;
        /** Title and icon for a card that isn't one of the module's own groups, or null. */
        final String title, icon;
        /** The tab this card sits under (see {@link Setting#groupSection}), or null on a page without tabs. */
        String tab;
        float body;
        float top;
        /** Fold state for a card that isn't a settings group (a group folds through its own setting). */
        boolean folded;
        Section(Setting<?> group, SettingsList list, List<Setting<?>> members, String title, String icon) {
            this.group = group; this.list = list; this.members = members; this.size = members.size(); this.title = title; this.icon = icon;
        }
        boolean open() { return group != null ? group.isExpanded() : !folded; }
        void toggle() { if (group != null) group.expanded = !group.isExpanded(); else folded = !folded; }
        String title() { return title != null ? title : group == null ? "General" : GuiState.title(group.getName()); }
        /** A group's card follows its header's visibility live, so cards for another mode come and go as it changes. */
        boolean shown() { return group == null || group.isVisible(); }
    }

    private final UiScreen host;
    private final Module module;
    private final List<Section> sections = new ArrayList<>();
    private final List<InlineCard> inline = new ArrayList<>();
    private final java.util.Map<InlineCard, Placement> anchors = new java.util.IdentityHashMap<>();
    private final String focusSetting;
    /** Top of each card this frame, for clicks on the header. */
    private final java.util.Map<Object, Float> tops = new java.util.IdentityHashMap<>();
    /** Top of the tab strip this frame, or NaN when the page has none. */
    private float tabsTop = Float.NaN;
    private float x, w;

    SettingsCards(UiScreen host, Module module, String focusSetting) {
        this.host = host;
        this.module = module;
        this.focusSetting = focusSetting;
        GuiSettings.expandGroups(module.getSettings());
        Setting<?> group = null;
        List<Setting<?>> members = new ArrayList<>();
        for (Setting<?> setting : module.getSettings()) {
            if (setting.isGroupOnly()) { add(host, group, members); group = setting; members = new ArrayList<>(); }
            else members.add(setting);
        }
        add(host, group, members);
        addBetterChat(host);
        assignTabs();
        // A folded card starts folded rather than shrinking into place, so jumping to a setting lands where it should.
        for (Section section : sections) section.body = section.open() ? section.list.contentHeight() + 6 : 0;
    }

    /**
     * Adds a card of the module's own tools.
     */
    void addInline(InlineCard card, Placement placement) { inline.add(card); anchors.put(card, placement); }

    /** Every card in display order. */
    private List<Object> order() {
        List<Object> order = new ArrayList<>();
        String tab = currentTab();
        for (InlineCard card : inline) if (onPage(card, tab) && anchors.get(card).after() == null && !anchors.get(card).atEnd()) order.add(card);
        for (Section s : sections) {
            if (!s.shown() || !onTab(s.tab, tab)) { s.body = 0; continue; }
            order.add(s);
            if (s.group != null) for (InlineCard card : inline) if (onPage(card, tab) && s.group.getName().equals(anchors.get(card).after())) order.add(card);
        }
        // A card placed after a group that is hidden right now stays hidden with it.
        for (InlineCard card : inline) if (onPage(card, tab) && !order.contains(card) && (anchors.get(card).after() == null || !anchorHidden(card))) order.add(card);
        return order;
    }

    // ---- tabs --------------------------------------------------------------------------------

    /**
     * Gives every card its tab: a group naming one starts it, the groups after it follow it, and cards before the
     * first named tab (the General card) sit under the first tab.
     */
    private void assignTabs() {
        String current = null, first = null;
        for (Section s : sections) {
            if (s.group != null && s.group.groupSection != null) current = s.group.groupSection;
            s.tab = current;
            if (first == null) first = current;
        }
        for (Section s : sections) if (s.tab == null) s.tab = first;
        if (focusSetting != null) for (Section s : sections)
            if (s.tab != null && s.members.stream().anyMatch(m -> m.getName().equals(focusSetting))) PICKED_TAB.put(module.getName(), s.tab);
    }

    /** The tabs shown right now, in page order: empty unless at least two visible cards name different tabs. */
    private List<String> tabs() {
        List<String> tabs = new ArrayList<>();
        for (Section s : sections) if (s.tab != null && s.shown() && !tabs.contains(s.tab)) tabs.add(s.tab);
        return tabs.size() >= 2 ? tabs : List.of();
    }

    private String currentTab() {
        List<String> tabs = tabs();
        if (tabs.isEmpty()) return null;
        String picked = PICKED_TAB.get(module.getName());
        return tabs.contains(picked) ? picked : tabs.get(0);
    }

    private static boolean onTab(String cardTab, String current) { return current == null || cardTab == null || cardTab.equals(current); }

    /** A tool card's tab: the tab of the group it sits under, or the first or last tab for one at either end. */
    private String tabOf(InlineCard card) {
        Placement placement = anchors.get(card);
        if (sections.isEmpty()) return null;
        if (placement.after() != null) {
            for (Section s : sections) if (s.group != null && s.group.getName().equals(placement.after())) return s.tab;
            return null;
        }
        return placement.atEnd() ? sections.get(sections.size() - 1).tab : sections.get(0).tab;
    }

    private boolean onPage(InlineCard card, String tab) { return card.visible() && onTab(tabOf(card), tab); }
    private boolean onPage(InlineCard card) { return onPage(card, currentTab()); }

    private float tabsHeight() { return tabs().isEmpty() ? 0 : TABS + GAP; }

    private void drawTabs(GuiGraphicsExtractor g, int mx, int my, UiTheme t, List<String> tabs, String current, float x, float y, float w, float clipTop, float clipBottom) {
        float segment = w / tabs.size();
        UiDraw.rounded(g, x, y, w, TABS, TABS / 2, t.crust());
        float thumb = UiAnim.smooth("tabs." + module.getName(), tabs.indexOf(current), 16);
        UiDraw.rounded(g, x + 2 + thumb * segment, y + 2, segment - 4, TABS - 4, (TABS - 4) / 2, UiDraw.alpha(t.accent(), .22f));
        for (int i = 0; i < tabs.size(); i++) {
            boolean hover = UiDraw.over(mx, my, x + i * segment, y, segment, TABS) && my >= clipTop && my < clipBottom;
            float hv = UiAnim.smooth("tabs." + module.getName() + "." + i, hover ? 1 : 0, 18);
            int colour = tabs.get(i).equals(current) ? t.accent() : UiDraw.mix(t.muted(), t.text(), hv);
            UiDraw.center(g, UiDraw.fit(tabs.get(i), segment - 10), x + i * segment, y + TABS / 2, segment, colour);
        }
    }

    /** Picks the tab under the click, if the click is on the tab strip. */
    private boolean tabClicked(double mx, double my) {
        List<String> tabs = tabs();
        if (tabs.isEmpty() || Float.isNaN(tabsTop) || !UiDraw.over(mx, my, x, tabsTop, w, TABS)) return false;
        int index = Math.clamp((int) ((mx - x) / (w / tabs.size())), 0, tabs.size() - 1);
        stopEditing();
        PICKED_TAB.put(module.getName(), tabs.get(index));
        return true;
    }

    private boolean anchorHidden(InlineCard card) {
        for (Section s : sections) if (s.group != null && s.group.getName().equals(anchors.get(card).after())) return !s.shown();
        return false;
    }

    private float bodyOf(Object item) { return item instanceof Section s ? s.body : ((InlineCard) item).shown(); }

    private void add(UiScreen host, Setting<?> group, List<Setting<?>> members) { add(host, module, group, members, null, null); }

    private void add(UiScreen host, Module owner, Setting<?> group, List<Setting<?>> members, String title, String icon) {
        // A group whose header is hidden stays out of view (e.g. ESP's old target cards, kept for config only, or a
        // mode's cards while another mode is picked); see Section.shown.
        if (members.isEmpty()) return;
        boolean focused = focusSetting != null && members.stream().anyMatch(s -> s.getName().equals(focusSetting));
        List<Setting<?>> copy = List.copyOf(members);
        sections.add(new Section(group, new SettingsList(host, owner, focused ? focusSetting : null, copy), copy, title, icon));
        // A card that starts folded still opens when search jumps to one of its settings (after the list, which
        // resets every group's fold when it has a setting to focus).
        if (focused && group != null) group.expanded = true;
    }

    /**
     * BetterChat is hidden and runs inside Chat Tools, so its settings follow Chat Tools' own as extra cards. Each card's
     * list still belongs to BetterChat, so its descriptions and saving work as before.
     */
    private void addBetterChat(UiScreen host) {
        if (!(module instanceof ChatToolsModule)) return;
        Module betterChat = ClientContext.getModule(BetterChatModule.class);
        if (betterChat == null) return;
        List<Setting<?>> settings = betterChat.getSettings();
        if (focusSetting != null) GuiSettings.expandGroups(settings);
        for (var span : ChatSettingsSections.split(settings.stream().map(Setting::getName).toList()))
            add(host, betterChat, null, settings.subList(span.from(), span.to()), span.title(), span.icon());
    }

    private String icon(int index) {
        Section s = sections.get(index);
        if (s.icon != null) return s.icon;
        if (s.group == null) return "sliders";
        return s.group.groupIcon != null ? s.group.groupIcon : "misc";
    }

    private static boolean changed(Setting<?> setting) { return !Objects.equals(setting.getValue(), setting.getDefaultValue()); }

    /** Settings in a card that differ from their defaults. */
    int changed(int index) { return (int) sections.get(index).members.stream().filter(SettingsCards::changed).count(); }
    int changed() { int total = 0; for (int i = 0; i < sections.size(); i++) if (sections.get(i).shown()) total += changed(i); return total; }

    /** Restores every setting on the page to its default and flashes the rows that changed. */
    int resetAll() {
        stopEditing();
        int count = 0;
        for (Section s : sections) if (s.shown()) for (Setting<?> setting : s.members) if (changed(setting)) {
            setting.reset(); count++;
            UiAnim.set("set." + System.identityHashCode(setting) + ".reset", 1);
        }
        return count;
    }

    Module module() { return module; }
    boolean isEmpty() { return sections.isEmpty() && inline.isEmpty(); }

    void panelEdges(float left, float right) { for (Section s : sections) s.list.panelEdges(left, right); }

    /** Total height of all cards (with their current, animated body heights). */
    float height() {
        float total = tabsHeight();
        for (Object item : order()) total += UiDraw.CARD_HEAD + bodyOf(item) + GAP;
        return Math.max(0, total - GAP);
    }

    /** Offset of the card holding the focused setting, for scrolling it into view. */
    float focusOffset() {
        float offset = tabsHeight();
        for (Object item : order()) {
            if (item instanceof Section s && focusSetting != null && s.list.showsSetting(focusSetting)) return offset;
            offset += UiDraw.CARD_HEAD + bodyOf(item) + GAP;
        }
        return 0;
    }

    void render(GuiGraphicsExtractor g, int mx, int my, float delta, UiTheme t, float x, float y, float w, float clipTop, float clipBottom) {
        this.x = x; this.w = w;
        tops.clear();
        List<String> tabs = tabs();
        tabsTop = Float.NaN;
        if (!tabs.isEmpty()) {
            float appear = host.appear(2), opacity = UiRender.opacity;
            UiRender.opacity = opacity * appear;
            try { drawTabs(g, mx, my, t, tabs, currentTab(), x, y, w, clipTop, clipBottom); } finally { UiRender.opacity = opacity; }
            tabsTop = y;
            y += TABS + GAP;
        }
        List<Object> order = order();
        // Tool cards on another tab put their text fields away.
        for (InlineCard card : inline) if (!order.contains(card)) card.hide();
        for (int index = 0; index < order.size(); index++) {
            Object item = order.get(index);
            Section s = item instanceof Section section ? section : null;
            InlineCard card = item instanceof InlineCard c ? c : null;
            boolean open = s != null ? s.open() : card.open;
            if (s != null) s.body = UiAnim.approach(s.body, open ? s.list.contentHeight() + 6 : 0, 16);
            else card.animate(open);
            tops.put(item, y);
            float body = bodyOf(item), h = UiDraw.CARD_HEAD + body;
            String title = s != null ? s.title() : card.title();
            String subtitle;
            if (s != null) {
                int count = open ? s.list.rowCount() : s.size, changed = changed(sections.indexOf(s));
                subtitle = count + (count == 1 ? " setting" : " settings") + (changed > 0 ? " · " + changed + " changed" : "");
                if (s.group != null && s.group.groupNote != null) subtitle += " · " + s.group.groupNote;
            } else subtitle = card.subtitle();
            if (!open) subtitle += " · click to show";
            String icon = s != null ? icon(sections.indexOf(s)) : card.icon();
            // Cards rise into place one after another when the page opens.
            float appear = host.appear(index + 3), opacity = UiRender.opacity;
            UiRender.opacity = opacity * appear;
            g.pose().pushMatrix();
            g.pose().translate(0, (1 - appear) * 10);
            try {
                boolean cardHover = UiDraw.over(mx, my, x, y, w, h) && my >= clipTop && my < clipBottom;
                float lift = UiAnim.smooth("card.l." + module.getName() + "." + title, cardHover ? 1 : 0, 14);
                UiDraw.card(g, x, y, w, h, icon, title, subtitle, 40, t);
                if (lift > .01f) UiDraw.outline(g, x, y, w, h, 8, .7f, UiDraw.alpha(t.accent(), .28f * lift));
                boolean hover = UiDraw.over(mx, my, x, y, w, UiDraw.CARD_HEAD) && my >= clipTop && my < clipBottom;
                String foldKey = "card.o." + module.getName() + "." + title;
                float hv = UiAnim.smooth(foldKey + ".h", hover ? 1 : 0, 20);
                float shown = UiAnim.smooth(foldKey, open ? 1 : 0, 16);
                int colour = UiDraw.mix(t.muted(), t.text(), hv);
                if (hv > 0) UiDraw.circle(g, x + w - UiDraw.CARD_PAD - 7, y + 15, 8, UiDraw.alpha(t.surface(), hv));
                UiDraw.icon(g, "chevron_down", x + w - UiDraw.CARD_PAD - 11, y + 11, 8, UiDraw.alpha(colour, shown));
                UiDraw.icon(g, "chevron_right", x + w - UiDraw.CARD_PAD - 11, y + 11, 8, UiDraw.alpha(colour, 1 - shown));
                if (body > 1) {
                    // The body is clipped to the card while it opens or closes.
                    float bottom = Math.min(clipBottom, y + h);
                    g.enableScissor((int) x, (int) Math.max(clipTop, y + UiDraw.CARD_HEAD - 2), (int) (x + w), (int) bottom);
                    if (s != null) {
                        s.list.embed(x + UiDraw.CARD_PAD - 4, y + UiDraw.CARD_HEAD, w - UiDraw.CARD_PAD * 2 + 8, clipTop, bottom);
                        s.list.render(g, mx, my, delta, t);
                    } else card.render(g, mx, my, t, x + UiDraw.CARD_PAD, y + UiDraw.CARD_HEAD, w - UiDraw.CARD_PAD * 2, clipTop, Math.min(clipBottom, bottom));
                    g.disableScissor();
                } else if (card != null) card.hide();
            } finally { g.pose().popMatrix(); UiRender.opacity = opacity; }
            y += h + GAP;
        }
    }

    void popup(GuiGraphicsExtractor g, UiTheme t, int width, int height) {
        for (Section s : sections) if (s.body > 1) s.list.popup(g, t, width, height);
        for (InlineCard card : inline) if (onPage(card) && card.open && card.shown() > 1) card.popup(g, t, width, height);
    }

    String message() {
        for (InlineCard card : inline) { String m = card.error(); if (!m.isEmpty()) return m; }
        for (Section s : sections) { String m = s.list.message(); if (!m.isEmpty()) return m; }
        return "";
    }

    /** The latest success message from an inline card, or an empty string. */
    String notice() {
        for (InlineCard card : inline) { String m = card.notice(); if (!m.isEmpty()) return m; }
        return "";
    }

    boolean mouseClicked(MouseButtonEvent event, boolean doubled, float clipTop, float clipBottom) {
        double my = event.y();
        if (my < clipTop || my >= clipBottom) { stopEditing(); return false; }
        if (event.button() == 0 && tabClicked(event.x(), my)) return true;
        for (InlineCard card : inline) if (onPage(card) && card.open && card.shown() > 1 && card.mouseClicked(event, doubled)) { for (Section s : sections) s.list.stopEditing(); return true; }
        for (Object item : order()) {
            Float top = tops.get(item);
            if (top != null && event.button() == 0 && UiDraw.over(event.x(), my, x, top, w, UiDraw.CARD_HEAD)) {
                stopEditing();
                if (item instanceof Section s) s.toggle(); else { InlineCard card = (InlineCard) item; card.open = !card.open; }
                return true;
            }
        }
        for (Section s : sections) if (s.body > 1 && s.list.mouseClicked(event, doubled)) return true;
        return false;
    }

    boolean mouseDragged(MouseButtonEvent event) {
        for (InlineCard card : inline) if (card.dragged(event)) return true;
        for (Section s : sections) if (s.list.mouseDragged(event)) return true;
        return false;
    }
    boolean mouseReleased() {
        for (InlineCard card : inline) if (card.released()) return true;
        for (Section s : sections) if (s.list.mouseReleased()) return true;
        return false;
    }
    boolean keyPressed(KeyEvent event) { for (InlineCard card : inline) if (card.keyPressed(event)) return true; for (Section s : sections) if (s.list.keyPressed(event)) return true; return false; }
    void reattach() { for (InlineCard card : inline) card.reattach(); for (Section s : sections) s.list.reattach(); }
    void stopEditing() { for (InlineCard card : inline) card.stopEditing(); for (Section s : sections) s.list.stopEditing(); }
}
