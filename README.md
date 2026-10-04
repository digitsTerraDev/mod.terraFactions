# TerraFactions

TerraFactions is a standalone factions mod for NeoForge 1.21.1. It owns its faction, member, relationship, power, and territory data per Minecraft save. The Fabric Factions mod and Sinytra Connector are not used.

[JourneyMap](https://modrinth.com/mod/journeymap) integration is optional and uses its native NeoForge API.

## Factions

Create and manage factions with `/factions` (also available as `/tf` and `/terrafactions`):

```text
/factions create <name>
/factions invite <player>
/factions join <name>
/factions leave
/factions kick <player>
/factions disband
/factions rank <player> <leader|commander|member|guest|owner>
/factions declare <ally|neutral|enemy> <faction>
/factions info [faction]
/factions list
/factions power
```

Faction owners begin at the configured maximum power. Maximum power is the configured base plus the configured amount per member. Player deaths reduce power and online members regenerate their attributed death loss over time. `/factions power` shows available power, claim usage, total death loss, and the players responsible for that loss. These values are configurable in each world's `serverconfig/terrafactions-server.toml`.

## Capital, core, and border territory

Every claimed chunk is exactly one of three types:

- Capital is the faction's single first claim. It uses the core cost and protection rules.
- Core costs 10 power by default. Outsiders cannot modify or use blocks, or interact with or attack non-player entities.
- Border costs 1 power by default. Blocks remain protected, but outsiders can use containers and kill or interact with entities.

Faction members except guests can build in their own territory. Mutual allies receive the same block permission. Explosions cannot destroy claimed blocks. Territory never blocks player-versus-player attacks, so PvP remains symmetric.

```text
/factions claim core
/factions claim core <size>
/factions claim border
/factions claim border <size>
/factions unclaim
/factions convert core
/factions convert border
/factions liberate
/factions capital set
```

New claims must share a cardinal edge with existing faction territory in that dimension, and voluntary unclaims cannot split it. A bulk size of 2 claims a centered 3x3 square, size 3 claims 5x5, and so on; the operation is all-or-nothing. The first claim becomes the faction capital. Leadership can move the capital only to a core chunk, which converts the old capital back to core. Move the capital before unclaiming or converting it.

Border claims become vulnerable at or below 30% of maximum faction power by default. Core and capital become vulnerable at zero available power. `/factions info` shows the current secure/vulnerable status for core and border claims. Claiming an adjacent vulnerable enemy chunk automatically captures it as the requested type. `liberate` removes vulnerable enemy territory instead.

Claim costs reduce the faction's available power:

```text
available power = maximum power - death losses - capital/core costs - border costs
```

## Tags, chat, and radar

Every faction automatically gets an uppercase tag from the first four letters or numbers in its name. Tags are at most four letters, numbers, or underscores. Factionless players show `[NF]`.

```text
/factions modify name <name>
/factions modify description <description>
/factions modify color <named color|RRGGBB>
/factions modify tag <tag>
/factions modify tag clear
/factions settings chat <global|faction|focus>
/factions settings radar [on|off]
```

Only the tag is relation-colored in chat and above a player's head: blue for your own faction, green for mutual allies, red for enemies, and gray for neutral or factionless players. Faction-channel lines are additionally prefixed with `[Faction]`. Leaving, being kicked, or disbanding automatically resets faction/focus chat to global. Faction-name arguments provide tab completion.

Press `G` (rebindable under Controls) or run `/factionsui` to open the TerraLib faction dashboard. It presents live faction identity and power, a scrollable member roster, claim and vulnerability summaries, a relation-aware faction directory, editable identity forms, and personal settings. Management controls are shown only when the player's faction rank permits them. The dashboard uses typed network actions handled directly by the server, where normal permission and territory validation remains in force.

The persistent top-left TerraLib-styled radar displays the owning faction and `Capital`, `Core`, or `Border` on one line, or `Wilderness` when unclaimed, colored blue for your own faction, green for mutual allies, red for enemies, or gray for neutral factions and wilderness. A red/yellow flashing accent marks vulnerable territory.

The same radar panel includes a compact own-faction row with `P: current/max` power followed by Border and Core security indicators.

The client config can show or hide the radar or its own-faction row, set its scale, opacity, percentage-based screen position and anchor, and disable vulnerability flashing. `/factions settings radar [on|off]` remains the per-player server-side radar toggle. Radar defaults to enabled for new players.

## JourneyMap

When JourneyMap for NeoForge is installed, adjacent claims of the same faction and type are merged into exterior polygons. Core uses a 38% fill and border uses an 18% fill. Only the capital carries the faction name label. Vulnerable areas flash between bright and dim red outlines once per second.

The JourneyMap fullscreen UI has a **Factions: On/Off** button. The same preference can be changed with `/factions overlay on` and `/factions overlay off`.

## Importing the old compatibility data

Import is never automatic because the former Fabric Factions files were shared by every save. An operator can inspect what would be imported into the current world, then explicitly confirm it:

```text
/factions admin importlegacy preview
/factions admin importlegacy confirm
```

The importer combines the shared legacy factions, users, and core claims with the current world's old TerraFactions border claims, capital, and tags. It refuses to run after the current world contains any native faction data.

## Development

## KubeJS anchor recipe

TerraFactions ships no anchor or war-camp recipes. Install KubeJS for NeoForge and copy the included `kubejs` directory into
the root of the server or modpack instance. The supplied
`kubejs/server_scripts/terrafactions_anchors.js` creates the basic, advanced,
master, and ultimate faction anchors plus the war camp, and is the intended place
to change recipes.
See `kubejs/examples/terrafactions_new_anchors.js.example` for copyable custom
and bulk-anchor recipe examples.

Use JDK 21:

```shell
./gradlew runClient
./gradlew test
./gradlew build
```

To launch directly into a development save, use `./gradlew -PquickWorld="Save Name" runClient`.

The generated IntelliJ client run uses `net.neoforged.devlaunch.Main`; Gradle supplies its VM and program argument files.
