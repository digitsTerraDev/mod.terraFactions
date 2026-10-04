package dev.terrafactions.client;

import com.digitscodecompendium.terralib.client.gui.TerraButton;
import com.digitscodecompendium.terralib.client.gui.TerraDropdown;
import com.digitscodecompendium.terralib.client.gui.TerraGui;
import com.digitscodecompendium.terralib.client.gui.TerraMenu;
import com.digitscodecompendium.terralib.client.gui.TerraTextField;
import com.digitscodecompendium.terralib.client.gui.TerraUiTheme;
import dev.terrafactions.factions.FactionChatMode;
import dev.terrafactions.factions.FactionRank;
import dev.terrafactions.factions.FactionRelation;
import dev.terrafactions.territory.ProtectionAction;
import dev.terrafactions.territory.ProtectionPolicy;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.journeymap.TerraFactionsClientConfig;
import dev.terrafactions.network.FactionUiPayload;
import dev.terrafactions.network.FactionActionPayload;
import dev.terrafactions.network.FactionActionPayload.Action;
import dev.terrafactions.registry.TerraFactionsBlocks;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Informational faction dashboard backed by a server-synchronized snapshot. */
public final class FactionDashboardScreen extends Screen {
    private static final TerraUiTheme THEME = TerraUiTheme.VANILLA;
    private static final int PANEL_WIDTH = 430;
    private static final int PANEL_HEIGHT = 270;
    private static final int PAD = 10;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 6;
    private static final int ROW_HEIGHT = 18;
    private static volatile FactionUiPayload state = FactionUiPayload.empty();

    private Tab selectedTab = Tab.OVERVIEW;
    private List<Tab> visibleTabs = List.of();
    private FactionUiPayload shownState = FactionUiPayload.empty();
    private TerraMenu.TabbedPanelLayout tabLayout;
    private TerraMenu.ScrollPanelLayout scrollLayout;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int scrollOffset;
    private int selectedFaction;
    private int selectedAdminFaction;
    private int selectedWarFaction;
    private String selectedWarTarget = "";
    private WarGoalType warGoalChoice = WarGoalType.CONQUEST;
    private TerraTextField memberField;
    private TerraTextField memberSearchField;
    private String memberSearch = "";
    private String selectedMember = "";
    private TerraTextField colorField;
    private TerraTextField adminPowerField;
    private int colorPreviewX;
    private int colorPreviewY;

    public FactionDashboardScreen() {
        super(text("title"));
    }

    public static void accept(FactionUiPayload payload) {
        state = payload;
    }

    @Override
    protected void init() {
        panelWidth = Math.min(PANEL_WIDTH, Math.max(220, width - 90));
        panelHeight = Math.min(PANEL_HEIGHT, Math.max(190, height - 36));
        panelX = (width - panelWidth) / 2 + 28;
        panelY = (height - panelHeight) / 2;
        shownState = state;
        refreshTabs();
        populateWidgets();
    }

    private void refreshTabs() {
        List<Tab> tabs = new ArrayList<>();
        tabs.add(Tab.OVERVIEW);
        if (state.hasFaction()) tabs.add(Tab.MEMBERS);
        if (state.hasFaction()) tabs.add(Tab.LOSSES);
        if (state.hasFaction() && state.rank().canBuild()) tabs.add(Tab.TERRITORY);
        if (state.hasFaction()) tabs.add(Tab.PROTECTIONS);
        if (state.hasFaction()) tabs.add(Tab.WAR);
        tabs.add(Tab.RELATIONS);
        if (!state.hasFaction() || state.rank().isLeadership()) tabs.add(Tab.FACTION);
        tabs.add(Tab.SETTINGS);
        if (isOperator()) tabs.add(Tab.ADMIN);
        visibleTabs = List.copyOf(tabs);
        if (!visibleTabs.contains(selectedTab)) selectedTab = Tab.OVERVIEW;
        selectedFaction = Math.clamp(selectedFaction, 0, Math.max(0, state.factions().size() - 1));
        selectedAdminFaction = Math.clamp(selectedAdminFaction, 0,
                Math.max(0, state.adminFactions().size() - 1));
        selectedWarFaction = Math.clamp(selectedWarFaction, 0, Math.max(0, state.factions().size() - 1));
    }

    private void populateWidgets() {
        clearWidgets();
        scrollLayout = null;
        memberField = null;
        memberSearchField = null;
        colorField = null;
        adminPowerField = null;
        int x = panelX + PAD;
        int bottom = panelY + panelHeight - PAD - BUTTON_HEIGHT;
        switch (selectedTab) {
            case OVERVIEW -> overviewWidgets(x, bottom);
            case MEMBERS -> memberWidgets(x, bottom);
            case LOSSES -> { }
            case TERRITORY -> territoryWidgets(x, bottom);
            case PROTECTIONS -> protectionWidgets(x);
            case WAR -> warWidgets(x, bottom);
            case RELATIONS -> relationWidgets(x, bottom);
            case FACTION -> factionWidgets(x, bottom);
            case SETTINGS -> settingsWidgets(x, panelY + 42);
            case ADMIN -> adminWidgets(x, bottom);
        }
        addButton(CommonComponents.GUI_DONE, panelX + panelWidth - 66, panelY + 4, 56, button -> onClose());
    }

    private void overviewWidgets(int x, int y) {
        if (!state.hasFaction()) return;
        int buttonX = panelX + panelWidth - 154;
        int buttonY = panelY + 4;
        if (state.rank() == FactionRank.OWNER) {
            confirmButton("disband", buttonX, buttonY, 82, Action.DISBAND, "confirm_disband");
        } else {
            confirmButton("leave", buttonX, buttonY, 82, Action.LEAVE, "confirm_leave");
        }
    }

