# ExcellentCrates 6.6.1 to 7.2.0 Converter (v1.0.14)

This is a Paper addon. On first startup it translates supported 6.6.1 crate, key, reward, rarity, and opening-profile data into the 7.2.0 rewrite layout before ExcellentCrates loads its files. If v7 data already exists, automatic conversion leaves it untouched; use `/ecv6convert` to add definitions that are missing.

Requires Paper 1.21 or newer and ExcellentCrates 7.2.0.

## Install

1. Stop the server and make sure the 6.6.1 plugin jar is removed.
2. Put ExcellentCrates 7.2.0 and `ExcellentCrates-V6Converter.jar` in `plugins/`.
3. Start the server and review the `ExcellentCratesV6Converter` console summary.

The converter writes crate, key, reward, and rarity definitions under `plugins/ExcellentCrates/objects/`. For a clean v6 install it moves the complete old `plugins/ExcellentCrates` folder to a sibling folder named `ExcellentCrates-v6.6.1-backup-<timestamp>`, then publishes the converted folder at `plugins/ExcellentCrates`. It writes a completion marker so later starts do not repeat the migration. If it finds existing v7 config or object folders, automatic migration stops without moving any files; the manual recovery command can then merge missing definitions.

If a previous converter run created the completion marker but v7 could not load the result, this version retries from the newest recognizable `ExcellentCrates-v6.6.1-backup-*` folder. It preserves the previous converted folder as `ExcellentCrates-v7-previous-conversion-*` before publishing the repaired conversion. Stop the server, replace the converter JAR, and start it again; keep both backup folders until you confirm the server loads and the data is correct.

Version 1.0.3 fixes copying nested Bukkit YAML sections in legacy reward files. This preserves `ItemsData` when reading each reward and writes its item stacks into v7's `items.item_list`; map and list variants are also accepted. The conversion report now warns when an item reward has no readable legacy item entries.

Version 1.0.4 uses the first converted given item as an item reward's display icon when the legacy reward does not use a custom preview. It also inherits that item's name and lore, matching v6's default preview behavior. Rewards with custom previews retain their legacy `PreviewData` icon.

Version 1.0.5 checks custom item providers used by converted crate, key, reward, and preview items. It checks plugin presence during conversion, then checks known provider plugins again after startup finishes. The recognized v7 provider dependencies are ItemsAdder, Oraxen, Nexo, MMOItems, ExecutableItems, CraftEngine, and ExcellentCrates. Missing or disabled plugins and unrecognized provider IDs are reported in the console and `conversion-report.txt`, with affected definitions listed.

Version 1.0.6 also checks that NightCore can load the ItemsAdder API class `dev.lone.itemsadder.api.CustomStack`, which its ItemsAdder item integration uses. If the class is unavailable, the converter reports an API compatibility warning and lists the affected custom items instead of marking the provider ready.

Version 1.0.7 converts legacy Bukkit world names to the namespaced world keys used by v7. It resolves loaded worlds directly (including renamed overworlds and custom worlds), maps the standard `world`, `world_nether`, and `world_the_end` names when a world is unavailable, and reports placements that required a fallback. It now runs after worlds load and declares a Paper plugin load-order dependency so it still converts before ExcellentCrates.

Version 1.0.8 introduced conversion of v6 crate-preview settings and preview GUI layouts. Version 1.0.11 removes GUI layout conversion so it cannot override or conflict with the v7 built-in default profile. The release also converts v6 Simple Roll opening profiles and links crates to their v7 animation profiles. Other legacy opening styles have no direct profile equivalent and are left disabled with a review notice. Both `Crate.Holograms.TemplateList` and the older `Crate.Holograms.Templates` shape are read; text for templates used by crates is embedded into each crate, and the hologram refresh interval is converted from seconds to ticks. V7 has no global hologram template registry, so unused templates and unsupported global visual options stay in the backup.

Version 1.0.9 uses a default hologram Y offset of `0.7` when the v6 crate did not explicitly configure one, while preserving configured offsets. It converts v6 block-effect models that exist in v7 (`beacon`, `helix`, `pulsar`, `sphere`, `spiral`, and `vortex`) into per-crate v7 effect profiles, including compatible particle colors and timing. Unsupported effect models are disabled and reported. Particle types whose data cannot be read safely by v7 are changed to `FLAME` and reported; the original settings remain in the backup. Simple Roll remains the only shipped v6 opening animation with a direct v7 equivalent. V7's CSGO animation and legacy custom inventory/selectable openings use different presentation and behavior, so they are not silently substituted.

