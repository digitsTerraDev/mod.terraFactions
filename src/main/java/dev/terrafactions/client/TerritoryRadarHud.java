package dev.terrafactions.client;

import com.digitscodecompendium.terralib.client.gui.HudPanelPlacement;
import com.digitscodecompendium.terralib.client.gui.TerraGui;
import com.digitscodecompendium.terralib.client.gui.TerraUiTheme;
import dev.terrafactions.factions.FactionRank;
import dev.terrafactions.journeymap.TerraFactionsClientConfig;
import dev.terrafactions.network.TerritoryRadarPayload;
import dev.terrafactions.war.WarGoalType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public final class TerritoryRadarHud {
    private static final int PADDING = 6;
    private static final int LINE_HEIGHT = 10;
    private static final int ACCENT_WIDTH = 3;
    private static final int INDICATOR_SIZE = 10;
    private static final int INDICATOR_GAP = 3;
    private static final int STATUS_GAP = 8;
    private static final int ROW_GAP = 5;
    private static final int MIN_CONTENT_WIDTH = 96;
    private static final int VULNERABLE_RED = 0xFFFF5555;
    private static final int VULNERABLE_YELLOW = 0xFFFFFF55;
    private static final int ISOLATED_AMBER = 0xFFFFAA00;
    private static TerritoryRadarPayload state = TerritoryRadarPayload.hidden();
    private static boolean hudVisible = true;

    private TerritoryRadarHud() {
    }

    public static void accept(TerritoryRadarPayload payload) {
        state = payload;
    }

    static FactionRank playerFactionRank() {
        return state.factionRank();
    }

    static boolean hudVisible() {
        return hudVisible;
    }

    static boolean toggleHudVisibility() {
        hudVisible = !hudVisible;
        return hudVisible;
    }

    /** Renders the independently configured territory radar. */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!hudVisible || minecraft.options.hideGui || minecraft.player == null
                || !TerraFactionsClientConfig.RADAR_HUD.enabled()
                || (!state.radarVisible() && !state.warVisible())) {
            return;
        }

        boolean showRadar = state.radarVisible();
        String label = showRadar ? radarLabel() : "";
        int contentWidth = Math.max(MIN_CONTENT_WIDTH, minecraft.font.width(label));
        boolean showFactionInfo = showRadar && TerraFactionsClientConfig.FACTION_INFO_ENABLED.get()
                && state.factionInfoVisible();
        String power = "P: " + state.power() + "/" + state.maximumPower();
        if (showFactionInfo) {
            contentWidth = Math.max(contentWidth,
                    minecraft.font.width(power) + STATUS_GAP + statusWidth(minecraft));
        }
        String warTitle = state.warVisible() ? "WAR: " + state.warOpponent() + " - "
                + state.warState().name() : "";
        String ownGoal = state.warVisible() ? "Goal: " + warGoalLabel() : "";
        String enemyGoal = state.warVisible() ? "Enemy: "
                + (state.enemyWarGoal() == null ? "Not selected" : pretty(state.enemyWarGoal().name())) : "";
        if (state.warVisible()) {
            contentWidth = Math.max(contentWidth, minecraft.font.width(warTitle));
            contentWidth = Math.max(contentWidth, minecraft.font.width(ownGoal));
            contentWidth = Math.max(contentWidth, minecraft.font.width(enemyGoal));
        }
        int width = contentWidth + PADDING * 2 + ACCENT_WIDTH + 2;
        int radarHeight = showRadar ? LINE_HEIGHT + (showFactionInfo ? ROW_GAP + LINE_HEIGHT : 0) : 0;
        int warHeight = state.warVisible() ? LINE_HEIGHT * 3 + ROW_GAP : 0;
        int contentHeight = radarHeight + (radarHeight > 0 && warHeight > 0 ? ROW_GAP : 0) + warHeight;
        int height = contentHeight + PADDING * 2;
        HudPanelPlacement placement = TerraFactionsClientConfig.RADAR_HUD.placement();
        double screenX = graphics.guiWidth() * placement.horizontalPercent() / 100.0D;
        double screenY = graphics.guiHeight() * placement.verticalPercent() / 100.0D;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate((float) screenX, (float) screenY, 0.0F);
            graphics.pose().scale((float) placement.scale(), (float) placement.scale(), 1.0F);
            graphics.pose().translate((float) (-width * placement.anchor().horizontal()),
                    (float) (-height * placement.anchor().vertical()), 0.0F);

            TerraGui.raisedPanel(graphics, 0, 0, width, height, TerraUiTheme.VANILLA,
                    TerraFactionsClientConfig.RADAR_HUD.opacity());
            int lineY = PADDING;
            int textX = PADDING + ACCENT_WIDTH + 2;
            if (showRadar) {
                int accent = state.vulnerable() ? vulnerabilityColor(minecraft)
                        : state.isolated() ? ISOLATED_AMBER : 0xFF000000 | state.relationColor();
                graphics.fill(PADDING, lineY, PADDING + ACCENT_WIDTH, lineY + LINE_HEIGHT - 1, accent);
                graphics.drawString(minecraft.font, label, textX, lineY,
                        0xFF000000 | state.relationColor(), false);
                lineY += LINE_HEIGHT;
                if (showFactionInfo) {
                    int separatorY = lineY + 2;
                    graphics.fill(textX, separatorY, width - PADDING, separatorY + 1,
                            TerraUiTheme.VANILLA.surfaceHighlight());
                    lineY += ROW_GAP;
                    renderFactionInfo(graphics, minecraft, power, textX, lineY);
                    lineY += LINE_HEIGHT;
                }
            }
            if (state.warVisible()) {
                if (lineY > PADDING) {
                    graphics.fill(textX, lineY + 2, width - PADDING, lineY + 3,
                            TerraUiTheme.VANILLA.surfaceHighlight());
                    lineY += ROW_GAP;
                }
                graphics.drawString(minecraft.font, warTitle, textX, lineY, VULNERABLE_RED, false);
                graphics.drawString(minecraft.font, ownGoal, textX, lineY + LINE_HEIGHT,
                        state.ownWarFailed() ? VULNERABLE_RED
                                : state.ownWarCompleted() ? 0xFF55FF55 : TerraUiTheme.VANILLA.text(), false);
                graphics.drawString(minecraft.font, enemyGoal, textX, lineY + LINE_HEIGHT * 2,
                        TerraUiTheme.VANILLA.mutedText(), false);
            }
        } finally {
            graphics.pose().popPose();
        }
    }

    private static String warGoalLabel() {
        WarGoalType goal = state.ownWarGoal();
        if (goal == null) return "Not selected";
        String label = pretty(goal.name());
        if (goal == WarGoalType.CONQUEST || goal == WarGoalType.PLUNDER || goal == WarGoalType.PUNITIVE) {
            label += " " + state.ownWarProgress() + "/" + state.ownWarRequired();
        }
        if (goal.requiresWarCamp()) {
            label += " | Camp: " + (state.warCampState() == null
                    ? "Not placed" : pretty(state.warCampState().name()));
        }
        return label;
    }

    private static String pretty(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String radarLabel() {
        String label = state.faction().isBlank() || "Wilderness".equals(state.territory())
                ? state.territory()
                : state.faction() + " - " + state.territory();
        return state.isolated() ? label + " [ISOLATED]" : label;
    }

    private static int statusWidth(Minecraft minecraft) {
        return INDICATOR_SIZE + INDICATOR_GAP + minecraft.font.width("Border") + STATUS_GAP
                + INDICATOR_SIZE + INDICATOR_GAP + minecraft.font.width("Core");
    }

    private static void renderFactionInfo(GuiGraphics graphics, Minecraft minecraft, String power, int x, int y) {
        graphics.drawString(minecraft.font, power, x, y + 1, TerraUiTheme.VANILLA.text(), false);
        int borderX = x + minecraft.font.width(power) + STATUS_GAP;
        int coreX = drawStatus(graphics, minecraft, borderX, y, "Border", state.borderVulnerable());
        drawStatus(graphics, minecraft, coreX + STATUS_GAP, y, "Core", state.coreVulnerable());
    }

    private static int drawStatus(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                  String label, boolean vulnerable) {
        drawStatusPip(graphics, minecraft, x, y, vulnerable);
        int labelX = x + INDICATOR_SIZE + INDICATOR_GAP;
        graphics.drawString(minecraft.font, label, labelX, y + 1,
                TerraUiTheme.VANILLA.mutedText(), false);
        return labelX + minecraft.font.width(label);
    }

    private static void drawStatusPip(GuiGraphics graphics, Minecraft minecraft, int x, int y,
                                      boolean vulnerable) {
        if (!vulnerable) {
            TerraGui.indicator(graphics, x, y, true, TerraUiTheme.VANILLA);
            return;
        }

        int color = vulnerabilityColor(minecraft);
        int highlight = color == VULNERABLE_YELLOW ? 0xFFFFFFAA : 0xFFFFAAAA;
        graphics.fill(x, y, x + INDICATOR_SIZE, y + INDICATOR_SIZE, TerraUiTheme.VANILLA.outline());
        graphics.fill(x + 2, y + 2, x + 8, y + 8, color);
        graphics.fill(x + 3, y + 3, x + 6, y + 4, highlight);
    }

    private static int vulnerabilityColor(Minecraft minecraft) {
        if (!TerraFactionsClientConfig.VULNERABILITY_FLASH_ENABLED.get() || minecraft.level == null) {
            return VULNERABLE_RED;
        }
        return (minecraft.level.getGameTime() / 10L & 1L) == 0L ? VULNERABLE_RED : VULNERABLE_YELLOW;
    }
}
