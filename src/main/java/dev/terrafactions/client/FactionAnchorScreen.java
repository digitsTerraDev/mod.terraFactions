package dev.terrafactions.client;

import com.digitscodecompendium.terralib.client.gui.TerraButton;
import com.digitscodecompendium.terralib.client.gui.TerraGui;
import com.digitscodecompendium.terralib.client.gui.TerraTextField;
import com.digitscodecompendium.terralib.client.gui.TerraUiTheme;
import dev.terrafactions.network.AnchorPowerPayload;
import dev.terrafactions.network.AnchorStatePayload;
import dev.terrafactions.network.AnchorStateRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FactionAnchorScreen extends Screen {
    private static final TerraUiTheme THEME = TerraUiTheme.VANILLA;
    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 205;

    private AnchorStatePayload state;
    private TerraTextField powerField;
    private TerraTextField priorityField;
    private Component validationMessage = Component.empty();
    private int panelX;
    private int panelY;

    private FactionAnchorScreen(AnchorStatePayload state) {
        super(text("title"));
        this.state = state;
    }

    public static void accept(AnchorStatePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof FactionAnchorScreen screen && screen.state.pos().equals(payload.pos())) {
            screen.state = payload;
            if (screen.powerField != null && !screen.powerField.isFocused()) {
                screen.powerField.setValue(Integer.toString(payload.allocatedPower()));
            }
            if (screen.priorityField != null && !screen.priorityField.isFocused()) {
                screen.priorityField.setValue(Integer.toString(payload.priority()));
            }
            screen.validationMessage = Component.empty();
        } else {
            minecraft.setScreen(new FactionAnchorScreen(payload));
        }
    }

    @Override
    protected void init() {
        panelX = (width - PANEL_WIDTH) / 2;
        panelY = (height - PANEL_HEIGHT) / 2;
        powerField = addRenderableWidget(TerraTextField.builder(font, text("power"))
                .bounds(panelX + 16, panelY + 67, 126, 20)
                .maxLength(9).initialValue(Integer.toString(state.allocatedPower()))
                .filter(value -> value.matches("\\d*"))
                .hint(text("power")).theme(THEME).build());
        priorityField = addRenderableWidget(TerraTextField.builder(font, Component.literal("Priority"))
                .bounds(panelX + 150, panelY + 67, 62, 20)
                .maxLength(6).initialValue(Integer.toString(state.priority()))
                .filter(value -> value.matches("-?\\d*")).hint(Component.literal("Priority")).theme(THEME).build());
        addRenderableWidget(TerraButton.text(text("apply"), button -> applyPower())
                .bounds(panelX + 220, panelY + 67, 64, 20).theme(THEME).build());
        addRenderableWidget(TerraButton.text(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(panelX + 220, panelY + 91, 64, 20).theme(THEME).build());
    }

    private void applyPower() {
        try {
            int power = Integer.parseInt(powerField.getValue());
            int priority = Integer.parseInt(priorityField.getValue());
            if (power < 0 || power > state.maximumPower()) {
                validationMessage = text("power_range", state.maximumPower());
                return;
            }
            validationMessage = text("updating");
            PacketDistributor.sendToServer(new AnchorPowerPayload(state.pos(), power, priority));
        } catch (NumberFormatException exception) {
            validationMessage = text("power_range", state.maximumPower());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft != null && minecraft.level != null && minecraft.level.getGameTime() % 20L == 0L) {
            PacketDistributor.sendToServer(new AnchorStateRequestPayload(state.pos()));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TerraGui.raisedPanel(graphics, panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT, THEME);
        graphics.drawCenteredString(font, title, width / 2, panelY + 9, THEME.text());
        graphics.drawString(font, text("owner", state.factionName()), panelX + 16, panelY + 28,
                THEME.text(), false);
        graphics.drawString(font, text("tier", state.tierName()), panelX + 16, panelY + 42,
                THEME.text(), false);
        graphics.drawString(font, text("cost", formatCost(state.powerTenthsPerClaim())), panelX + 150, panelY + 42,
                THEME.mutedText(), false);
        graphics.drawString(font, text("usable", formatCost(state.usablePowerTenths()),
                        stateText("power_state", state.powerState().name())),
                panelX + 16, panelY + 101, state.powerState().name().equals("FULL")
                        ? THEME.positive() : THEME.negative(), false);
        graphics.drawString(font, text("projected", state.projectedClaims()), panelX + 16, panelY + 115,
                THEME.mutedText(), false);
        graphics.drawString(font, text("radius", state.projectedRadius()), panelX + 150, panelY + 115,
                THEME.mutedText(), false);
        graphics.drawString(font, Component.literal(state.capital() ? "Core / Capital Anchor" : "Border Anchor"),
                panelX + 16, panelY + 129, state.capital() ? 0xFFAA55FF : THEME.text(), false);
        graphics.drawString(font, text("vulnerability",
                        stateText("vulnerability_state", state.vulnerabilityState().name())), panelX + 150,
                panelY + 129, state.vulnerabilityState().name().equals("PROTECTED") ? THEME.positive()
                        : state.vulnerabilityState().name().equals("PENDING") ? 0xFFFFDD33 : THEME.negative(), false);
        graphics.drawString(font, Component.literal(state.skyExposed() ? "Sky path: clear" : "Sky path: blocked"),
                panelX + 16, panelY + 143, state.skyExposed() ? THEME.positive() : THEME.negative(), false);
        if (state.capital() && state.siegeDamage() > 0) graphics.drawString(font,
                Component.literal("Siege: " + state.siegeDamage() + "/" + state.siegeDamageRequired()),
                panelX + 150, panelY + 143, THEME.negative(), false);
        graphics.drawString(font, text("power_help", state.maximumPower()), panelX + 16, panelY + 161,
                THEME.mutedText(), false);
        if (!validationMessage.getString().isEmpty()) {
            graphics.drawString(font, validationMessage, panelX + 16, panelY + 179, THEME.negative(), false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Keep the world visible behind this compact block configuration screen.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static Component text(String key, Object... args) {
        return Component.translatable("screen.terrafactions.anchor." + key, args);
    }

    private static Component stateText(String group, String value) {
        return text(group + "." + value.toLowerCase(java.util.Locale.ROOT));
    }

    private static String formatCost(int tenths) {
        return tenths % 10 == 0 ? Integer.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }
}
