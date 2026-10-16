# Mace Evolution — Кара Світів

Fabric mod for Minecraft Java Edition **1.21.11**, Java 21.

- One mace with 20 levels; level and kill count are stored as item data components.
- PvP progression counts only player deaths attributed to the mace.
- Per-level item-model dispatch, custom-name/lore progression and enchantment glint.
- Combat effects include speed, haste, resistance, slowness, weakness, glowing, fire, blindness, levitation, freezing, wither and particles.
- Level 12 visual 3×3 flame effect does not place fire blocks; level 14 freezes targets; level 20 adds powerful debuffs.
- `/maceevolution give` gives a fresh mace (permission level 2).

Build with Java 21 and `gradle build`; output is in `build/libs/`.

The supplied progression table sums to **63 kills**, not 62. Automatic player bans are not included; bans remain an explicit moderator decision.