    private void memberWidgets(int x, int y) {
        memberSearchField = addRenderableWidget(TerraTextField.builder(font, text("search_members"))
                .bounds(x, panelY + 48, contentWidth(), BUTTON_HEIGHT)
                .maxLength(40).initialValue(memberSearch).hint(text("search_members"))
                .responder(value -> {
                    memberSearch = value;
                    scrollOffset = 0;
                }).theme(THEME).build());
        if (!state.rank().isLeadership()) return;
        int contentWidth = contentWidth();
        int playerWidth = Math.max(72, contentWidth - 150);
        TerraTextField player = field(x, y, playerWidth, "player", 40, "");
        memberField = player;
        int actionX = x + playerWidth + GAP;
        int actionWidth = Math.max(42, (contentWidth - playerWidth - GAP * 2) / 2);
        addButton(text("invite"), actionX, y, actionWidth,
                button -> sendInput(player, value -> send(Action.INVITE, value)));
        addButton(text("kick"), actionX + actionWidth + GAP, y, actionWidth,
                button -> sendInput(player, value -> send(Action.KICK, value)));
        if (state.rank() == FactionRank.OWNER) {
            int rankY = y - BUTTON_HEIGHT - GAP;
            int dropdownWidth = Math.min(130, Math.max(82, contentWidth - 70));
            TerraDropdown<FactionRank> ranks = addRenderableWidget(TerraDropdown.builder(
                            text("rank"), List.of(FactionRank.OWNER, FactionRank.LEADER, FactionRank.COMMANDER,
                                    FactionRank.MEMBER, FactionRank.GUEST), FactionRank.MEMBER,
                            (dropdown, rank) -> { })
                    .optionLabel(FactionDashboardScreen::rankText)
                    .bounds(x, rankY, dropdownWidth, BUTTON_HEIGHT).theme(THEME).build());
            addButton(text("apply"), x + dropdownWidth + GAP, rankY,
                    Math.min(64, contentWidth - dropdownWidth - GAP),
                    button -> sendInput(player, value ->
                            send(Action.SET_RANK, value, ranks.value().name())));
        }
    }

    private void territoryWidgets(int x, int y) {
        int contentWidth = contentWidth();
        int buttonWidth = Math.max(42, (contentWidth - GAP * 2) / 3);
        addButton(text("claim_core"), x, y, buttonWidth, button -> send(Action.CLAIM_CORE));
        addButton(text("unclaim"), x + buttonWidth + GAP, y, buttonWidth,
                button -> send(Action.UNCLAIM));
        addButton(text("set_capital"), x + (buttonWidth + GAP) * 2, y, buttonWidth,
                button -> send(Action.SET_CAPITAL));
    }

    private void protectionWidgets(int x) {
        int columnWidth = (contentWidth() - GAP) / 2;
        int y = panelY + 52;
        for (ProtectionAction action : ProtectionAction.values()) {
            addProtectionToggle(x, y, columnWidth, TerritoryType.CORE, action);
            addProtectionToggle(x + columnWidth + GAP, y,
                    contentWidth() - columnWidth - GAP, TerritoryType.BORDER, action);
            y += BUTTON_HEIGHT + 2;
        }
    }

    private void addProtectionToggle(int x, int y, int width, TerritoryType territory,
                                     ProtectionAction action) {
        boolean core = territory != TerritoryType.BORDER;
        int effectiveMask = core ? state.coreProtectionMask() : state.borderProtectionMask();
        int configurableMask = core ? state.coreConfigurableProtectionMask()
                : state.borderConfigurableProtectionMask();
        boolean configurable = action.enabledIn(configurableMask);
        Component label = configurable ? protectionText(action)
                : text("protection_server_locked", protectionText(action));
        TerraButton button = addRenderableWidget(TerraButton.toggle(label, action.enabledIn(effectiveMask),
                        (pressed, enabled) -> send(new FactionActionPayload(Action.SET_PROTECTION,
                                action.name(), territory.name(), "", enabled)))
                .bounds(x, y, width, BUTTON_HEIGHT).theme(THEME).build());
        button.active = state.rank().isLeadership() && configurable;
    }

    private void relationWidgets(int x, int y) {
        if (!state.hasFaction() || !state.rank().isLeadership() || state.factions().isEmpty()) return;
        int buttonWidth = Math.max(50, (contentWidth() - GAP * 2) / 3);
        addButton(text("ally"), x, y, buttonWidth, button -> declare(FactionRelation.ALLIED));
        addButton(text("neutral"), x + buttonWidth + GAP, y, buttonWidth,
                button -> declare(FactionRelation.NEUTRAL));
        addButton(text("enemy"), x + (buttonWidth + GAP) * 2, y, buttonWidth,
                button -> declare(FactionRelation.ENEMY));
    }

    private void warWidgets(int x, int bottom) {
        if (!state.hasFaction() || state.factions().isEmpty()) return;
        FactionUiPayload.FactionEntry opponent = selectedWarOpponent();
        FactionUiPayload.WarEntry war = warWith(opponent.name());
        int selectorWidth = contentWidth();
        addRenderableWidget(TerraDropdown.builder(text("war_opponent"), state.factions(), opponent,
                        (dropdown, selected) -> {
                            selectedWarFaction = Math.max(0, state.factions().indexOf(selected));
                            selectedWarTarget = "";
                            scrollOffset = 0;
                            populateWidgets();
                        })
                .optionLabel(faction -> Component.literal("[" + faction.tag() + "] " + faction.name()))
                .bounds(x, panelY + 48, selectorWidth, BUTTON_HEIGHT).theme(THEME).build());
        if (state.rank().isLeadership() && war == null) {
            addButton(text("war_declare"), x, bottom, contentWidth(),
                    button -> sendWar(Action.DECLARE_WAR, opponent.name(), null, ""));
        }
    }

    private void addTargetButton(int x, int y, int width, FactionUiPayload.FactionEntry opponent,
                                 FactionUiPayload.WarEntry war, String target) {
        boolean selected = war.targetAnchorIds().contains(target);
        addButton(text(selected ? "war_remove_target" : "war_add_target"), x, y, width,
                button -> sendWar(selected ? Action.REMOVE_WAR_TARGET : Action.ADD_WAR_TARGET,
                        opponent.name(), null, target));
    }