Version 1.0.10 converts supported v6 key costs into v7 `key_requirements` and explicitly enables the component. Active key entries must refer to keys that were successfully converted. When an active currency, custom, combined, or invalid cost has no safe v7 mapping, the converter creates a per-crate virtual migration-lock key and enables it as the crate's requirement. That crate cannot be opened by ordinary players until an administrator configures a supported cost and removes the migration-lock requirement. If a crate has both supported key choices and unsupported cost options, the supported choices stay enabled and the omitted options are reported. The console summary and `conversion-report.txt` list every locked crate; original cost settings remain in the backup.

Version 1.0.11 removes legacy preview GUI conversion. Converted crates keep their v6 preview-enabled setting and use `inventory:default`, leaving the built-in v7.2.0 default preview profile untouched. Legacy preview GUI files stay in the backup and are reported for review.

Version 1.0.12 resolves reward IDs that collide after v7 normalization. It assigns deterministic suffixes to the additional rewards and reads `openings.log` when available, keeping the most frequently logged legacy reward ID as the unsuffixed ID. The console summary and conversion report show how many collisions were resolved and how many collision groups matched the log.

Version 1.0.13 adds `/ecv6convert` (alias `/ecconvert`, permission `excellentcratesv6converter.convert`, default operator). It searches the current ExcellentCrates data folder and timestamped v6 backups, adds missing crate, key, reward, rarity, animation, effect, and supported settings files, and preserves existing v7 definitions. When it adds rewards to an existing crate, it keeps the current crate settings and appends links only for newly recovered reward files. Existing crate files that need edits are copied to `ExcellentCrates-v7-manual-backup-<timestamp>` first. Reward IDs that conflict with an existing unlinked v7 reward receive a unique suffix. The command reports results in chat and console and appends details to `plugins/ExcellentCrates/conversion-report.txt`. It does not move legacy data or import SQLite balances while v7 is active. Restart the server after the command so ExcellentCrates loads recovered files.

Version 1.0.13 preserves the Paper dependency order that loads this converter before ExcellentCrates, so automatic conversion can finish before v7 reads its files.

Version 1.0.14 adds a confirmed force mode: `/ecv6convert -f` displays the changes and a clickable confirmation button; `/ecv6convert -f confirm` runs it directly after the same terms have been presented. Force mode backs up and replaces existing crate, key, and reward definition files. It disables legacy previews, animations, block effects, and holograms; drops unsupported opening-count limits and reward limits/cooldowns/rarity/preview metadata; and defaults malformed settings or removes malformed item entries. An unreadable crate YAML becomes a safe, key-locked crate with no rewards. Legacy files are kept. It records a manual recovery marker so a timestamped v6 backup is not automatically replayed over the recovered v7 files on later starts. The report counts replaced files, disabled features, defaults, and failures.

## Converted data

- Crate display, item, permissions, post-open commands, linked block positions, holograms, supported block-effect profiles, crate cooldowns, enabled supported key costs, preview enablement using the built-in v7 default profile, supported Simple Roll animation references, and reward references. Unsupported active costs receive an enabled per-crate virtual-key lock so the crate cannot silently become free. Crates without a legacy hologram Y offset use `0.7`; explicit offsets are preserved. Legacy `crate key ...` commands are changed to `cratekey ...`. Block positions include the v7 pitch and yaw fields, set to `0` for legacy block locations.
- Key display, item, virtual flag, and stackability.
- Item and command rewards, unique reward IDs, preview details, rarity links, broadcast settings, limits, and cooldown settings. When IDs collide after normalization, the resolver checks `openings.log` and keeps the most frequently referenced legacy ID unsuffixed; additional rewards receive deterministic numeric suffixes. Item rewards are written with v7 item providers and data. Legacy compressed items are decoded from their NBT compound without building a Bukkit ItemStack. Unknown `swing_animation` and `map_color` entries are removed only from the `hidden_components` tooltip list, where they otherwise prevent v7 from loading the stack.
- Legacy rarities.
- Simple Roll opening profiles and compatible block-effect profiles, where their fields have a v7 equivalent. Legacy preview GUI layouts are retained in the backup; crates with previews enabled use the v7 built-in default profile.
- Player virtual and unclaimed key balances from one local SQLite database, when a SQLite JDBC driver is available from a loaded server plugin. The target table is `key_storage` in `data.db`.

The console lists each converted definition, reward item stack counts (converted and failed/skipped), duplicate reward ID collisions resolved, cost requirements enabled, crates locked for cost review, and any data that needs review. `conversion-report.txt` is also written into the new ExcellentCrates data folder.

