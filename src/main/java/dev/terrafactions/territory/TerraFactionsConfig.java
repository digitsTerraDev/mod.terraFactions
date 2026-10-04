package dev.terrafactions.territory;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.EnumMap;

public final class TerraFactionsConfig {
    private static final EnumMap<ProtectionAction, ModConfigSpec.EnumValue<ProtectionPolicy>> CORE_PROTECTION_POLICIES =
            new EnumMap<>(ProtectionAction.class);
    private static final EnumMap<ProtectionAction, ModConfigSpec.EnumValue<ProtectionPolicy>> BORDER_PROTECTION_POLICIES =
            new EnumMap<>(ProtectionAction.class);
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue CORE_CLAIM_COST;
    public static final ModConfigSpec.IntValue BORDER_CLAIM_COST;
    public static final ModConfigSpec.DoubleValue BORDER_VULNERABILITY_PERCENT;
    public static final ModConfigSpec.BooleanValue REQUIRE_SIDE_CONNECTIVITY;
    public static final ModConfigSpec.IntValue JOURNEYMAP_CLAIM_RADIUS;
    public static final ModConfigSpec.BooleanValue JOURNEYMAP_FACTION_ONLY_PLAYER_RADAR;
    public static final ModConfigSpec.IntValue MAX_ANCHOR_POWER;
    public static final ModConfigSpec.IntValue ANCHOR_RECALCULATION_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue ANCHOR_DISCONNECTION_GRACE_TICKS;
    public static final ModConfigSpec.IntValue ANCHOR_MINIMUM_Y;
    public static final ModConfigSpec.EnumValue<ProtectionPolicy> OUTSIDE_PVP_POLICY;
    public static final ModConfigSpec.IntValue CORE_SIEGE_DAMAGE_REQUIRED;
    public static final ModConfigSpec.IntValue CORE_SIEGE_RADIUS_BLOCKS;
    public static final ModConfigSpec.IntValue FACTION_ANCHOR_PLACEMENT_DEADLINE_TICKS;
    public static final ModConfigSpec.IntValue BASE_POWER;
    public static final ModConfigSpec.IntValue POWER_PER_MEMBER;
    public static final ModConfigSpec.IntValue DEATH_POWER_PENALTY;
    public static final ModConfigSpec.IntValue POWER_REGEN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue POWER_REGEN_AMOUNT;
    public static final ModConfigSpec.IntValue WAR_PREPARATION_DURATION_MINUTES;
    public static final ModConfigSpec.BooleanValue WAR_WINDOWS_ENABLED;
    public static final ModConfigSpec.IntValue DEFAULT_WAR_WINDOW_START_UTC_MINUTE;
    public static final ModConfigSpec.IntValue DEFAULT_WAR_WINDOW_DURATION_MINUTES;
    public static final ModConfigSpec.IntValue WAR_POWER_DRAIN_INTERVAL_TICKS;
    public static final ModConfigSpec.IntValue WAR_POWER_DRAIN_AMOUNT;
    public static final ModConfigSpec.IntValue WAR_CAMP_ESTABLISHMENT_DURATION_TICKS;
    public static final ModConfigSpec.IntValue WAR_CAMP_PLACEMENT_DEADLINE_TICKS;
    public static final ModConfigSpec.IntValue WAR_MAX_DURATION_TICKS;
    public static final ModConfigSpec.IntValue WAR_CAMP_DESTRUCTION_INTERACTIONS;
    public static final ModConfigSpec.IntValue ANCHOR_OCCUPATION_RANGE_CHUNKS;
    public static final ModConfigSpec.IntValue ANCHOR_SIEGE_INTERACTIONS;
    public static final ModConfigSpec.IntValue INTEGRATION_POWER_DECAY_DURATION_TICKS;
    public static final ModConfigSpec.IntValue PLUNDER_BREACH_DURATION_TICKS;
    public static final ModConfigSpec.BooleanValue PLUNDER_ALLOW_BLOCK_BREAKING;
    public static final ModConfigSpec.BooleanValue PLUNDER_ALLOW_BLOCK_PLACEMENT;
    public static final ModConfigSpec.BooleanValue PLUNDER_ALLOW_INTERACTIONS;
    public static final ModConfigSpec.IntValue PUNITIVE_REQUIRED_ANCHOR_POWER;
    public static final ModConfigSpec.IntValue PUNITIVE_SUPPRESSION_DURATION_TICKS;
    public static final ModConfigSpec.DoubleValue PUNITIVE_SUPPRESSION_PERCENT;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("power");
        BASE_POWER = builder.comment("Base maximum power available to every faction.")
                .defineInRange("basePower", 20, 0, Integer.MAX_VALUE);
        POWER_PER_MEMBER = builder.comment("Additional maximum power supplied by each faction member.")
                .defineInRange("powerPerMember", 20, 0, Integer.MAX_VALUE);
        DEATH_POWER_PENALTY = builder.comment("Power lost whenever a faction member dies in PvP.")
                .defineInRange("deathPowerPenalty", 10, 0, Integer.MAX_VALUE);
        POWER_REGEN_INTERVAL_TICKS = builder.comment("Ticks between faction power regeneration pulses.")
                .defineInRange("regenerationIntervalTicks", 12000, 1, Integer.MAX_VALUE);
        POWER_REGEN_AMOUNT = builder.comment("Power restored to each outstanding player-attributed loss at every regeneration pulse.")
                .defineInRange("regenerationAmount", 1, 0, Integer.MAX_VALUE);
        CORE_CLAIM_COST = builder.comment("Power consumed by one capital or core claim.")
                .defineInRange("coreClaimCost", 10, 1, Integer.MAX_VALUE);
        BORDER_CLAIM_COST = builder.comment("Power consumed by one border claim.")
                .defineInRange("borderClaimCost", 1, 1, Integer.MAX_VALUE);
        BORDER_VULNERABILITY_PERCENT = builder.comment("Border claims can be attacked at or below this fraction of maximum faction power.")
                .defineInRange("borderVulnerabilityPercent", 0.30D, 0.0D, 1.0D);
        builder.pop();
        builder.push("claims");
        REQUIRE_SIDE_CONNECTIVITY = builder.comment("Require new claims to share a cardinal edge with existing territory in that dimension.")
                .define("requireSideConnectivity", true);
        JOURNEYMAP_CLAIM_RADIUS = builder.comment("Maximum chunk radius in which players can claim territory from JourneyMap.")
                .defineInRange("journeyMapClaimRadius", 3, 0, Integer.MAX_VALUE);
        JOURNEYMAP_FACTION_ONLY_PLAYER_RADAR = builder.comment(
                        "Hide players from each other's JourneyMap radar unless they belong to the same faction.")
                .define("journeyMapFactionOnlyPlayerRadar", true);
        MAX_ANCHOR_POWER = builder.comment("Maximum power that can be allocated to one faction anchor.")
                .defineInRange("maximumAnchorPower", 1000, 0, Integer.MAX_VALUE);
        ANCHOR_RECALCULATION_INTERVAL_TICKS = builder.comment(
                        "Ticks between periodic anchor border reconciliations. Overlapping projections are awarded by effective anchor power and distance.")
                .defineInRange("anchorRecalculationIntervalTicks", 200, 1, Integer.MAX_VALUE);
        ANCHOR_DISCONNECTION_GRACE_TICKS = builder.comment(
                        "Time an exposed anchor stays protected and pending after losing its connection to the Capital Anchor.")
                .defineInRange("anchorDisconnectionGraceTicks", 12000, 0, Integer.MAX_VALUE);
        ANCHOR_MINIMUM_Y = builder.comment(
                        "Y offset from a dimension's sea level required for an anchor to activate (0 means sea level).")
                .defineInRange("anchorMinimumYAboveSeaLevel", 0, -128, 320);
        CORE_SIEGE_DAMAGE_REQUIRED = builder.comment(
                        "Explosion damage required to breach a fractured Capital Anchor.")
                .defineInRange("coreSiegeDamageRequired", 100, 1, Integer.MAX_VALUE);
        CORE_SIEGE_RADIUS_BLOCKS = builder.comment(
                        "Maximum distance from the Capital Anchor at which an explosion applies siege damage.")
                .defineInRange("coreSiegeRadiusBlocks", 32, 1, 256);
        FACTION_ANCHOR_PLACEMENT_DEADLINE_TICKS = builder.comment(
                        "Ticks a newly created faction has to place its first anchor before being automatically disbanded.")
                .defineInRange("factionAnchorPlacementDeadlineTicks", 12000, 20, Integer.MAX_VALUE);
        builder.pop();
        builder.push("protections");
        OUTSIDE_PVP_POLICY = builder.comment(
                        "Controls PvP in unclaimed territory. FORCED_ON is the default: faction protections never disable wilderness PvP.")
                .defineEnum("outsidePvp", ProtectionPolicy.FORCED_ON);
        defineProtectionPolicies(builder, "core", CORE_PROTECTION_POLICIES);
        defineProtectionPolicies(builder, "border", BORDER_PROTECTION_POLICIES);
        builder.pop();
        builder.push("war");
        WAR_PREPARATION_DURATION_MINUTES = builder.comment(
                        "Real-world minutes between declaring a formal war and it becoming eligible to start.")
                .defineInRange("preparationDurationMinutes", 10, 0, 10080);
        WAR_WINDOWS_ENABLED = builder.comment(
                        "When true, formal wars begin only in the defender's configured UTC daily window."
                                + " When false, a war begins as soon as its preparation period ends.")
                .define("warWindowsEnabled", true);
        DEFAULT_WAR_WINDOW_START_UTC_MINUTE = builder.comment(
                        "Default UTC minute of day at which a faction's daily war window begins (0-1439).")
                .defineInRange("defaultWindowStartUtcMinute", 720, 0, 1439);
        DEFAULT_WAR_WINDOW_DURATION_MINUTES = builder.comment(
                        "Default real-world duration, in minutes, of a faction's daily UTC war window.")
                .defineInRange("defaultWindowDurationMinutes", 360, 1, 1440);
        WAR_POWER_DRAIN_INTERVAL_TICKS = builder.comment(
                        "Ticks between Active Power pressure pulses during an active formal war.")
                .defineInRange("powerDrainIntervalTicks", 1200, 20, Integer.MAX_VALUE);
        WAR_POWER_DRAIN_AMOUNT = builder.comment(
                        "Active Power removed from the defending faction per formal-war pressure pulse.")
                .defineInRange("powerDrainAmount", 2, 0, Integer.MAX_VALUE);
        WAR_CAMP_ESTABLISHMENT_DURATION_TICKS = builder.comment(
                        "Ticks a placed War Camp spends establishing before it becomes active.")
                .defineInRange("warCampEstablishmentDurationTicks", 12000, 0, Integer.MAX_VALUE);
        WAR_CAMP_PLACEMENT_DEADLINE_TICKS = builder.comment(
                        "Ticks after a war starts that an offensive side has to place its War Camp before forfeiting its goal.")
                .defineInRange("warCampPlacementDeadlineTicks", 12000, 20, Integer.MAX_VALUE);
        WAR_MAX_DURATION_TICKS = builder.comment(
                        "Maximum ticks an active war may last before all unfinished goals fail and the war resolves.")
                .defineInRange("maximumWarDurationTicks", 1728000, 20, Integer.MAX_VALUE);
        WAR_CAMP_DESTRUCTION_INTERACTIONS = builder.comment(
                        "Successful enemy sabotage interactions required to destroy a War Camp.")
                .defineInRange("warCampDestructionInteractions", 10, 1, Integer.MAX_VALUE);
        ANCHOR_OCCUPATION_RANGE_CHUNKS = builder.comment(
                        "Extra chunk gap allowed between a War Camp or occupied-anchor projection and the next anchor.")
                .defineInRange("anchorOccupationRangeChunks", 3, 0, Integer.MAX_VALUE);
        ANCHOR_SIEGE_INTERACTIONS = builder.comment(
                        "One-second faction-presence pulses required to occupy or liberate an anchor.")
                .defineInRange("anchorSiegeInteractions", 20, 1, Integer.MAX_VALUE);
        INTEGRATION_POWER_DECAY_DURATION_TICKS = builder.comment(
                        "Ticks for conquest Integration Power to decay linearly to zero.")
                .defineInRange("integrationPowerDecayDurationTicks", 1728000, 1, Integer.MAX_VALUE);
        PLUNDER_BREACH_DURATION_TICKS = builder.comment(
                        "Ticks that a successfully plundered anchor region remains breached.")
                .defineInRange("plunderBreachDurationTicks", 36000, 1, Integer.MAX_VALUE);
        PLUNDER_ALLOW_BLOCK_BREAKING = builder.comment(
                        "Allow the breaching faction to break ordinary blocks inside active Plunder regions.")
                .define("plunderAllowBlockBreaking", true);
        PLUNDER_ALLOW_BLOCK_PLACEMENT = builder.comment(
                        "Allow the breaching faction to place blocks inside active Plunder regions.")
                .define("plunderAllowBlockPlacement", true);
        PLUNDER_ALLOW_INTERACTIONS = builder.comment(
                        "Allow the breaching faction to use containers, machines, and non-player entities in active Plunder regions.")
                .define("plunderAllowInteractions", true);
        PUNITIVE_REQUIRED_ANCHOR_POWER = builder.comment(
                        "Total allocated Power of enemy anchors that must be occupied to complete a Punitive goal.")
                .defineInRange("punitiveRequiredAnchorPower", 300, 1, Integer.MAX_VALUE);
        PUNITIVE_SUPPRESSION_DURATION_TICKS = builder.comment(
                        "Ticks that a victorious Punitive suppression remains active (default: about three days at 20 TPS).")
                .defineInRange("punitiveSuppressionDurationTicks", 5184000, 1, Integer.MAX_VALUE);
        PUNITIVE_SUPPRESSION_PERCENT = builder.comment(
                        "Fraction of permanent faction Power suppressed after a Punitive victory.")
                .defineInRange("punitiveSuppressionPercent", 0.20D, 0.0D, 1.0D);
        builder.pop();
        SPEC = builder.build();
    }

    public static ProtectionPolicy protectionPolicy(TerritoryType type, ProtectionAction action) {
        return (type == TerritoryType.BORDER ? BORDER_PROTECTION_POLICIES : CORE_PROTECTION_POLICIES)
                .get(action).get();
    }

    private static void defineProtectionPolicies(
            ModConfigSpec.Builder builder, String territory,
            EnumMap<ProtectionAction, ModConfigSpec.EnumValue<ProtectionPolicy>> destination) {
        builder.push(territory);
        for (ProtectionAction action : ProtectionAction.values()) {
            destination.put(action, builder.comment(
                            "FACTION_CONTROLLED lets faction leadership toggle this protection; "
                                    + "FORCED_ON and FORCED_OFF override every faction.")
                    .defineEnum(action.configKey(), ProtectionPolicy.FACTION_CONTROLLED));
        }
        builder.pop();
    }

    private TerraFactionsConfig() {
    }
}