    private void factionWidgets(int x, int y) {
        if (!state.hasFaction()) {
            int nameWidth = Math.max(72, contentWidth() - 140);
            int actionWidth = Math.max(52, (contentWidth() - nameWidth - GAP * 2) / 2);
            TerraTextField name = field(x, y, nameWidth, "faction_name", 32, "");
            addButton(text("create"), x + nameWidth + GAP, y, actionWidth,
                    button -> sendInput(name, value -> send(Action.CREATE, value)));
            addButton(text("join"), x + nameWidth + GAP + actionWidth + GAP, y, actionWidth,
                    button -> sendInput(name, value -> send(Action.JOIN, value)));
            return;
        }
        int fieldWidth = panelWidth - PAD * 2 - 76;
        TerraTextField name = field(x, panelY + 48, fieldWidth, "faction_name", 32, state.name());
        addButton(text("save"), x + fieldWidth + GAP, panelY + 48, 60,
                button -> sendInput(name, value -> send(Action.SET_NAME, value)));
        int descriptionY = panelY + 76;
        int descriptionHeight = Math.clamp(panelHeight - 140, 50, 72);
        MultiLineEditBox description = new MultiLineEditBox(font, x, descriptionY, fieldWidth,
                descriptionHeight, text("description"), text("description"));
        description.setCharacterLimit(256);
        description.setValue(state.description());
        addRenderableWidget(description);
        addButton(text("save"), x + fieldWidth + GAP, descriptionY, 60,
                button -> sendInput(description, value -> send(Action.SET_DESCRIPTION, value), true));
        int identityRowY = descriptionY + descriptionHeight + 16;
        int half = (contentWidth() - GAP) / 2;
        int smallAction = Math.min(82, Math.max(54, half / 2));
        int previewSize = 18;
        int colorWidth = Math.max(10, half - smallAction - GAP * 2 - previewSize);
        TerraTextField color = field(x, identityRowY, colorWidth, "color", 16,
                String.format("#%06X", state.color() & 0xFFFFFF));
        colorField = color;
        colorPreviewX = x + colorWidth + GAP;
        colorPreviewY = identityRowY + 1;
        addButton(text("set_color"), x + half - smallAction, identityRowY, smallAction,
                button -> sendInput(color, value -> send(Action.SET_COLOR, value)));
        int tagX = x + half + GAP;
        int tagAction = Math.max(12, half - 54 - GAP);
        TerraTextField tag = field(tagX, identityRowY, 54, "tag", 4, state.tag());
        addButton(text("set_tag"), tagX + 54 + GAP, identityRowY, tagAction,
                button -> sendInput(tag, value -> send(Action.SET_TAG, value)));
    }

    private void settingsWidgets(int x, int y) {
        int toggleWidth = Math.min(150, Math.max(90, (contentWidth() - GAP) / 2));
        addRenderableWidget(TerraButton.toggle(text("radar"), state.radarEnabled(),
                        (button, enabled) -> send(Action.SET_RADAR, enabled))
                .bounds(x, y, toggleWidth, BUTTON_HEIGHT).theme(THEME).build());
        if (state.hasFaction()) {
            addRenderableWidget(TerraDropdown.builder(text("chat_mode"), List.of(FactionChatMode.values()),
                            state.chatMode(), (dropdown, mode) ->
                                    send(Action.SET_CHAT, mode.name()))
                    .optionLabel(FactionDashboardScreen::chatModeText)
                    .bounds(x, y + 30, toggleWidth, BUTTON_HEIGHT).theme(THEME).build());
        }
        if (ModList.get().isLoaded("journeymap")) {
            addRenderableWidget(TerraButton.toggle(text("overlay"),
                            TerraFactionsClientConfig.OVERLAY_ENABLED.get(),
                            (button, enabled) -> setOverlayEnabled(enabled))
                    .bounds(x + toggleWidth + GAP, y, toggleWidth, BUTTON_HEIGHT).theme(THEME).build());
        }
        if (isOperator()) {
            int importWidth = Math.max(50, (contentWidth() - GAP) / 2);
            addButton(text("import_preview"), x, y + 72, importWidth,
                    button -> send(Action.IMPORT_PREVIEW));
            confirmButton("import_confirm", x + importWidth + GAP, y + 72, importWidth,
                    Action.IMPORT_CONFIRM, "confirm_import");
        }
    }

    private void adminWidgets(int x, int y) {
        if (!isOperator() || state.adminFactions().isEmpty()) return;
        int amountWidth = Math.min(110, Math.max(72, contentWidth() / 3));
        int buttonWidth = Math.max(54, (contentWidth() - amountWidth - GAP * 2) / 2);
        adminPowerField = field(x, y, amountWidth, "power_amount", 10, "10");
        addButton(text("give_power"), x + amountWidth + GAP, y, buttonWidth,
                button -> sendAdminPower(Action.ADMIN_GIVE_POWER));
        addButton(text("remove_power"), x + amountWidth + GAP + buttonWidth + GAP, y, buttonWidth,
                button -> sendAdminPower(Action.ADMIN_REMOVE_POWER));
    }