## Manual recovery after installing v7

Install the converter beside ExcellentCrates 7.2.0, start the server, then run `/ecv6convert` as an operator or a user with `excellentcratesv6converter.convert`. The command searches `plugins/ExcellentCrates/crates/`, `plugins/ExcellentCrates/keys/`, and sibling folders named `ExcellentCrates-v6.6.1-backup-*`. It checks the current data folder first, then the newest backup folders.

The default command fills missing definitions without replacing existing v7 files. If the regular conversion cannot produce usable definitions, run `/ecv6convert -f`. It will show the force terms; in game, click **Confirm force conversion**, or run `/ecv6convert -f confirm` after reviewing those terms. Force mode backs up each existing crate, key, or reward file before replacing it. It disables old previews, opening animations, block effects, and holograms; omits opening-count limits; disables reward limits, cooldowns, rarity, and preview metadata; and applies safe defaults to malformed settings. A crate whose YAML cannot be read becomes an editor-ready crate locked behind a virtual review key, with no rewards. The v6 files remain in place.

Both modes report files added, replaced, preserved, or skipped in chat/console and `conversion-report.txt`. A manual-recovery marker prevents a timestamped backup from being automatically replayed over the result on later starts; the command can still be run again. If the old files are no longer present in either location, restore the v6 `crates/` and `keys/` directories into `plugins/ExcellentCrates/` or restore the timestamped v6 backup, then run the command again. Restart the server after a successful recovery.

## PlaceholderAPI placeholders in v7.2.0

The PlaceholderAPI expansion identifier is `excellentcrates`. Crate placeholders use `%excellentcrates_crate_<token>_<crateId>%`; key placeholders use `%excellentcrates_key_<token>_<keyId>%`.

| Placeholder | Meaning |
| --- | --- |
| `%excellentcrates_crate_id_<crateId>%` | Crate identifier. |
| `%excellentcrates_crate_name_<crateId>%` | Crate display name. |
| `%excellentcrates_crate_description_<crateId>%` | Crate display lore, joined with line breaks. |
| `%excellentcrates_crate_effective_cooldown_<crateId>%` | For a player, the time remaining on their effective crate cooldown; returns the configured ready/never text when ready or permanent. Requires a player context. |
| `%excellentcrates_crate_initial_global_cooldown_<crateId>%` | Configured global cooldown duration. Only available when that cooldown is enabled. |
| `%excellentcrates_crate_initial_individual_cooldown_<crateId>%` | Configured individual cooldown duration. Only available when that cooldown is enabled. |
| `%excellentcrates_crate_total_rewards_weight_<crateId>%` | Sum of the weights of the crate's rewards. |
| `%excellentcrates_key_id_<keyId>%` | Key identifier. |
| `%excellentcrates_key_name_<keyId>%` | Key display name. |
| `%excellentcrates_key_lore_<keyId>%` | Key display lore, joined with line breaks. |
| `%excellentcrates_key_balance_<keyId>%` | The player's balance of that key. Requires a player context. |

The tokens shown in the tables correspond to internal placeholders such as `%crate_id%`, `%crate_name%`, `%crate_description%`, `%key_id%`, `%key_name%`, `%key_lore%`, and `%key_balance%`; these are not additional standalone PlaceholderAPI tokens. The v6.6.1 `%excellentcrates_cooldown_<crateId>%` token is rewritten to `%excellentcrates_crate_effective_cooldown_<crateId>%`. Other old PAPI tokens have no direct v7 equivalent, remain unchanged, and are listed as review warnings in the console/report.

The v7 resolver separates the placeholder token from the crate or key ID at the final underscore. IDs containing underscores may therefore fail to resolve through these PAPI forms; the converter reports this when it rewrites a cooldown placeholder with an underscore in its crate ID.

## Preserved for manual migration

The complete 6.6.1 folder remains in the timestamped backup. Some settings have no direct counterpart in the rewrite branch or need an external provider to interpret. In particular, remote MySQL data, legacy reward history, crate milestones, permission filters, currency/custom crate costs, legacy preview GUI layouts, non-Simple-Roll opening styles, and unsupported block-effect models or particle data are not imported. Active costs without a safe built-in mapping are guarded by an enabled virtual-key lock until reviewed. Their old files and database remain in the backup, and the converter reports supported omissions.

## Build from source

Requires Java 21 or newer and Maven. From this directory, run `mvn package`. The Paper API is a provided dependency and is not bundled into the addon jar.

