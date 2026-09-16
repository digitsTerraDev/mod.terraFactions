package dev.terrafactions.factions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import dev.terrafactions.territory.TerritoryType;
import dev.terrafactions.territory.TerraFactionsConfig;
import dev.terrafactions.network.FactionActionPayload;
import dev.terrafactions.war.WarGoalType;
import dev.terrafactions.war.WarGoalSnapshot;
import dev.terrafactions.war.WarManager;
import dev.terrafactions.war.WarSnapshot;
import dev.terrafactions.anchor.FactionAnchorBlockEntity;
import dev.terrafactions.anchor.AnchorMapSnapshot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.io.IOException;

/** Brigadier command surface for the standalone faction authority. */
public final class FactionCommandService {
    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_DESCRIPTION_LENGTH = 256;

    private final NativeFactionService factions;
    private final WarManager wars;
    private final Runnable refreshDisplays;
    private final BiPredicate<UUID, TerritoryType> vulnerability;
    private final LegacyFactionImporter legacyImporter = new LegacyFactionImporter();

    public FactionCommandService(NativeFactionService factions, WarManager wars, Runnable refreshDisplays,
                                 BiPredicate<UUID, TerritoryType> vulnerability) {
        this.factions = factions;
        this.wars = wars;
        this.refreshDisplays = refreshDisplays;
        this.vulnerability = vulnerability;
    }