    @Override
    public void tick() {
        super.tick();
        if (!state.equals(shownState)) {
            shownState = state;
            refreshTabs();
            populateWidgets();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        List<Component> labels = visibleTabs.stream().map(Tab::label).toList();
        tabLayout = TerraMenu.tabbedPanel(graphics, font, panelX, panelY, panelWidth, panelHeight,
                labels, visibleTabs.indexOf(selectedTab), TerraMenu.TabSide.LEFT, THEME);
        graphics.drawString(font, selectedTab.label(), panelX + PAD, panelY + 9, THEME.text(), false);
        switch (selectedTab) {
            case OVERVIEW -> renderOverview(graphics);
            case MEMBERS -> renderMembers(graphics);
            case LOSSES -> renderLosses(graphics);
            case TERRITORY -> renderTerritory(graphics);
            case PROTECTIONS -> renderProtections(graphics);
            case WAR -> renderWar(graphics);
            case RELATIONS -> renderRelations(graphics);
            case FACTION -> renderFaction(graphics);
            case SETTINGS -> renderSettings(graphics);
            case ADMIN -> renderAdmin(graphics);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Intentionally transparent: Screen calls this hook before render(), and the vanilla
        // implementation both blurs and darkens the world behind an in-game screen.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void renderOverview(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 32;
        if (!state.hasFaction()) {
            section(graphics, text("factionless"), x, y);
            wrapped(graphics, text("factionless_help"), x, y + 18, panelWidth - PAD * 2);
            renderFactionDirectory(graphics, x, y + 52, panelWidth - PAD * 2, 112);
            return;
        }
        graphics.fill(x, y, x + 4, y + 31, 0xFF000000 | state.color());
        graphics.drawString(font, Component.literal("[" + state.tag() + "] " + state.name()), x + 10, y,
                0xFF000000 | state.color(), false);
        graphics.drawString(font, rankText(state.rank()), x + 10, y + 13, THEME.mutedText(), false);
        if (!state.description().isBlank()) wrapped(graphics, Component.literal(state.description()), x, y + 38,
                contentWidth(), 2);
        int breakdownY = y + 62;
        section(graphics, text("power_breakdown"), x, breakdownY);
        int boxY = breakdownY + 15;
        boolean suppressed = state.suppressedPower() > 0;
        int suppressionOffset = suppressed ? 13 : 0;
        TerraGui.recessedPanel(graphics, x, boxY, contentWidth(), 72 + suppressionOffset, THEME);
        long permanentSources = Math.max(0L, state.basePower()
                + (long) state.members().size() * state.powerPerMember());
        Component sources = text("power_sources", state.basePower(), state.members().size(),
                state.powerPerMember(), permanentSources);
        Component special = state.temporaryPower() > 0 && state.specialPower() != 0
                ? text("power_modifiers", signedPower(state.specialPower()), state.temporaryPower())
                : state.temporaryPower() > 0
                ? text("power_integration", state.temporaryPower())
                : state.specialPower() > 0
                ? text("power_special_addition", state.specialPower())
                : state.specialPower() < 0
                ? text("power_special_subtraction", Math.abs((long) state.specialPower()))
                : text("power_special_none");
        Component claims = text("power_claims", state.capitalClaims() + state.coreClaims(),
                state.coreClaimCost(), state.borderClaims() - state.projectedBorderClaims(),
                state.borderClaimCost(), state.projectedClaimUsage(), state.claimUsage());
        drawFit(graphics, sources, x + 6, boxY + 5, contentWidth() - 12, THEME.text());
        drawFit(graphics, special, x + 6, boxY + 18, contentWidth() - 12,
                state.temporaryPower() > 0 || state.specialPower() > 0 ? THEME.positive()
                        : state.specialPower() < 0 ? THEME.negative() : THEME.mutedText());
        if (suppressed) {
            drawFit(graphics, text("power_punitive", Math.round(state.suppressionPercent() * 100.0D),
                    state.suppressedPower()), x + 6, boxY + 31, contentWidth() - 12, THEME.negative());
        }
        drawFit(graphics, claims, x + 6, boxY + 31 + suppressionOffset,
                contentWidth() - 12, THEME.negative());
        graphics.drawString(font, text("power_deaths", state.deathLoss()), x + 6,
                boxY + 44 + suppressionOffset,
                state.deathLoss() > 0 ? THEME.negative() : THEME.mutedText(), false);
        Component remaining = text("power_remaining", state.power(), state.maximumPower());
        int remainingX = Math.max(x + 6, x + contentWidth() - font.width(remaining) - 6);
        graphics.drawString(font, remaining, remainingX, boxY + 58 + suppressionOffset,
                state.power() >= 0 ? THEME.positive() : THEME.negative(), false);

        int barY = boxY + 78 + suppressionOffset;
        double progress = state.maximumPower() <= 0 ? 0 : (double) Math.max(0, state.power()) / state.maximumPower();
        if (panelHeight >= 225) {
            TerraGui.progressBar(graphics, x, barY, contentWidth(), 10, progress, 0, THEME);
        }
        if (panelHeight >= 245) {
            status(graphics, text("core"), !state.coreVulnerable(), x, barY + 17);
            status(graphics, text("border"), !state.borderVulnerable(), x + 104, barY + 17);
        }
    }

    private void renderMembers(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 31;
        List<FactionUiPayload.MemberEntry> members = filteredMembers();
        section(graphics, text("member_roster", members.size()), x, y);
        int actionRows = state.rank().isLeadership() ? state.rank() == FactionRank.OWNER ? 2 : 1 : 0;
        renderScroll(graphics, x, y + 43, contentWidth(), panelHeight - 88 - actionRows * 26,
                members.size(), (entryY, index) -> {
                    FactionUiPayload.MemberEntry member = members.get(index);
                    if (member.name().equals(selectedMember)) {
                        graphics.fill(x + 2, entryY + 2, panelX + panelWidth - PAD - 15,
                                entryY + ROW_HEIGHT, 0x40FFFFFF);
                    }
                    TerraGui.colorPip(graphics, x + 8, entryY + 4,
                            member.online() ? THEME.positive() : THEME.mutedText(), THEME);
                    boolean compact = contentWidth() < 300;
                    int nameWidth = Math.max(30, contentWidth() - (compact ? 110 : 178));
                    graphics.drawString(font, font.plainSubstrByWidth(member.name(), nameWidth),
                            x + 23, entryY + 5, THEME.text(), false);
                    graphics.drawString(font, rankText(member.rank()),
                            x + contentWidth() - (compact ? 80 : 145),
                            entryY + 5, THEME.mutedText(), false);
                    if (!compact && member.deathLoss() > 0) graphics.drawString(font, "-" + member.deathLoss(),
                            x + contentWidth() - 45, entryY + 5, THEME.negative(), false);
                });
    }

    private void renderLosses(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 31;
        section(graphics, text("loss_summary", state.deathLoss(), state.losses().size()), x, y);
        if (state.losses().isEmpty()) {
            wrapped(graphics, text("no_losses"), x, y + 23, contentWidth());
            return;
        }
        renderScroll(graphics, x, y + 18, contentWidth(), panelHeight - 64,
                state.losses().size(), (entryY, index) -> {
                    FactionUiPayload.LossEntry loss = state.losses().get(index);
                    TerraGui.colorPip(graphics, x + 8, entryY + 4, THEME.negative(), THEME);
                    boolean compact = contentWidth() < 300;
                    int nameWidth = Math.max(40, contentWidth() - (compact ? 90 : 165));
                    graphics.drawString(font, font.plainSubstrByWidth(loss.name(), nameWidth),
                            x + 23, entryY + 5, THEME.text(), false);
                    if (!compact) graphics.drawString(font,
                            text(loss.currentMember() ? "current_member" : "former_member"),
                            x + contentWidth() - 132, entryY + 5, THEME.mutedText(), false);
                    Component amount = Component.literal("-" + loss.amount());
                    graphics.drawString(font, amount, x + contentWidth() - font.width(amount) - 16,
                            entryY + 5, THEME.negative(), false);
                });
    }

    private void renderTerritory(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 34;
        section(graphics, text("claim_summary"), x, y);
        int statWidth = Math.max(42, (contentWidth() - GAP * 3) / 4);
        stat(graphics, text("capital"), Integer.toString(state.capitalClaims()), x, y + 18, statWidth);
        stat(graphics, text("core"), Integer.toString(state.coreClaims()), x + statWidth + GAP, y + 18, statWidth);
        stat(graphics, text("border"), Integer.toString(state.borderClaims()),
                x + (statWidth + GAP) * 2, y + 18, statWidth);
        stat(graphics, text("claim_usage"), Integer.toString(state.claimUsage()),
                x + (statWidth + GAP) * 3, y + 18, statWidth);
        labelValue(graphics, text("capital_location"),
                state.capital().isBlank() ? text("not_set") : Component.literal(state.capital()), x, y + 62);
        status(graphics, text("core_status"), !state.coreVulnerable(), x, y + 74);
        status(graphics, text("border_status"), !state.borderVulnerable(),
                x + Math.min(150, contentWidth() / 2), y + 74);
        if (panelHeight >= 235) wrapped(graphics, text("territory_help"), x, y + 113, contentWidth());
    }

    private void renderProtections(GuiGraphics graphics) {
        int x = panelX + PAD;
        section(graphics, text("territory_protections"), x, panelY + 27);
        int columnWidth = (contentWidth() - GAP) / 2;
        graphics.drawString(font, text("core"), x, panelY + 41, THEME.mutedText(), false);
        graphics.drawString(font, text("border"), x + columnWidth + GAP,
                panelY + 41, THEME.mutedText(), false);
        int outsidePvpY = panelY + 52 + ProtectionAction.values().length * (BUTTON_HEIGHT + 2) + 3;
        graphics.drawString(font, text("outside_pvp", protectionPolicyText(state.outsidePvpPolicy())),
                x, outsidePvpY, THEME.mutedText(), false);
        if (panelHeight >= 255) {
            wrapped(graphics, text(state.rank().isLeadership()
                            ? "protections_help" : "protections_view_only"),
                    x, outsidePvpY + 15, contentWidth(), 2);
        }
    }

    private void renderRelations(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 31;
        section(graphics, text("faction_directory", state.factions().size()), x, y);
        renderScroll(graphics, x, y + 15, contentWidth(),
                panelHeight - 61 - (state.hasFaction() && state.rank().isLeadership() ? 26 : 0),
                state.factions().size(), (entryY, index) -> {
                    FactionUiPayload.FactionEntry faction = state.factions().get(index);
                    if (index == selectedFaction) graphics.fill(x + 2, entryY + 2,
                            panelX + panelWidth - PAD - 15, entryY + ROW_HEIGHT, 0x30FFFFFF);
                    TerraGui.colorPip(graphics, x + 8, entryY + 4, 0xFF000000 | faction.color(), THEME);
                    boolean compact = contentWidth() < 300;
                    int nameWidth = Math.max(40, contentWidth() - (compact ? 95 : 188));
                    graphics.drawString(font, font.plainSubstrByWidth(
                                    "[" + faction.tag() + "] " + faction.name(), nameWidth), x + 23, entryY + 5,
                            0xFF000000 | faction.color(), false);
                    if (!compact) graphics.drawString(font, text("member_count", faction.memberCount()),
                            x + contentWidth() - 158, entryY + 5, THEME.mutedText(), false);
                    graphics.drawString(font, relationText(faction), x + contentWidth() - 67, entryY + 5,
                            relationColor(faction), false);
                });
    }

    private void renderWar(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 31;
        section(graphics, text("war_management"), x, y);
        if (state.factions().isEmpty()) {
            wrapped(graphics, text("war_no_opponents"), x, y + 22, contentWidth());
            return;
        }
        FactionUiPayload.FactionEntry opponent = selectedWarOpponent();
        FactionUiPayload.WarEntry war = warWith(opponent.name());
        int infoY = panelY + 78;
        if (war == null) {
            graphics.drawString(font, text("war_no_active", opponent.name()), x, infoY,
                    THEME.mutedText(), false);
            wrapped(graphics, Component.literal("A declaration gives advance warning. At the defender's next "
                    + "daily war window, their Active Power is forced down over time."),
                    x, infoY + 14, contentWidth(), 3);
        } else {
            graphics.drawString(font, Component.literal(war.state().name() + " vs " + war.opponentName()),
                    x, infoY, 0xFF000000 | war.opponentColor(), false);
            String status = war.state() == WarState.PREPARING
                    ? "Warning active. Window opens in " + warPreparationSeconds(war) + "s."
                    : "War pressure is active. Break vulnerable Borders; siege fractured Capitals.";
            wrapped(graphics, Component.literal(status), x, infoY + 15, contentWidth(), 3);
        }
    }

    private void renderFaction(GuiGraphics graphics) {
        int x = panelX + PAD;
        if (!state.hasFaction()) {
            section(graphics, text("create_or_join"), x, panelY + 35);
            wrapped(graphics, text("create_or_join_help"), x, panelY + 54, panelWidth - PAD * 2);
        } else {
            section(graphics, text("identity_settings"), x, panelY + 29);
            renderColorPreview(graphics);
            if (panelHeight >= 225) {
                graphics.drawString(font, text("identity_help"), x, panelY + panelHeight - 42,
                        THEME.mutedText(), false);
            }
        }
    }

    private void renderSettings(GuiGraphics graphics) {
        int x = panelX + PAD;
        section(graphics, text("personal_settings"), x, panelY + 28);
        wrapped(graphics, text("settings_help"), x, panelY + 142, panelWidth - PAD * 2);
    }

    private void renderAdmin(GuiGraphics graphics) {
        int x = panelX + PAD;
        int y = panelY + 31;
        section(graphics, text("admin_powers"), x, y);
        if (state.adminFactions().isEmpty()) {
            wrapped(graphics, text("no_factions"), x, y + 20, contentWidth());
            return;
        }
        FactionUiPayload.AdminFactionEntry selected = state.adminFactions().get(selectedAdminFaction);
        Component summary = text("admin_power_summary", selected.power(), selected.maximumPower(),
                signedPower(selected.specialPower()));
        drawFit(graphics, summary, x, y + 18, contentWidth(), THEME.mutedText());
        renderScroll(graphics, x, y + 34, contentWidth(), panelHeight - 110,
                state.adminFactions().size(), (entryY, index) -> {
                    FactionUiPayload.AdminFactionEntry faction = state.adminFactions().get(index);
                    if (index == selectedAdminFaction) graphics.fill(x + 2, entryY + 2,
                            panelX + panelWidth - PAD - 15, entryY + ROW_HEIGHT, 0x40FFFFFF);
                    TerraGui.colorPip(graphics, x + 8, entryY + 4, 0xFF000000 | faction.color(), THEME);
                    drawFit(graphics, Component.literal("[" + faction.tag() + "] " + faction.name()),
                            x + 23, entryY + 5, Math.max(50, contentWidth() - 190),
                            0xFF000000 | faction.color());
                    Component power = Component.literal(faction.power() + "/" + faction.maximumPower());
                    graphics.drawString(font, power, x + contentWidth() - 125, entryY + 5,
                            faction.power() >= 0 ? THEME.text() : THEME.negative(), false);
                    Component special = Component.literal(signedPower(faction.specialPower()));
                    graphics.drawString(font, special, x + contentWidth() - font.width(special) - 16,
                            entryY + 5, faction.specialPower() > 0 ? THEME.positive()
                                    : faction.specialPower() < 0 ? THEME.negative() : THEME.mutedText(), false);
                });
    }

    private void renderFactionDirectory(GuiGraphics graphics, int x, int y, int width, int height) {
        int shown = Math.min(state.factions().size(), Math.max(0, (height - 8) / ROW_HEIGHT));
        TerraGui.recessedPanel(graphics, x, y, width, height, THEME);
        for (int index = 0; index < shown; index++) {
            FactionUiPayload.FactionEntry faction = state.factions().get(index);
            int rowY = y + 6 + index * ROW_HEIGHT;
            TerraGui.colorPip(graphics, x + 7, rowY, 0xFF000000 | faction.color(), THEME);
            graphics.drawString(font, "[" + faction.tag() + "] " + faction.name(), x + 21, rowY + 1,
                    0xFF000000 | faction.color(), false);
            graphics.drawString(font, text("member_count", faction.memberCount()), x + width - 88, rowY + 1,
                    THEME.mutedText(), false);
        }
    }

    private void renderScroll(GuiGraphics graphics, int x, int y, int width, int height,
                              int entries, RowRenderer renderer) {
        int contentHeight = entries * ROW_HEIGHT + 4;
        scrollLayout = TerraMenu.scrollPanel(graphics, x, y, width, height, contentHeight, scrollOffset,
                TerraMenu.ScrollAxis.VERTICAL, THEME);
        scrollOffset = scrollLayout.scrollBar().offset();
        TerraMenu.Bounds viewport = scrollLayout.viewport();
        graphics.enableScissor(viewport.x(), viewport.y(), viewport.right(), viewport.bottom());
        for (int index = 0; index < entries; index++) {
            int entryY = viewport.y() + 2 + index * ROW_HEIGHT - scrollOffset;
            if (entryY + ROW_HEIGHT >= viewport.y() && entryY <= viewport.bottom()) renderer.render(entryY, index);
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && tabLayout != null) {
            int tab = tabLayout.tabAt(mouseX, mouseY);
            if (tab >= 0 && tab < visibleTabs.size() && visibleTabs.get(tab) != selectedTab) {
                selectedTab = visibleTabs.get(tab);
                scrollOffset = 0;
                scrollLayout = null;
                populateWidgets();
                return true;
            }
        }
        if (button == 0 && selectedTab == Tab.RELATIONS && scrollLayout != null
                && scrollLayout.viewport().contains(mouseX, mouseY)) {
            int index = (int) ((mouseY - scrollLayout.viewport().y() - 2 + scrollOffset) / ROW_HEIGHT);
            if (index >= 0 && index < state.factions().size()) {
                selectedFaction = index;
                return true;
            }
        }
        if (button == 0 && selectedTab == Tab.MEMBERS && scrollLayout != null
                && scrollLayout.viewport().contains(mouseX, mouseY)) {
            int index = (int) ((mouseY - scrollLayout.viewport().y() - 2 + scrollOffset) / ROW_HEIGHT);
            List<FactionUiPayload.MemberEntry> members = filteredMembers();
            if (index >= 0 && index < members.size()) {
                selectedMember = members.get(index).name();
                if (memberField != null) memberField.setValue(selectedMember);
                return true;
            }
        }
        if (button == 0 && selectedTab == Tab.WAR && scrollLayout != null
                && scrollLayout.viewport().contains(mouseX, mouseY) && !state.factions().isEmpty()) {
            int index = (int) ((mouseY - scrollLayout.viewport().y() - 2 + scrollOffset) / ROW_HEIGHT);
            List<FactionUiPayload.WarTargetEntry> targets = warTargets(selectedWarOpponent().name());
            if (index >= 0 && index < targets.size()) {
                selectedWarTarget = targets.get(index).anchorId();
                populateWidgets();
                return true;
            }
        }
        if (button == 0 && selectedTab == Tab.ADMIN && scrollLayout != null
                && scrollLayout.viewport().contains(mouseX, mouseY)) {
            int index = (int) ((mouseY - scrollLayout.viewport().y() - 2 + scrollOffset) / ROW_HEIGHT);
            if (index >= 0 && index < state.adminFactions().size()) {
                selectedAdminFaction = index;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollLayout != null && scrollLayout.viewport().contains(mouseX, mouseY)) {
            scrollOffset = Math.clamp(scrollOffset - (int) Math.signum(scrollY) * ROW_HEIGHT,
                    0, scrollLayout.scrollBar().maximumOffset());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void declare(FactionRelation relation) {
        if (!state.factions().isEmpty()) send(Action.DECLARE_RELATION,
                state.factions().get(selectedFaction).name(), relation.name());
    }

    private TerraTextField field(int x, int y, int width, String key, int maxLength, String initial) {
        return addRenderableWidget(TerraTextField.builder(font, text(key)).bounds(x, y, width, BUTTON_HEIGHT)
                .maxLength(maxLength).initialValue(initial).hint(text(key)).theme(THEME).build());
    }

    private void addButton(Component label, int x, int y, int width, TerraButton.OnPress onPress) {
        addRenderableWidget(TerraButton.text(label, onPress).bounds(x, y, width, BUTTON_HEIGHT).theme(THEME).build());
    }

    private void confirmButton(String label, int x, int y, int width, Action action, String message) {
        addButton(text(label), x, y, width, button -> minecraft.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) send(action);
            minecraft.setScreen(this);
        }, text("confirm_title"), text(message))));
    }

    private void sendInput(TerraTextField input, Consumer<String> action) {
        sendInput(input, action, false);
    }

    private void sendInput(TerraTextField input, Consumer<String> action, boolean allowEmpty) {
        String value = input.getValue().trim();
        if (allowEmpty || !value.isEmpty()) action.accept(value);
        else setFocused(input);
    }

    private void sendInput(MultiLineEditBox input, Consumer<String> action, boolean allowEmpty) {
        String value = input.getValue().trim();
        if (allowEmpty || !value.isEmpty()) action.accept(value);
        else setFocused(input);
    }

    private void send(Action action) {
        send(new FactionActionPayload(action));
    }

    private void send(Action action, String primary) {
        send(new FactionActionPayload(action, primary, "", false));
    }

    private void send(Action action, String primary, String secondary) {
        send(new FactionActionPayload(action, primary, secondary, false));
    }

    private void send(Action action, boolean enabled) {
        send(new FactionActionPayload(action, "", "", enabled));
    }

    private void sendWar(Action action, String opponent, WarGoalType goal, String target) {
        send(new FactionActionPayload(action, opponent, goal == null ? "" : goal.name(),
                target == null ? "" : target, false));
    }

    private void send(FactionActionPayload payload) {
        if (minecraft.player != null && minecraft.player.connection != null) {
            PacketDistributor.sendToServer(payload);
        }
    }

    private void sendAdminPower(Action action) {
        if (adminPowerField == null || state.adminFactions().isEmpty()) return;
        String amount = adminPowerField.getValue().trim();
        try {
            if (Integer.parseInt(amount) <= 0) {
                setFocused(adminPowerField);
                return;
            }
        } catch (NumberFormatException exception) {
            setFocused(adminPowerField);
            return;
        }
        send(action, state.adminFactions().get(selectedAdminFaction).name(), amount);
    }

    private static String signedPower(int power) {
        return power > 0 ? "+" + power : Integer.toString(power);
    }

    private void setOverlayEnabled(boolean enabled) {
        TerraFactionsClientConfig.OVERLAY_ENABLED.set(enabled);
        TerraFactionsClientConfig.OVERLAY_ENABLED.save();
        send(Action.SET_OVERLAY, enabled);
    }

    private boolean isOperator() {
        return minecraft != null && minecraft.player != null && minecraft.player.hasPermissions(3);
    }

    private int totalClaims() {
        return state.capitalClaims() + state.coreClaims() + state.borderClaims();
    }

    private List<FactionUiPayload.MemberEntry> filteredMembers() {
        String query = memberSearch.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return state.members();
        return state.members().stream()
                .filter(member -> member.name().toLowerCase(Locale.ROOT).contains(query))
                .toList();
    }

    private FactionUiPayload.FactionEntry selectedWarOpponent() {
        return state.factions().get(Math.clamp(selectedWarFaction, 0, state.factions().size() - 1));
    }

    private FactionUiPayload.WarEntry warWith(String opponentName) {
        return state.wars().stream().filter(war -> war.opponentName().equalsIgnoreCase(opponentName))
                .findFirst().orElse(null);
    }

    private List<FactionUiPayload.WarTargetEntry> warTargets(String opponentName) {
        return state.warTargets().stream()
                .filter(target -> target.factionName().equalsIgnoreCase(opponentName)).toList();
    }

    private static boolean usesTargets(WarGoalType goal) {
        return goal == WarGoalType.CONQUEST || goal == WarGoalType.PLUNDER;
    }

    private static boolean needsCampAssignment(FactionUiPayload.WarEntry war) {
        return war != null && war.state() == WarState.ACTIVE && war.ownGoal() != null
                && war.ownGoal().requiresWarCamp() && war.campState() == null
                && !war.ownCompleted() && !war.ownFailed();
    }

    private boolean isHoldingWarCamp() {
        return minecraft != null && minecraft.player != null
                && (minecraft.player.getMainHandItem().is(TerraFactionsBlocks.WAR_CAMP_ITEM.get())
                || minecraft.player.getOffhandItem().is(TerraFactionsBlocks.WAR_CAMP_ITEM.get()));
    }

    private static String goalProgress(FactionUiPayload.WarEntry war) {
        if (war.ownGoal() == WarGoalType.CONQUEST || war.ownGoal() == WarGoalType.PLUNDER) {
            return " " + war.ownProgress() + "/" + war.targetAnchorIds().size();
        }
        return war.ownGoal() == WarGoalType.PUNITIVE
                ? " " + war.ownProgress() + "/" + war.ownRequired() + " P" : "";
    }

    private long warPreparationSeconds(FactionUiPayload.WarEntry war) {
        return Math.max(0L, war.preparationEndsAt() - System.currentTimeMillis()) / 1000L;
    }

    private int contentWidth() {
        return panelWidth - PAD * 2;
    }

    private void section(GuiGraphics graphics, Component title, int x, int y) {
        graphics.drawString(font, title, x, y, THEME.text(), false);
        graphics.fill(x, y + 12, panelX + panelWidth - PAD, y + 13, THEME.surfaceHighlight());
    }

    private void stat(GuiGraphics graphics, Component label, String value, int x, int y, int width) {
        TerraGui.recessedPanel(graphics, x, y, width, 32, THEME);
        graphics.drawString(font, label, x + 5, y + 5, THEME.mutedText(), false);
        graphics.drawString(font, value, x + 5, y + 17, THEME.text(), false);
    }

    private void labelValue(GuiGraphics graphics, Component label, Component value, int x, int y) {
        graphics.drawString(font, label, x, y, THEME.mutedText(), false);
        graphics.drawString(font, value, x + font.width(label) + 5, y, THEME.text(), false);
    }

    private void drawFit(GuiGraphics graphics, Component value, int x, int y, int width, int color) {
        graphics.drawString(font, font.plainSubstrByWidth(value.getString(), width), x, y, color, false);
    }

    private void status(GuiGraphics graphics, Component label, boolean secure, int x, int y) {
        TerraGui.booleanPip(graphics, x, y, secure, THEME);
        graphics.drawString(font, label, x + 14, y + 1, secure ? THEME.positive() : THEME.negative(), false);
        graphics.drawString(font, secure ? text("secure") : text("vulnerable"),
                x + 14, y + 11, THEME.mutedText(), false);
    }

    private void renderColorPreview(GuiGraphics graphics) {
        if (colorField == null) return;
        Integer color = parsePreviewColor(colorField.getValue());
        int outline = color == null ? THEME.negative() : THEME.outline();
        graphics.fill(colorPreviewX, colorPreviewY, colorPreviewX + 18, colorPreviewY + 18, outline);
        graphics.fill(colorPreviewX + 2, colorPreviewY + 2, colorPreviewX + 16, colorPreviewY + 16,
                color == null ? THEME.surfaceDark() : 0xFF000000 | color);
    }

    private static Integer parsePreviewColor(String value) {
        String normalized = value.trim();
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.matches("(?i)[0-9a-f]{6}")) return Integer.parseInt(hex, 16);
        ChatFormatting formatting = ChatFormatting.getByName(normalized.toLowerCase(Locale.ROOT));
        return formatting == null ? null : formatting.getColor();
    }

    private void wrapped(GuiGraphics graphics, Component value, int x, int y, int width) {
        wrapped(graphics, value, x, y, width, Integer.MAX_VALUE);
    }

    private void wrapped(GuiGraphics graphics, Component value, int x, int y, int width, int maximumLines) {
        int lineY = y;
        int lines = 0;
        for (var line : font.split(value, width)) {
            if (lines++ >= maximumLines) break;
            graphics.drawString(font, line, x, lineY, THEME.mutedText(), false);
            lineY += font.lineHeight + 1;
        }
    }

    private static Component rankText(FactionRank rank) {
        return text("rank." + rank.name().toLowerCase());
    }

    private static Component chatModeText(FactionChatMode mode) {
        return text("chat." + mode.name().toLowerCase());
    }

    private static Component warGoalText(WarGoalType goal) {
        return text("war_goal." + goal.name().toLowerCase(Locale.ROOT));
    }

    private static Component warGoalDescription(WarGoalType goal) {
        return text("war_goal." + goal.name().toLowerCase(Locale.ROOT) + ".description");
    }

    private static Component protectionText(ProtectionAction action) {
        return text("protection." + action.name().toLowerCase(Locale.ROOT));
    }

    private static Component protectionPolicyText(ProtectionPolicy policy) {
        return text("protection_policy." + policy.name().toLowerCase(Locale.ROOT));
    }

    private static Component relationText(FactionUiPayload.FactionEntry faction) {
        if (faction.allyProposed()) return text("relation.proposed");
        if (faction.allyRequested()) return text("relation.asking");
        return text("relation." + faction.relation().name().toLowerCase());
    }

    private static int relationColor(FactionUiPayload.FactionEntry faction) {
        if (faction.allyProposed() || faction.allyRequested()) return 0xFF5555FF;
        return switch (faction.relation()) {
            case ALLIED -> 0xFF55FF55;
            case ENEMY -> 0xFFFF5555;
            case NEUTRAL -> THEME.mutedText();
        };
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.terrafactions.factions." + key, args);
    }

    private enum Tab {
        OVERVIEW("overview"), MEMBERS("members"), LOSSES("losses"), TERRITORY("territory"),
        PROTECTIONS("protections"), WAR("war"),
        RELATIONS("relations"), FACTION("faction"), SETTINGS("settings"), ADMIN("admin_powers");
        private final String key;
        Tab(String key) { this.key = key; }
        Component label() { return text(key); }
    }

    @FunctionalInterface
    private interface RowRenderer {
        void render(int y, int index);
    }
}