    public void register(CommandDispatcher<CommandSourceStack> dispatcher, String root) {
        dispatcher.register(Commands.literal(root)
                .executes(context -> overview(context.getSource()))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> create(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("disband").executes(context -> disband(context.getSource())))
                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> invite(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("join")
                        .then(Commands.argument("faction", StringArgumentType.greedyString())
                                .suggests(this::suggestFactionNames)
                                .executes(context -> join(context.getSource(),
                                        StringArgumentType.getString(context, "faction")))))
                .then(Commands.literal("leave").executes(context -> leave(context.getSource())))
                .then(Commands.literal("kick")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> kick(context.getSource(),
                                        EntityArgument.getPlayer(context, "player")))))
                .then(Commands.literal("rank")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(rankLiteral("leader", FactionRank.LEADER))
                                .then(rankLiteral("commander", FactionRank.COMMANDER))
                                .then(rankLiteral("member", FactionRank.MEMBER))
                                .then(rankLiteral("guest", FactionRank.GUEST))
                                .then(Commands.literal("owner").executes(context -> transferOwnership(
                                        context.getSource(), EntityArgument.getPlayer(context, "player"))))))
                .then(Commands.literal("declare")
                        .then(relationLiteral("ally", FactionRelation.ALLIED))
                        .then(relationLiteral("neutral", FactionRelation.NEUTRAL))
                        .then(relationLiteral("enemy", FactionRelation.ENEMY)))
                .then(Commands.literal("modify")
                        .then(Commands.literal("name")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> setName(context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("description")
                                .then(Commands.argument("description", StringArgumentType.greedyString())
                                        .executes(context -> setDescription(context.getSource(),
                                                StringArgumentType.getString(context, "description")))))
                        .then(Commands.literal("color")
                                .then(Commands.argument("color", StringArgumentType.word())
                                        .executes(context -> setColor(context.getSource(),
                                                StringArgumentType.getString(context, "color")))))
                        .then(buildTagCommands()))
                .then(Commands.literal("settings")
                        .then(Commands.literal("radar")
                                .executes(context -> toggleRadar(context.getSource()))
                                .then(Commands.literal("on").executes(context -> setRadar(context.getSource(), true)))
                                .then(Commands.literal("off").executes(context -> setRadar(context.getSource(), false))))
                        .then(Commands.literal("chat")
                                .then(chatLiteral("global", FactionChatMode.GLOBAL))
                                .then(chatLiteral("faction", FactionChatMode.FACTION))
                                .then(chatLiteral("focus", FactionChatMode.FOCUS))))
                .then(Commands.literal("chat")
                        .then(chatLiteral("global", FactionChatMode.GLOBAL))
                        .then(chatLiteral("faction", FactionChatMode.FACTION))
                        .then(chatLiteral("focus", FactionChatMode.FOCUS)))
                .then(Commands.literal("info")
                        .executes(context -> infoOwn(context.getSource()))
                        .then(Commands.argument("faction", StringArgumentType.greedyString())
                                .suggests(this::suggestFactionNames)
                                .executes(context -> info(context.getSource(),
                                        StringArgumentType.getString(context, "faction")))))
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("power").executes(context -> power(context.getSource())))
                .then(buildWarCommands())
                .then(buildAdminCommands()));
    }

    /** Executes a typed dashboard request without routing it through Brigadier or parsing command text. */
    public int handleUiAction(ServerPlayer player, FactionActionPayload payload) throws CommandSyntaxException {
        CommandSourceStack source = player.createCommandSourceStack().withSuppressedOutput();
        try {
            return switch (payload.action()) {
                case CREATE -> create(source, payload.primary());
                case JOIN -> join(source, payload.primary());
                case LEAVE -> leave(source);
                case DISBAND -> disband(source);
                case INVITE -> {
                    ServerPlayer target = onlinePlayer(source, payload.primary());
                    yield target == null ? fail(source, "That player must be online.") : invite(source, target);
                }
                case KICK -> {
                    ServerPlayer target = onlinePlayer(source, payload.primary());
                    yield target == null ? fail(source, "That player must be online.") : kick(source, target);
                }
                case SET_RANK -> {
                    ServerPlayer target = onlinePlayer(source, payload.primary());
                    if (target == null) yield fail(source, "That player must be online.");
                    FactionRank rank = FactionRank.valueOf(payload.secondary().toUpperCase(Locale.ROOT));
                    yield rank == FactionRank.OWNER
                            ? transferOwnership(source, target) : setRank(source, target, rank);
                }
                case DECLARE_RELATION -> setRelation(source, payload.primary(),
                        FactionRelation.valueOf(payload.secondary().toUpperCase(Locale.ROOT)));
                case SET_NAME -> setName(source, payload.primary());
                case SET_DESCRIPTION -> setDescription(source, payload.primary());
                case SET_COLOR -> setColor(source, payload.primary());
                case SET_TAG -> setTag(source, payload.primary());
                case SET_RADAR -> setRadar(source, payload.enabled());
                case SET_CHAT -> setChat(source,
                        FactionChatMode.valueOf(payload.primary().toUpperCase(Locale.ROOT)));
                case IMPORT_PREVIEW -> player.hasPermissions(3)
                        ? previewLegacyImport(source) : fail(source, "Operator permission is required.");
                case IMPORT_CONFIRM -> player.hasPermissions(3)
                        ? confirmLegacyImport(source) : fail(source, "Operator permission is required.");
                case ADMIN_GIVE_POWER -> adjustSpecialPower(player, source, payload, true);
                case ADMIN_REMOVE_POWER -> adjustSpecialPower(player, source, payload, false);
                default -> fail(source, "That action is not a faction-management action.");
            };
        } catch (IllegalArgumentException exception) {
            return fail(source, "The dashboard request contained an invalid option.");
        }
    }

    private int adjustSpecialPower(ServerPlayer player, CommandSourceStack source,
                                   FactionActionPayload payload, boolean give) {
        if (!player.hasPermissions(3)) return fail(source, "Operator permission is required.");
        UUID factionId = factions.factionByName(payload.primary().trim());
        if (factionId == null) return fail(source, "That faction does not exist.");
        int amount;
        try {
            amount = Integer.parseInt(payload.secondary().trim());
        } catch (NumberFormatException exception) {
            return fail(source, "Power adjustment must be a whole number.");
        }
        if (amount <= 0) return fail(source, "Power adjustment must be greater than zero.");
        factions.adjustSpecialPower(factionId, give ? amount : -amount);
        return success(source, (give ? "Gave " : "Removed ") + amount + " special power "
                + (give ? "to " : "from ") + factions.factionName(factionId) + ".");
    }

    private static ServerPlayer onlinePlayer(CommandSourceStack source, String name) {
        return source.getServer().getPlayerList().getPlayerByName(name.trim());
    }

    private LiteralArgumentBuilder<CommandSourceStack> rankLiteral(String name, FactionRank rank) {
        return Commands.literal(name).executes(context -> setRank(context.getSource(),
                EntityArgument.getPlayer(context, "player"), rank));
    }

    private LiteralArgumentBuilder<CommandSourceStack> relationLiteral(String name, FactionRelation relation) {
        return Commands.literal(name)
                .then(Commands.argument("faction", StringArgumentType.greedyString())
                        .suggests(this::suggestFactionNames)
                        .executes(context -> setRelation(context.getSource(),
                                StringArgumentType.getString(context, "faction"), relation)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> chatLiteral(String name, FactionChatMode mode) {
        return Commands.literal(name).executes(context -> setChat(context.getSource(), mode));
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildWarCommands() {
        LiteralArgumentBuilder<CommandSourceStack> declare = Commands.literal("declare");
        for (WarGoalType goal : WarGoalType.values()) {
            if (!goal.requiresWarCamp()) continue;
            declare.then(Commands.literal(goal.name().toLowerCase(Locale.ROOT))
                    .then(Commands.argument("faction", StringArgumentType.greedyString())
                            .suggests(this::suggestFactionNames)
                            .executes(context -> declareWar(context.getSource(), goal,
                                    StringArgumentType.getString(context, "faction")))));
        }
        LiteralArgumentBuilder<CommandSourceStack> choose = Commands.literal("goal");
        for (WarGoalType goal : WarGoalType.values()) {
            choose.then(Commands.literal(goal.name().toLowerCase(Locale.ROOT))
                    .then(Commands.argument("attacker", StringArgumentType.greedyString())
                            .suggests(this::suggestFactionNames)
                            .executes(context -> chooseWarGoal(context.getSource(), goal,
                                    StringArgumentType.getString(context, "attacker")))));
        }
        return Commands.literal("war")
                .executes(context -> listWars(context.getSource()))
                .then(Commands.literal("list").executes(context -> listWars(context.getSource())))
                .then(Commands.literal("camp")
                        .then(Commands.argument("opponent", StringArgumentType.greedyString())
                                .suggests(this::suggestFactionNames)
                                .executes(context -> selectWarCamp(context.getSource(),
                                        StringArgumentType.getString(context, "opponent")))))
                .then(Commands.literal("target")
                        .then(warTargetLiteral("add", true))
                        .then(warTargetLiteral("remove", false))
                        .then(Commands.literal("list")
                                .then(Commands.argument("opponent", StringArgumentType.greedyString())
                                        .suggests(this::suggestFactionNames)
                                        .executes(context -> listWarTargets(context.getSource(),
                                                StringArgumentType.getString(context, "opponent"))))))
                .then(declare)
                .then(choose);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildAdminCommands() {
        return Commands.literal("admin").requires(source -> source.hasPermission(3))
                .then(Commands.literal("war")
                        .then(Commands.literal("list")
                                .executes(context -> listAdminWars(context.getSource())))
                        .then(Commands.literal("end")
                                .then(Commands.argument("war", StringArgumentType.word())
                                        .suggests(this::suggestUnresolvedWarIds)
                                        .executes(context -> forceEndWar(context.getSource(),
                                                StringArgumentType.getString(context, "war"))))))
                .then(Commands.literal("faction")
                        .then(Commands.literal("disband")
                                .then(Commands.argument("faction", StringArgumentType.greedyString())
                                        .suggests(this::suggestFactionNames)
                                        .executes(context -> forceDisbandFaction(context.getSource(),
                                                StringArgumentType.getString(context, "faction"))))))
                .then(Commands.literal("importlegacy")
                        .then(Commands.literal("preview")
                                .executes(context -> previewLegacyImport(context.getSource())))
                        .then(Commands.literal("confirm")
                                .executes(context -> confirmLegacyImport(context.getSource()))));
    }

    private int listAdminWars(CommandSourceStack source) {
        List<WarSnapshot> entries = wars.allWars().stream()
                .filter(war -> war.state() != dev.terrafactions.war.WarState.ENDED)
                .toList();
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal("There are no unresolved wars."), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Unresolved wars (" + entries.size() + "):"), false);
        for (WarSnapshot war : entries) {
            source.sendSuccess(() -> Component.literal(shortId(war.id()) + " | " + war.state().name()
                    + " | " + factions.factionName(war.attackerFactionId()) + " vs "
                    + factions.factionName(war.defenderFactionId())), false);
        }
        return entries.size();
    }

    private LiteralArgumentBuilder<CommandSourceStack> warTargetLiteral(String name, boolean add) {
        return Commands.literal(name)
                .then(Commands.argument("opponent", StringArgumentType.greedyString())
                        .suggests(this::suggestFactionNames)
                        .executes(context -> changeWarTarget(context.getSource(),
                                StringArgumentType.getString(context, "opponent"), add)));
    }

    private int declareWar(CommandSourceStack source, WarGoalType goal, String defenderName)
            throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        UUID defenderId = factions.factionByName(defenderName.trim());
        if (defenderId == null) return fail(source, "No faction named " + defenderName.trim() + " exists.");
        try {
            WarGoalSnapshot selectedGoal = WarGoalSnapshot.selected(goal);
            if (usesAnchorTargets(goal)) {
                String target = lookedAtAnchor(source, defenderId);
                if (target == null) {
                    return fail(source, "Look directly at an enemy faction anchor before declaring "
                            + goal.name() + ".");
                }
                selectedGoal = selectedGoal.withTargets(java.util.Set.of(target));
            }
            WarSnapshot war = wars.declareWar(actor.id(), defenderId, selectedGoal);
            long seconds = Math.max(0L, war.preparationEndsAt() - war.declaredAt()) / 20L;
            return success(source, "Declared " + goal.name().toLowerCase(Locale.ROOT) + " war on "
                    + factions.factionName(defenderId) + ". Preparation lasts " + seconds + " seconds. War ID: "
                    + shortId(war.id()) + ".");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int chooseWarGoal(CommandSourceStack source, WarGoalType goal, String attackerName)
            throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        UUID attackerId = factions.factionByName(attackerName.trim());
        if (attackerId == null) return fail(source, "No faction named " + attackerName.trim() + " exists.");
        WarSnapshot war = wars.getWarBetweenFactions(actor.id(), attackerId);
        if (war == null) return fail(source, "There is no unresolved war between those factions.");
        try {
            WarGoalSnapshot selectedGoal = WarGoalSnapshot.selected(goal);
            if (usesAnchorTargets(goal)) {
                String target = lookedAtAnchor(source, attackerId);
                if (target != null) selectedGoal = selectedGoal.withTargets(java.util.Set.of(target));
            }
            wars.chooseDefenderGoal(war.id(), actor.id(), selectedGoal);
            return success(source, "Selected " + goal.name().toLowerCase(Locale.ROOT)
                    + " for war " + shortId(war.id()) + "."
                    + (usesAnchorTargets(goal) && selectedGoal.targetAnchorIds().isEmpty()
                    ? " Add at least one target before preparation ends." : ""));
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int listWars(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        List<WarSnapshot> entries = wars.getWarsForFaction(actor.id()).stream()
                .filter(war -> war.state() != dev.terrafactions.war.WarState.ENDED).toList();
        if (entries.isEmpty()) return success(source, "Your faction has no unresolved wars.");
        source.sendSuccess(() -> Component.literal("Wars (" + entries.size() + "):"), false);
        for (WarSnapshot war : entries) {
            WarSideSnapshotView view = warView(war, actor.id());
            source.sendSuccess(() -> Component.literal(shortId(war.id()) + " | " + war.state().name()
                    + " | vs " + factions.factionName(view.opponentId()) + " | Your goal: "
                    + goalName(view.ownGoal()) + " | Camp: " + campState(war.side(actor.id()))
                    + " | Occupied: " + wars.getOccupations(war.id(), actor.id()).size()
                    + " | Breaches: " + wars.getPlunderBreaches(war.id(), actor.id()).stream()
                    .filter(breach -> breach.active(source.getServer().overworld().getGameTime())).count()
                    + " | Enemy goal: " + goalName(view.enemyGoal())), false);
        }
        return entries.size();
    }

    private int selectWarCamp(CommandSourceStack source, String opponentName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity actor = requireLeadership(source);
        UUID opponentId = factions.factionByName(opponentName.trim());
        if (opponentId == null) return fail(source, "No faction named " + opponentName.trim() + " exists.");
        WarSnapshot war = wars.getWarBetweenFactions(actor.id(), opponentId);
        if (war == null) return fail(source, "There is no unresolved war between those factions.");
        try {
            wars.selectWarCampWar(player, actor.id(), war.id());
            return success(source, "The held War Camp is assigned against "
                    + factions.factionName(opponentId) + " (" + shortId(war.id()) + ").");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int forceEndWar(CommandSourceStack source, String reference) {
        String normalized = reference.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return fail(source, "A war ID or unique prefix is required.");
        List<WarSnapshot> matches = wars.allWars().stream()
                .filter(war -> war.state() != dev.terrafactions.war.WarState.ENDED)
                .filter(war -> war.id().toString().startsWith(normalized))
                .toList();
        if (matches.isEmpty()) return fail(source, "No unresolved war matches " + reference.trim() + ".");
        if (matches.size() > 1) return fail(source, "That war ID prefix is ambiguous; enter more characters.");

        WarSnapshot war = matches.getFirst();
        String attacker = factions.factionName(war.attackerFactionId());
        String defender = factions.factionName(war.defenderFactionId());
        wars.forceEndWar(war.id(), source.getServer().overworld().getGameTime(),
                "Force-ended by administrator " + source.getTextName());
        return success(source, "Force-ended war " + shortId(war.id()) + " between "
                + attacker + " and " + defender + ".");
    }

    private int forceDisbandFaction(CommandSourceStack source, String factionName) {
        String requested = factionName.trim();
        UUID factionId = factions.factionByName(requested);
        if (factionId == null) return fail(source, "No faction named " + requested + " exists.");
        String name = factions.factionName(factionId);
        long unresolvedWars = wars.getWarsForFaction(factionId).stream()
                .filter(war -> war.state() != dev.terrafactions.war.WarState.ENDED)
                .count();
        wars.cancelWarsForFaction(factionId, source.getServer().overworld().getGameTime(),
                "Faction force-disbanded by administrator " + source.getTextName());
        factions.disband(factionId);
        refreshDisplays.run();
        return success(source, "Force-disbanded " + name + " and cancelled " + unresolvedWars
                + " unresolved war" + (unresolvedWars == 1 ? "" : "s") + ".");
    }

    private int changeWarTarget(CommandSourceStack source, String opponentName, boolean add)
            throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        UUID opponentId = factions.factionByName(opponentName.trim());
        if (opponentId == null) return fail(source, "No faction named " + opponentName.trim() + " exists.");
        WarSnapshot war = wars.getWarBetweenFactions(actor.id(), opponentId);
        if (war == null) return fail(source, "There is no unresolved war between those factions.");
        String anchorId = lookedAtAnchor(source, opponentId);
        if (anchorId == null) return fail(source, "Look directly at one of that faction's anchors.");
        try {
            WarSnapshot updated = add
                    ? wars.addWarGoalTarget(war.id(), actor.id(), anchorId)
                    : wars.removeWarGoalTarget(war.id(), actor.id(), anchorId);
            WarGoalType goalType = updated.side(actor.id()).warGoal().type();
            return success(source, (add ? "Added" : "Removed") + " "
                    + goalType.name().toLowerCase(Locale.ROOT) + " target at "
                    + anchorPosition(anchorId) + ". Targets: "
                    + updated.side(actor.id()).warGoal().targetAnchorIds().size() + ".");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int listWarTargets(CommandSourceStack source, String opponentName)
            throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        UUID opponentId = factions.factionByName(opponentName.trim());
        if (opponentId == null) return fail(source, "No faction named " + opponentName.trim() + " exists.");
        WarSnapshot war = wars.getWarBetweenFactions(actor.id(), opponentId);
        if (war == null) return fail(source, "There is no unresolved war between those factions.");
        var goal = war.side(actor.id()).warGoal();
        if (goal == null || !usesAnchorTargets(goal.type())) {
            return fail(source, "Your faction's selected goal does not use anchor targets.");
        }
        String goalName = goal.type().name().toLowerCase(Locale.ROOT);
        if (goal.targetAnchorIds().isEmpty()) return success(source, "No " + goalName + " targets selected.");
        source.sendSuccess(() -> Component.literal(goalName + " targets (" + goal.progress() + "/"
                + goal.targetAnchorIds().size() + "):"), false);
        for (String target : goal.targetAnchorIds().stream().sorted().toList()) {
            boolean occupied = wars.getOccupation(target) != null
                    && actor.id().equals(wars.getOccupation(target).occupyingFactionId());
            boolean breached = goal.type() == WarGoalType.PLUNDER
                    && wars.getPlunderBreaches(war.id(), actor.id()).stream()
                    .anyMatch(breach -> breach.anchorId().equals(target));
            String state = breached ? "[breached] " : occupied ? "[occupied] " : "[open] ";
            source.sendSuccess(() -> Component.literal(state
                    + anchorPosition(target)), false);
        }
        return goal.targetAnchorIds().size();
    }

    private static boolean usesAnchorTargets(WarGoalType goal) {
        return goal == WarGoalType.CONQUEST || goal == WarGoalType.PLUNDER;
    }

    private String lookedAtAnchor(CommandSourceStack source, UUID expectedOwner) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(8.0D, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)
                || !(player.level().getBlockEntity(blockHit.getBlockPos()) instanceof FactionAnchorBlockEntity)) {
            return null;
        }
        String id = player.level().dimension().location() + "/" + blockHit.getBlockPos().asLong();
        AnchorMapSnapshot anchor = factions.anchor(id);
        return anchor != null && anchor.factionId().equals(expectedOwner) ? id : null;
    }

    private String anchorPosition(String anchorId) {
        AnchorMapSnapshot anchor = factions.anchor(anchorId);
        return anchor == null ? anchorId : anchor.x() + ", " + anchor.y() + ", " + anchor.z()
                + " (" + anchor.dimension() + ")";
    }

    private String campState(dev.terrafactions.war.WarSideSnapshot side) {
        if (side == null || side.warCampId() == null) return "not placed";
        dev.terrafactions.war.WarCampSnapshot camp = wars.getWarCamp(side.warCampId());
        return camp == null ? "missing" : camp.state().name().toLowerCase(Locale.ROOT);
    }

    private static WarSideSnapshotView warView(WarSnapshot war, UUID factionId) {
        boolean attacker = war.attackerFactionId().equals(factionId);
        return new WarSideSnapshotView(attacker ? war.defenderFactionId() : war.attackerFactionId(),
                attacker ? war.attacker().warGoal() : war.defender().warGoal(),
                attacker ? war.defender().warGoal() : war.attacker().warGoal());
    }

    private static String goalName(dev.terrafactions.war.WarGoalSnapshot goal) {
        if (goal == null) return "not selected";
        String name = goal.type().name().toLowerCase(Locale.ROOT);
        if (usesAnchorTargets(goal.type())) {
            return name + " " + goal.progress() + "/" + goal.targetAnchorIds().size();
        }
        return goal.type() == WarGoalType.PUNITIVE
                ? name + " " + goal.progress() + "/" + goal.requiredObjectiveValue() + " power"
                : name;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private record WarSideSnapshotView(UUID opponentId, dev.terrafactions.war.WarGoalSnapshot ownGoal,
                                       dev.terrafactions.war.WarGoalSnapshot enemyGoal) {
    }

    private CompletableFuture<Suggestions> suggestFactionNames(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                factions.allFactions().stream().map(FactionSnapshot::name), builder);
    }

    private CompletableFuture<Suggestions> suggestUnresolvedWarIds(
            com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(wars.allWars().stream()
                .filter(war -> war.state() != dev.terrafactions.war.WarState.ENDED)
                .map(war -> shortId(war.id())), builder);
    }

    private LiteralArgumentBuilder<CommandSourceStack> buildTagCommands() {
        return Commands.literal("tag")
                .executes(context -> showTag(context.getSource()))
                .then(Commands.literal("clear").executes(context -> clearTag(context.getSource())))
                .then(Commands.argument("tag", StringArgumentType.word())
                        .executes(context -> setTag(context.getSource(),
                                StringArgumentType.getString(context, "tag"))));
    }

    private int create(CommandSourceStack source, String requestedName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        String name = validName(requestedName);
        if (name == null) return fail(source, "Faction names must contain 1-32 visible characters.");
        try {
            long now = source.getServer().overworld().getGameTime();
            UUID factionId = factions.createFaction(player.getUUID(), name, now);
            refreshDisplays.run();
            long seconds = TerraFactionsConfig.FACTION_ANCHOR_PLACEMENT_DEADLINE_TICKS.get() / 20L;
            return success(source, "Created " + name + " with tag [" + factions.tag(factionId)
                    + "]. Place your first faction anchor within " + seconds + " seconds.");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int overview(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity identity = factions.factionForPlayer(player.getUUID());
        if (identity != null) return showInfo(source, identity.id());
        source.sendSuccess(() -> Component.literal("You are factionless [NF]."), false);
        source.sendSuccess(() -> Component.literal("Use /factions create <name>, /factions join <name>, or /factions list."), false);
        return 1;
    }

    private int disband(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity actor = factions.factionForPlayer(player.getUUID());
        if (actor == null) return fail(source, "You do not belong to a faction.");
        if (actor.rank() != FactionRank.OWNER) return fail(source, "Only the faction owner can disband it.");
        String name = factions.factionName(actor.id());
        wars.cancelWarsForFaction(actor.id(), source.getServer().overworld().getGameTime(),
                "Faction disbanded");
        factions.disband(actor.id());
        refreshDisplays.run();
        return success(source, "Disbanded " + name + ".");
    }

    private int invite(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        if (factions.factionForPlayer(target.getUUID()) != null) return fail(source, "That player already belongs to a faction.");
        factions.invite(actor.id(), target.getUUID());
        target.sendSystemMessage(Component.literal(source.getTextName() + " invited you to "
                + factions.factionName(actor.id()) + ". Use /factions join " + factions.factionName(actor.id()) + "."));
        return success(source, "Invited " + target.getGameProfile().getName() + ".");
    }

    private int join(CommandSourceStack source, String factionName) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        UUID factionId = factions.factionByName(factionName.trim());
        if (factionId == null) return fail(source, "No faction named " + factionName.trim() + " exists.");
        try {
            factions.join(factionId, player.getUUID());
            refreshDisplays.run();
            return success(source, "Joined " + factions.factionName(factionId) + ".");
        } catch (IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity actor = factions.factionForPlayer(player.getUUID());
        if (actor == null) return fail(source, "You do not belong to a faction.");
        try {
            factions.leave(player.getUUID());
            refreshDisplays.run();
            return success(source, "Left " + factions.factionName(actor.id()) + ". Chat was reset to global.");
        } catch (IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int kick(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        FactionIdentity targetIdentity = factions.factionForPlayer(target.getUUID());
        if (targetIdentity == null || !targetIdentity.id().equals(actor.id())) return fail(source, "That player is not in your faction.");
        if (targetIdentity.rank().ordinal() <= actor.rank().ordinal()) return fail(source, "You cannot kick a member of equal or higher rank.");
        factions.kick(actor.id(), target.getUUID());
        target.sendSystemMessage(Component.literal("You were removed from " + factions.factionName(actor.id())
                + ". Chat was reset to global."));
        refreshDisplays.run();
        return success(source, "Removed " + target.getGameProfile().getName() + " from the faction.");
    }

    private int setRank(CommandSourceStack source, ServerPlayer target, FactionRank rank) throws CommandSyntaxException {
        FactionIdentity actor = requireOwner(source);
        FactionIdentity targetIdentity = factions.factionForPlayer(target.getUUID());
        if (targetIdentity == null || !targetIdentity.id().equals(actor.id())) return fail(source, "That player is not in your faction.");
        if (targetIdentity.rank() == FactionRank.OWNER) return fail(source, "Transfer ownership before changing the owner's rank.");
        factions.setRank(actor.id(), target.getUUID(), rank);
        return success(source, "Set " + target.getGameProfile().getName() + " to " + rank.name().toLowerCase(Locale.ROOT) + ".");
    }

    private int transferOwnership(CommandSourceStack source, ServerPlayer target) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity actor = requireOwner(source);
        try {
            factions.transferOwnership(actor.id(), player.getUUID(), target.getUUID());
            return success(source, "Transferred faction ownership to " + target.getGameProfile().getName() + ".");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int setRelation(CommandSourceStack source, String targetName, FactionRelation relation)
            throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        UUID targetId = factions.factionByName(targetName.trim());
        if (targetId == null) return fail(source, "No faction named " + targetName.trim() + " exists.");
        try {
            factions.setRelation(actor.id(), targetId, relation);
            refreshDisplays.run();
            String suffix = relation == FactionRelation.ALLIED
                    ? " (allied status begins when they also declare ally)." : ".";
            return success(source, "Declared " + factions.factionName(targetId) + " "
                    + relation.name().toLowerCase(Locale.ROOT) + suffix);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int setName(CommandSourceStack source, String requestedName) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        String name = validName(requestedName);
        if (name == null) return fail(source, "Faction names must contain 1-32 visible characters.");
        UUID existing = factions.factionByName(name);
        if (existing != null && !existing.equals(actor.id())) return fail(source, "A faction with that name already exists.");
        factions.setName(actor.id(), name);
        refreshDisplays.run();
        return success(source, "Renamed your faction to " + name + ".");
    }

    private int setDescription(CommandSourceStack source, String description) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        String value = description.trim();
        if (value.length() > MAX_DESCRIPTION_LENGTH) return fail(source, "Descriptions can be at most 256 characters.");
        factions.setDescription(actor.id(), value);
        return success(source, "Updated your faction description.");
    }

    private int setColor(CommandSourceStack source, String requestedColor) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        Integer color = parseColor(requestedColor);
        if (color == null) return fail(source, "Use a named Minecraft color or a six-digit RGB value such as #3A7BD5.");
        factions.setColor(actor.id(), color);
        refreshDisplays.run();
        return success(source, "Updated your faction color.");
    }

    private int showTag(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        return success(source, "Your faction tag is [" + factions.tag(actor.id()) + "].");
    }

    private int setTag(CommandSourceStack source, String requestedTag) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        String tag = requestedTag.toUpperCase(Locale.ROOT);
        if (!tag.matches("[A-Z0-9_]{1,4}")) return fail(source, "Faction tags must be 1-4 letters, numbers, or underscores.");
        factions.setTag(actor.id(), tag);
        refreshDisplays.run();
        return success(source, "Set your faction tag to [" + tag + "].");
    }

    private int clearTag(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireLeadership(source);
        String tag = FactionTags.defaultFor(factions.factionName(actor.id()), actor.id());
        factions.setTag(actor.id(), tag);
        refreshDisplays.run();
        return success(source, "Reset your faction tag to [" + tag + "].");
    }

    private int toggleRadar(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        return setRadar(source, !factions.radarEnabled(player.getUUID()));
    }

    private int setRadar(CommandSourceStack source, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        factions.setRadarEnabled(player.getUUID(), enabled);
        return success(source, "Territory radar " + (enabled ? "enabled." : "disabled."));
    }

    private int setChat(CommandSourceStack source, FactionChatMode mode) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        try {
            factions.setChatMode(player.getUUID(), mode);
            return success(source, "Chat mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".");
        } catch (IllegalStateException exception) {
            return fail(source, exception.getMessage());
        }
    }

    private int infoOwn(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        return showInfo(source, actor.id());
    }

    private int info(CommandSourceStack source, String name) {
        UUID factionId = factions.factionByName(name.trim());
        return factionId == null ? fail(source, "No faction named " + name.trim() + " exists.") : showInfo(source, factionId);
    }

    private int showInfo(CommandSourceStack source, UUID factionId) {
        FactionSnapshot snapshot = factions.snapshot(factionId);
        FactionPower power = factions.power(factionId);
        MutableComponent header = Component.literal("[" + factions.tag(factionId) + "] " + snapshot.name())
                .withStyle(Style.EMPTY.withColor(snapshot.color()));
        source.sendSuccess(() -> header, false);
        if (!snapshot.description().isBlank()) source.sendSuccess(() -> Component.literal(snapshot.description()), false);
        source.sendSuccess(() -> Component.literal("Members: " + factions.members(factionId).size()
                + " | Available power: " + power.current() + "/" + power.maximum()
                + " | Claims: " + snapshot.claims().size() + " (" + power.claimUsage() + " power)"
                + " | Death loss: " + power.deathLoss()), false);
        if (power.specialPower() != 0) source.sendSuccess(() -> Component.literal(
                "Special " + (power.specialPower() > 0 ? "addition: +" : "subtraction: -")
                        + Math.abs((long) power.specialPower())), false);
        if (power.temporaryPower() > 0) source.sendSuccess(() -> Component.literal(
                "Conquest Integration Power: +" + power.temporaryPower() + " (decaying)"), false);
        showPunitiveSuppression(source, power);
        showVulnerability(source, factionId, "Core", TerritoryType.CORE);
        showVulnerability(source, factionId, "Border", TerritoryType.BORDER);
        showDeathLosses(source, power);
        return 1;
    }

    private void showVulnerability(CommandSourceStack source, UUID factionId, String label, TerritoryType type) {
        boolean vulnerable = vulnerability.test(factionId, type);
        source.sendSuccess(() -> Component.literal(label + ": " + (vulnerable ? "VULNERABLE" : "Secure"))
                .withStyle(vulnerable ? ChatFormatting.RED : ChatFormatting.GREEN), false);
    }

    private int list(CommandSourceStack source) {
        var entries = factions.allFactions().stream()
                .sorted(Comparator.comparing(FactionSnapshot::name, String.CASE_INSENSITIVE_ORDER)).toList();
        if (entries.isEmpty()) return success(source, "There are no factions yet.");
        source.sendSuccess(() -> Component.literal("Factions (" + entries.size() + "):"), false);
        entries.forEach(faction -> source.sendSuccess(() -> Component.literal("[" + factions.tag(faction.id()) + "] "
                + faction.name() + " — " + factions.members(faction.id()).size() + " members")
                .withStyle(Style.EMPTY.withColor(faction.color())), false));
        return entries.size();
    }

    private int power(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        FactionPower power = factions.power(actor.id());
        source.sendSuccess(() -> Component.literal("Faction power: " + power.current() + "/" + power.maximum()
                + " available | " + power.claimUsage() + " used by claims | " + power.deathLoss()
                + " lost to deaths."), false);
        if (power.specialPower() != 0) source.sendSuccess(() -> Component.literal(
                "Special " + (power.specialPower() > 0 ? "addition: +" : "subtraction: -")
                        + Math.abs((long) power.specialPower())), false);
        if (power.temporaryPower() > 0) source.sendSuccess(() -> Component.literal(
                "Conquest Integration Power: +" + power.temporaryPower() + " (decaying)"), false);
        showPunitiveSuppression(source, power);
        showDeathLosses(source, power);
        return 1;
    }

    private static void showPunitiveSuppression(CommandSourceStack source, FactionPower power) {
        if (power.suppressedPower() <= 0) return;
        source.sendSuccess(() -> Component.literal("Punitive suppression: "
                + Math.round(power.suppressionPercent() * 100.0D) + "% (-"
                + power.suppressedPower() + " power)"), false);
    }

    private void showDeathLosses(CommandSourceStack source, FactionPower power) {
        power.deathLossByPlayer().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(java.util.Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .forEach(entry -> source.sendSuccess(() -> Component.literal("  "
                        + playerName(source, entry.getKey()) + ": -" + entry.getValue()), false));
    }

    private static String playerName(CommandSourceStack source, UUID playerId) {
        ServerPlayer online = source.getServer().getPlayerList().getPlayer(playerId);
        if (online != null) return online.getGameProfile().getName();
        return source.getServer().getProfileCache().get(playerId)
                .map(profile -> profile.getName()).orElse(playerId.toString());
    }

    private int previewLegacyImport(CommandSourceStack source) {
        try {
            var legacy = legacyImporter.read(source.getServer());
            source.sendSuccess(() -> Component.literal("Legacy import preview: " + legacy.summary() + "."), false);
            source.sendSuccess(() -> Component.literal("Shared source: " + legacy.sharedSource()), false);
            source.sendSuccess(() -> Component.literal("Current-world border source: " + legacy.borderSource()), false);
            if (!factions.isEmpty()) {
                source.sendFailure(Component.literal("Import is blocked because this world already has native TerraFactions data."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Review the counts, then run /factions admin importlegacy confirm."), false);
            return 1;
        } catch (IOException exception) {
            return fail(source, "Could not read legacy data: " + exception.getMessage());
        }
    }

    private int confirmLegacyImport(CommandSourceStack source) {
        if (!factions.isEmpty()) return fail(source, "Import refused: this world already has native TerraFactions data.");
        try {
            var legacy = legacyImporter.read(source.getServer());
            if (legacy.factions().isEmpty()) return fail(source, "No legacy factions were found.");
            factions.importLegacy(legacy);
            refreshDisplays.run();
            return success(source, "Imported " + legacy.summary() + " into this world.");
        } catch (IOException | IllegalStateException exception) {
            return fail(source, "Legacy import failed: " + exception.getMessage());
        }
    }

    private FactionIdentity requireMember(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        FactionIdentity actor = factions.factionForPlayer(player.getUUID());
        if (actor == null) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                .create("You do not belong to a faction.");
        return actor;
    }

    private FactionIdentity requireLeadership(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        if (!actor.rank().isLeadership()) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                .create("Faction leadership is required.");
        return actor;
    }

    private FactionIdentity requireOwner(CommandSourceStack source) throws CommandSyntaxException {
        FactionIdentity actor = requireMember(source);
        if (actor.rank() != FactionRank.OWNER) throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherParseException()
                .create("Only the faction owner can do that.");
        return actor;
    }

    private static String validName(String requested) {
        String name = requested.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) return null;
        if (name.equalsIgnoreCase("wilderness") || name.equalsIgnoreCase("factionless")) return null;
        return name;
    }

    private static Integer parseColor(String value) {
        String normalized = value.trim();
        String hex = normalized.startsWith("#") ? normalized.substring(1) : normalized;
        if (hex.matches("(?i)[0-9a-f]{6}")) return Integer.parseInt(hex, 16);
        ChatFormatting formatting = ChatFormatting.getByName(normalized.toLowerCase(Locale.ROOT));
        return formatting == null ? null : formatting.getColor();
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
