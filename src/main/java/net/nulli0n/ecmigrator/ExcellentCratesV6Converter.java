package net.nulli0n.ecmigrator;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converts v6 data during startup or through the operator recovery command.
 * Startup conversion stages a complete v7 directory; manual recovery merges
 * definitions into the existing v7 data folder and preserves backups.
 */
public final class ExcellentCratesV6Converter extends JavaPlugin {

    private static final String SOURCE_FOLDER = "ExcellentCrates";
    private static final String MARKER = ".excellentcrates-v6-to-v7.complete";
    private static final String MANUAL_REPLAY_GUARD = ".excellentcrates-v6-to-v7.manual-recovery";
    private static final String MANUAL_COMMAND_PERMISSION = "excellentcratesv6converter.convert";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final Pattern JSON_INT_ENTRY = Pattern.compile("\"((?:\\\\.|[^\"\\\\])+)\"\\s*:\\s*(-?\\d+)");
    private static final Pattern UUID_PATTERN = Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern HIDDEN_COMPONENTS = Pattern.compile("hidden_components\\s*:\\s*\\[([^\\]]*)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEGACY_KEY_COMMAND = Pattern.compile("^(\\s*/?)(?:crate|crates|case|cases|excellentcrates|ec)\\s+key(?=\\s|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern OPENING_LOG_REWARD = Pattern.compile("\\bwon\\s+.*?\\(ID:\\s*([^,()]+),\\s*Weight:\\s*[^)]*\\)\\s+from\\s+.*?\\(ID:\\s*([^)]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final List<String> LEGACY_PAPI_PLACEHOLDERS = List.of(
        "openings_available", "openings_remaining", "next_milestone_openings", "next_milestone_reward",
        "latest_opener", "latest_rolled_reward", "openings_raw", "openings", "cooldown", "keys"
    );
    private static final Map<String, String> ITEM_PROVIDER_PLUGINS = Map.of(
        "executableitems", "ExecutableItems",
        "excellent_crates", "ExcellentCrates",
        "itemsadder", "ItemsAdder",
        "mmoitems", "MMOItems",
        "nexo", "Nexo",
        "oraxen", "Oraxen",
        "craftengine", "CraftEngine"
    );
    private static final int LEGACY_ITEM_DATA_VERSION = 3955; // Minecraft 1.21.1, used by ExcellentCrates 6.6.1.
    private static final List<String> V7_CONFIG_MARKERS = List.of(
        "engine.core.yml", "engine.database.yml", "crates.core.yml", "keys.core.yml", "rewards.core.yml"
    );

    private final List<String> successes = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private final Set<String> reportedPlaceholderWarnings = new LinkedHashSet<>();
    private final Set<String> reportedWorldWarnings = new LinkedHashSet<>();
    private final Set<String> convertedSimpleRollProfiles = new LinkedHashSet<>();
    private final Set<String> convertedBlockEffectProfiles = new LinkedHashSet<>();
    private final Set<String> referencedHologramTemplates = new LinkedHashSet<>();
    private final Set<String> cratesLockedForCostReview = new LinkedHashSet<>();
    private final Map<String, Set<String>> itemProviderUses = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> openingLogRewardIdCounts = new LinkedHashMap<>();
    private final Set<String> invalidManualKeyFiles = new LinkedHashSet<>();

    private Path sourceRoot;
    private Path targetRoot;
    private Path stageRoot;
    private Path backupRoot;
    private Path previousConvertedRoot;
    private boolean retryingFromBackup;
    private YamlConfiguration oldGlobalConfig;
    private int cratesConverted;
    private int keysConverted;
    private int rewardsConverted;
    private int raritiesConverted;
    private int blockPositionsConverted;
    private int keyBalancesConverted;
    private int rewardItemsConverted;
    private int rewardItemsFailed;
    private int rewardPreviewIconsFromItems;
    private int tooltipComponentsRemoved;
    private int openingProfilesConverted;
    private int blockEffectProfilesConverted;
    private int blockEffectProfilesSkipped;
    private int blockEffectParticleFallbacks;
    private int hologramTemplatesApplied;
    private int legacyKeyCommandsRewritten;
    private int legacyCooldownPlaceholdersRewritten;
    private int crateCostRequirementsEnabled;
    private int costReviewLockKeysCreated;
    private int openingLogRewardReferencesIndexed;
    private int rewardIdCollisionsResolved;
    private int rewardIdCollisionGroupsMatchedToOpeningLog;
    private boolean manualMode;
    private boolean manualForceMode;
    private boolean manualConversionRunning;
    private final Set<String> forceProcessedCrateIds = new LinkedHashSet<>();
    private final Set<String> forceProcessedKeyIds = new LinkedHashSet<>();
    private final Set<String> forceFallbackCrateIds = new LinkedHashSet<>();
    private final Set<String> currentSourceFallbackCrateIds = new LinkedHashSet<>();
    private Path manualBackupRoot;
    private int manualCratesAdded;
    private int manualCratesUpdated;
    private int manualKeysAdded;
    private int manualKeysUpdated;
    private int manualRewardsAdded;
    private int manualRewardsUpdated;
    private int manualSupportingFilesAdded;
    private int manualSupportingFilesUpdated;
    private int manualDefinitionsSkipped;
    private int manualFeaturesDisabled;
    private int manualSafeDefaultsApplied;

    @Override
    public void onEnable() {
        registerManualCommand();
        try {
            this.runMigration();
        }
        catch (Exception exception) {
            getLogger().log(Level.SEVERE, "Conversion stopped: " + exception.getMessage(), exception);
            if (this.stageRoot != null) deleteTreeQuietly(this.stageRoot);
        }
    }

    private void registerManualCommand() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        if (pluginManager.getPermission(MANUAL_COMMAND_PERMISSION) == null) {
            pluginManager.addPermission(new Permission(MANUAL_COMMAND_PERMISSION, PermissionDefault.OP));
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register("ecv6convert", List.of("ecconvert"), new BasicCommand() {
                @Override
                public String permission() {
                    return MANUAL_COMMAND_PERMISSION;
                }

                @Override
                public void execute(CommandSourceStack source, String[] args) {
                    CommandSender sender = source.getSender();
                    if (args.length == 0) {
                        ExcellentCratesV6Converter.this.runManualRecovery(sender, false);
                        return;
                    }
                    if (args.length == 1 && ("-f".equalsIgnoreCase(args[0]) || "--force".equalsIgnoreCase(args[0]))) {
                        ExcellentCratesV6Converter.this.showForceConfirmation(sender);
                        return;
                    }
                    if (args.length == 2 && ("-f".equalsIgnoreCase(args[0]) || "--force".equalsIgnoreCase(args[0]))
                        && "confirm".equalsIgnoreCase(args[1])) {
                        ExcellentCratesV6Converter.this.runManualRecovery(sender, true);
                        return;
                    }
                    sender.sendMessage("Usage: /ecv6convert [-f [confirm]]");
                }
            })
        );
    }

    private void showForceConfirmation(CommandSender sender) {
        sender.sendMessage("Force recovery can replace existing v7 crate, key, and reward files. Existing files are backed up, but custom v7 edits in those files will be replaced.");
        sender.sendMessage("It disables legacy previews, opening animations, block effects, and holograms; drops unsupported opening-count limits; clears reward preview, rarity, limit, and cooldown settings; and removes malformed item entries. Legacy v6 files remain in place.");
        sender.sendMessage("A crate whose source YAML cannot be read becomes a safe, key-locked crate with no rewards so it can still be opened in the v7 editor.");
        sender.sendMessage("It records this manual choice so the converter will not automatically replay the legacy backup over these changes on future starts.");
        sender.sendMessage("Afterward, review conversion-report.txt and restart the server. Confirm only if you accept these changes.");
        if (sender instanceof Player player) {
            Component confirm = Component.text("[Confirm force conversion]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/ecv6convert -f confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("Back up and replace conflicting v7 definitions with sanitized conversions.")));
            player.sendMessage(confirm);
        }
        else {
            sender.sendMessage("To confirm from console, run: /ecv6convert -f confirm");
        }
    }

    private void runMigration() throws IOException {
        Path pluginsDir = getDataFolder().toPath().toAbsolutePath().getParent();
        if (pluginsDir == null) {
            getLogger().severe("Could not determine the server plugins folder. No files were changed.");
            return;
        }

        this.targetRoot = pluginsDir.resolve(SOURCE_FOLDER);
        this.sourceRoot = this.targetRoot;
        if (!Files.isDirectory(this.targetRoot)) {
            getLogger().info("No plugins/ExcellentCrates data folder was found; nothing to convert.");
            return;
        }

        Path marker = this.targetRoot.resolve(MARKER);
        if (Files.exists(marker)) {
            if (Files.exists(this.targetRoot.resolve(MANUAL_REPLAY_GUARD))) {
                getLogger().info("A manual recovery was explicitly applied. Preserving those v7 files; the legacy backup will not be replayed automatically.");
                return;
            }
            Path legacyBackup = findLatestLegacyBackup(pluginsDir, marker);
            if (legacyBackup == null) {
                getLogger().info("ExcellentCrates data was already converted and no legacy backup was found. No files were changed. If legacy crate or key files remain, run /ecv6convert after startup to recover missing definitions.");
                return;
            }
            this.sourceRoot = legacyBackup;
            this.backupRoot = legacyBackup;
            this.retryingFromBackup = true;
            this.successes.add("retrying conversion from preserved v6.6.1 backup " + legacyBackup.getFileName());
        }

        for (String targetConfig : V7_CONFIG_MARKERS) {
            Path targetFile = this.sourceRoot.resolve("config").resolve(targetConfig);
            if (Files.exists(targetFile)) {
                getLogger().warning("Found existing v7 settings file '" + targetFile.getFileName()
                    + "' in config/. Automatic conversion left existing data untouched. Run /ecv6convert after startup to recover missing crate and key definitions.");
                return;
            }
        }
        for (String objectType : List.of("crate", "key", "reward", "rarity", "effects")) {
            Path targetObjects = this.sourceRoot.resolve("objects").resolve(objectType);
            if (Files.isDirectory(targetObjects)) {
                getLogger().warning("Found an existing v7 objects/" + objectType
                    + "/ folder. Automatic conversion left existing data untouched. Run /ecv6convert after startup to recover missing crate and key definitions.");
                return;
            }
        }

        List<Path> oldCrateFiles = topLevelYamlFiles(this.sourceRoot.resolve("crates"));
        List<Path> oldKeyFiles = allYamlFiles(this.sourceRoot.resolve("keys"));
        boolean hasConfig = Files.isRegularFile(this.sourceRoot.resolve("config.yml"));
        boolean hasOldRarity = Files.isRegularFile(this.sourceRoot.resolve("rarity.yml"));
        if (!hasConfig && !hasOldRarity && oldCrateFiles.isEmpty() && oldKeyFiles.isEmpty()) {
            getLogger().info("No recognizable ExcellentCrates 6.x files were found; nothing to convert.");
            return;
        }

        this.oldGlobalConfig = hasConfig ? loadYaml(this.sourceRoot.resolve("config.yml"), "config.yml") : new YamlConfiguration();
        if (hasConfig) {
            this.warnings.add("Legacy global settings without a direct v7 equivalent remain in the backup. Referenced hologram text and the hologram update interval are converted where possible.");
        }
        this.stageRoot = Files.createTempDirectory(pluginsDir, ".ExcellentCrates-v7-stage-");
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("crate"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("key"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("reward"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("rarity"));

        reportLegacyPreviewLayouts();
        convertSimpleRollProfiles();
        reportUnsupportedOpeningProfiles();

        Set<String> crateIds = new LinkedHashSet<>();
        Map<String, String> keyIds = new LinkedHashMap<>();
        loadOpeningLogRewardIds();

        for (Path file : oldKeyFiles) {
            try {
                String id = safeId(fileBase(file));
                if (keyIds.containsValue(id)) throw new IOException("Key ID collision after normalization: " + id);
                YamlConfiguration source = loadYaml(file, file.toString());
                YamlConfiguration target = convertKey(source);
                saveYaml(target, this.stageRoot.resolve("objects").resolve("key").resolve(id + ".yml"));
                keyIds.put(fileBase(file).toLowerCase(Locale.ROOT), id);
                this.keysConverted++;
                this.successes.add("key " + fileBase(file) + " -> " + id);
            }
            catch (Exception exception) {
                this.failures.add("key " + file + ": " + concise(exception));
            }
        }

        for (Path file : oldCrateFiles) {
            try {
                String id = safeId(fileBase(file));
                if (crateIds.contains(id)) throw new IOException("Crate ID collision after normalization: " + id);
                YamlConfiguration source = loadYaml(file, file.toString());
                YamlConfiguration target = convertCrate(source, id, keyIds);
                this.rewardsConverted += convertRewards(source, id, target);
                saveYaml(target, this.stageRoot.resolve("objects").resolve("crate").resolve(id + ".yml"));
                crateIds.add(id);
                this.cratesConverted++;
                this.successes.add("crate " + fileBase(file) + " -> " + id);
            }
            catch (Exception exception) {
                try {
                    String failedId = safeId(fileBase(file));
                    if (!crateIds.contains(failedId)) deleteTreeQuietly(this.stageRoot.resolve("objects").resolve("reward").resolve(failedId));
                }
                catch (IllegalArgumentException ignored) { }
                this.failures.add("crate " + file + ": " + concise(exception));
            }
        }

        this.raritiesConverted = convertRarities();
        convertHologramSettings();
        if (!this.failures.isEmpty()) {
            this.warnings.add(this.failures.size() + " legacy definition file(s) could not be converted; their originals remain in the backup.");
        }

        if (this.cratesConverted + this.keysConverted == 0
            && (!oldCrateFiles.isEmpty() || !oldKeyFiles.isEmpty() || (!hasConfig && !hasOldRarity))) {
            deleteTreeQuietly(this.stageRoot);
            this.stageRoot = null;
            getLogger().severe("No crate or key definitions could be converted. The v6 folder was not moved.");
            printReport(null);
            return;
        }

        DatabaseResult databaseResult = migrateSqliteKeyBalances(this.sourceRoot, this.stageRoot);
        if (databaseResult.importedRows > 0) {
            this.keyBalancesConverted = databaseResult.importedRows;
            this.successes.add("imported " + databaseResult.importedRows + " player key balance row(s) from " + databaseResult.sourceName);
        }
        if (databaseResult.warning != null) this.warnings.add(databaseResult.warning);
        checkItemProviderPlugins();

        if (this.retryingFromBackup) {
            this.previousConvertedRoot = uniqueConvertedBackupPath(pluginsDir);
        }
        else {
            this.backupRoot = uniqueBackupPath(pluginsDir);
        }
        writeStatusFiles(crateIds.size(), keyIds.size());
        if (this.retryingFromBackup) {
            movePath(this.targetRoot, this.previousConvertedRoot);
        }
        else {
            movePath(this.sourceRoot, this.backupRoot);
        }
        try {
            movePath(this.stageRoot, this.targetRoot);
            this.stageRoot = null;
        }
        catch (IOException publishFailure) {
            if (!Files.exists(this.targetRoot)) {
                if (this.retryingFromBackup && Files.exists(this.previousConvertedRoot)) {
                    movePath(this.previousConvertedRoot, this.targetRoot);
                    this.previousConvertedRoot = null;
                }
                else if (!this.retryingFromBackup && Files.exists(this.backupRoot)) {
                    movePath(this.backupRoot, this.targetRoot);
                    this.backupRoot = null;
                }
            }
            throw new IOException("Could not publish converted files; original data was restored when possible", publishFailure);
        }

        printReport(this.backupRoot);
        scheduleFinalItemProviderCheck();
    }

    private void runManualRecovery(CommandSender sender, boolean force) {
        if (this.manualConversionRunning) {
            sender.sendMessage("[ExcellentCratesV6Converter] A manual recovery is already running.");
            return;
        }
        Plugin excellentCrates = Bukkit.getPluginManager().getPlugin("ExcellentCrates");
        if (excellentCrates == null || !excellentCrates.isEnabled()) {
            sender.sendMessage("[ExcellentCratesV6Converter] ExcellentCrates must be installed and enabled before recovery.");
            return;
        }

        Path pluginsDir = getDataFolder().toPath().toAbsolutePath().getParent();
        if (pluginsDir == null) {
            sender.sendMessage("[ExcellentCratesV6Converter] Could not find the plugins folder.");
            return;
        }
        this.targetRoot = pluginsDir.resolve(SOURCE_FOLDER);
        if (!Files.isDirectory(this.targetRoot)) {
            sender.sendMessage("[ExcellentCratesV6Converter] plugins/ExcellentCrates was not found; no files were changed.");
            return;
        }

        this.manualConversionRunning = true;
        this.resetManualRunState();
        this.manualMode = true;
        this.manualForceMode = force;
        this.manualBackupRoot = uniquePath(pluginsDir,
            SOURCE_FOLDER + "-v7-manual-backup-" + LocalDateTime.now().format(STAMP));
        Path manualStageRoot = null;
        try {
            List<Path> sources = findManualSources(pluginsDir);
            if (sources.isEmpty()) {
                sender.sendMessage("[ExcellentCratesV6Converter] No legacy crate or key YAML files were found in ExcellentCrates or its v6 backups. Restore the v6 files there and run this command again.");
                this.warnings.add("No legacy crate or key YAML files were found in the current ExcellentCrates folder or timestamped v6 backups.");
                this.writeManualReport();
                return;
            }

            this.writeManualReplayGuard(force);
            this.successes.add("recorded the manual recovery choice so the legacy backup is not automatically replayed on future starts");
            Map<String, String> keyIds = collectLegacyKeyIds(sources);
            manualStageRoot = Files.createTempDirectory(pluginsDir, ".ExcellentCrates-v7-manual-stage-");
            sender.sendMessage("[ExcellentCratesV6Converter] Scanning " + sources.size()
                + " legacy data folder(s). " + (force
                    ? "Force mode will back up and replace conflicting v7 definitions."
                    : "Existing v7 files will be preserved."));
            this.successes.add("manual recovery sources: " + sources.stream()
                .map(path -> path.getFileName().toString()).toList());

            int index = 0;
            for (Path source : sources) {
                Path sourceStage = manualStageRoot.resolve("source-" + (++index));
                try {
                    this.convertManualSource(source, sourceStage, keyIds);
                }
                catch (Exception exception) {
                    this.failures.add("source " + source.getFileName() + ": " + concise(exception));
                    getLogger().log(Level.SEVERE, "Manual recovery could not fully process " + source, exception);
                }
                finally {
                    if (this.stageRoot != null) deleteTreeQuietly(this.stageRoot);
                    this.stageRoot = null;
                }
            }

            this.checkItemProviderPlugins();
            this.writeManualReport();
            this.printManualReport(sender);
            this.scheduleFinalItemProviderCheck();
        }
        catch (Exception exception) {
            this.failures.add("manual recovery: " + concise(exception));
            getLogger().log(Level.SEVERE, "Manual recovery stopped: " + exception.getMessage(), exception);
            sender.sendMessage("[ExcellentCratesV6Converter] Recovery stopped: " + concise(exception)
                + ". See the server console and conversion-report.txt.");
            try { this.writeManualReport(); }
            catch (IOException reportException) {
                getLogger().warning("Could not write conversion-report.txt: " + concise(reportException));
            }
        }
        finally {
            if (manualStageRoot != null) deleteTreeQuietly(manualStageRoot);
            this.stageRoot = null;
            this.sourceRoot = null;
            this.manualMode = false;
            this.manualForceMode = false;
            this.manualConversionRunning = false;
        }
    }

    private void resetManualRunState() {
        this.successes.clear();
        this.warnings.clear();
        this.failures.clear();
        this.reportedPlaceholderWarnings.clear();
        this.reportedWorldWarnings.clear();
        this.convertedSimpleRollProfiles.clear();
        this.convertedBlockEffectProfiles.clear();
        this.referencedHologramTemplates.clear();
        this.cratesLockedForCostReview.clear();
        this.itemProviderUses.clear();
        this.openingLogRewardIdCounts.clear();
        this.invalidManualKeyFiles.clear();
        this.cratesConverted = 0;
        this.keysConverted = 0;
        this.rewardsConverted = 0;
        this.raritiesConverted = 0;
        this.blockPositionsConverted = 0;
        this.keyBalancesConverted = 0;
        this.rewardItemsConverted = 0;
        this.rewardItemsFailed = 0;
        this.rewardPreviewIconsFromItems = 0;
        this.tooltipComponentsRemoved = 0;
        this.openingProfilesConverted = 0;
        this.blockEffectProfilesConverted = 0;
        this.blockEffectProfilesSkipped = 0;
        this.blockEffectParticleFallbacks = 0;
        this.hologramTemplatesApplied = 0;
        this.legacyKeyCommandsRewritten = 0;
        this.legacyCooldownPlaceholdersRewritten = 0;
        this.crateCostRequirementsEnabled = 0;
        this.costReviewLockKeysCreated = 0;
        this.openingLogRewardReferencesIndexed = 0;
        this.rewardIdCollisionsResolved = 0;
        this.rewardIdCollisionGroupsMatchedToOpeningLog = 0;
        this.manualCratesAdded = 0;
        this.manualCratesUpdated = 0;
        this.manualKeysAdded = 0;
        this.manualKeysUpdated = 0;
        this.manualRewardsAdded = 0;
        this.manualRewardsUpdated = 0;
        this.manualSupportingFilesAdded = 0;
        this.manualSupportingFilesUpdated = 0;
        this.manualDefinitionsSkipped = 0;
        this.manualFeaturesDisabled = 0;
        this.manualSafeDefaultsApplied = 0;
        this.forceProcessedCrateIds.clear();
        this.forceProcessedKeyIds.clear();
        this.forceFallbackCrateIds.clear();
        this.currentSourceFallbackCrateIds.clear();
        this.manualBackupRoot = null;
        this.retryingFromBackup = false;
        this.backupRoot = null;
        this.previousConvertedRoot = null;
    }

    private List<Path> findManualSources(Path pluginsDir) throws IOException {
        List<Path> candidates = new ArrayList<>();
        candidates.add(this.targetRoot);
        try (Stream<Path> paths = Files.list(pluginsDir)) {
            candidates.addAll(paths.filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith(SOURCE_FOLDER + "-v6.6.1-backup-"))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList());
        }

        List<Path> sources = new ArrayList<>();
        for (Path candidate : candidates) {
            if (!Files.isDirectory(candidate)) continue;
            if (!topLevelYamlFiles(candidate.resolve("crates")).isEmpty()
                || !allYamlFiles(candidate.resolve("keys")).isEmpty()) {
                sources.add(candidate);
            }
        }
        return sources;
    }

    private Map<String, String> collectLegacyKeyIds(List<Path> sources) throws IOException {
        Map<String, String> keyIds = new LinkedHashMap<>();
        Map<String, String> sourceNamesById = new LinkedHashMap<>();
        for (Path file : topLevelYamlFiles(this.targetRoot.resolve("objects").resolve("key"))) {
            try {
                String id = safeId(fileBase(file));
                keyIds.putIfAbsent(id.toLowerCase(Locale.ROOT), id);
            }
            catch (IllegalArgumentException exception) {
                this.warnings.add("Existing v7 key file '" + file.getFileName() + "' has an invalid identifier and was not used for legacy cost matching.");
            }
        }
        for (Path source : sources) {
            for (Path file : allYamlFiles(source.resolve("keys"))) {
                try {
                    String rawId = fileBase(file);
                    String id = safeId(rawId);
                    loadYaml(file, file.toString());
                    keyIds.putIfAbsent(rawId.toLowerCase(Locale.ROOT), id);
                    String prior = sourceNamesById.putIfAbsent(id, rawId);
                    if (prior != null && !prior.equalsIgnoreCase(rawId)) {
                        this.warnings.add("Legacy keys '" + prior + "' and '" + rawId
                            + "' normalize to the same v7 key ID '" + id + "'. The first available definition will be kept.");
                    }
                }
                catch (Exception exception) {
                    this.invalidManualKeyFiles.add(file.toAbsolutePath().normalize().toString());
                    this.failures.add("key " + file + ": " + concise(exception));
                }
            }
        }
        return keyIds;
    }

    private void convertManualSource(Path source, Path sourceStage, Map<String, String> keyIds) throws IOException {
        this.sourceRoot = source;
        this.stageRoot = sourceStage;
        this.currentSourceFallbackCrateIds.clear();
        this.oldGlobalConfig = Files.isRegularFile(source.resolve("config.yml"))
            ? loadYaml(source.resolve("config.yml"), source.resolve("config.yml").toString())
            : new YamlConfiguration();
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("crate"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("key"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("reward"));
        Files.createDirectories(this.stageRoot.resolve("objects").resolve("rarity"));
        this.openingLogRewardIdCounts.clear();
        this.referencedHologramTemplates.clear();
        this.loadOpeningLogRewardIds();
        this.reportLegacyPreviewLayouts();
        if (!this.manualForceMode) this.convertSimpleRollProfiles();
        this.reportUnsupportedOpeningProfiles();

        Set<String> keyIdsInSource = new LinkedHashSet<>();
        for (Path file : allYamlFiles(source.resolve("keys"))) {
            if (this.invalidManualKeyFiles.contains(file.toAbsolutePath().normalize().toString())) continue;
            try {
                String rawId = fileBase(file);
                String id = keyIds.getOrDefault(rawId.toLowerCase(Locale.ROOT), safeId(rawId));
                if (!keyIdsInSource.add(id)) {
                    throw new IOException("Multiple legacy key files normalize to the same v7 key ID: " + id);
                }
                if (this.manualForceMode && this.forceProcessedKeyIds.contains(id)) {
                    this.manualDefinitionsSkipped++;
                    this.successes.add("kept first available forced key source for " + id);
                    continue;
                }
                if (!this.manualForceMode
                    && existingDefinition(this.targetRoot.resolve("objects").resolve("key"), id) != null) {
                    this.manualDefinitionsSkipped++;
                    this.successes.add("kept existing v7 key " + id + "; legacy definition was not overwritten");
                    continue;
                }
                YamlConfiguration oldKey = loadYaml(file, file.toString());
                YamlConfiguration converted = convertKey(oldKey);
                if (this.manualForceMode) this.forceSanitizeKey(converted, id);
                saveYaml(converted, this.stageRoot.resolve("objects").resolve("key").resolve(id + ".yml"));
                this.keysConverted++;
            }
            catch (Exception exception) {
                this.failures.add("key " + file + ": " + concise(exception));
            }
        }

        Set<String> crateIds = new LinkedHashSet<>();
        for (Path file : topLevelYamlFiles(source.resolve("crates"))) {
            String id = null;
            try {
                id = safeId(fileBase(file));
                if (!crateIds.add(id)) throw new IOException("Multiple legacy crate files normalize to the same v7 crate ID: " + id);
                if (this.manualForceMode && this.forceProcessedCrateIds.contains(id)
                    && !this.forceFallbackCrateIds.contains(id)) {
                    this.manualDefinitionsSkipped++;
                    this.successes.add("kept first available forced crate source for " + id);
                    continue;
                }
                int failuresBeforeCrate = this.failures.size();
                YamlConfiguration oldCrate = null;
                boolean safeFallback = false;
                try {
                    oldCrate = loadYaml(file, file.toString());
                }
                catch (IOException exception) {
                    if (!this.manualForceMode) throw exception;
                    safeFallback = true;
                    this.warnings.add("Crate '" + id + "' source YAML could not be parsed; force mode created a safe, locked v7 crate with no converted rewards: " + concise(exception));
                }

                YamlConfiguration converted;
                if (oldCrate == null) {
                    converted = createSafeCrate(id);
                }
                else {
                    if (this.manualForceMode) this.noteForcedFeatureRemoval(oldCrate, id);
                    try {
                        converted = convertCrate(oldCrate, id, keyIds);
                    }
                    catch (Exception exception) {
                        if (!this.manualForceMode) throw exception;
                        safeFallback = true;
                        this.warnings.add("Crate '" + id + "' had a setting that could not be converted; force mode replaced the crate settings with safe defaults: " + concise(exception));
                        converted = createSafeCrate(id);
                    }
                    if (oldCrate != null) this.rewardsConverted += convertRewards(oldCrate, id, converted);
                }
                if (this.manualForceMode && this.failures.size() > failuresBeforeCrate) safeFallback = true;
                if (this.manualForceMode) this.forceSanitizeCrate(converted, id);
                saveYaml(converted, this.stageRoot.resolve("objects").resolve("crate").resolve(id + ".yml"));
                if (this.manualForceMode && safeFallback) this.currentSourceFallbackCrateIds.add(id);
                this.cratesConverted++;
            }
            catch (Exception exception) {
                if (id != null && !crateIds.contains(id)) {
                    deleteTreeQuietly(this.stageRoot.resolve("objects").resolve("reward").resolve(id));
                }
                this.failures.add("crate " + file + ": " + concise(exception));
            }
        }

        if (!this.manualForceMode) {
            try {
                this.raritiesConverted += convertRarities();
            }
            catch (Exception exception) {
                this.failures.add("rarities in " + source.getFileName() + ": " + concise(exception));
            }
            convertHologramSettings();
        }
        this.mergeManualStage();
    }

    private YamlConfiguration createSafeCrate(String crateId) throws IOException {
        YamlConfiguration crate = new YamlConfiguration();
        crate.set("base.permission_required", false);
        crate.set("display.name", crateId.replace('_', ' '));
        crate.set("display.lore", List.of());
        crate.set("item.item_stackable", true);
        crate.set("item.use_display", true);
        crate.set("opening.enabled", true);
        crate.set("opening.opening_commands", List.of());
        crate.set("hologram.enabled", false);
        crate.set("hologram.text", defaultHologramText());
        crate.set("hologram.offset.x", 0.0D);
        crate.set("hologram.offset.y", 0.7D);
        crate.set("hologram.offset.z", 0.0D);
        crate.set("cooldowns.individual.enabled", false);
        crate.set("cooldowns.individual.mode", "DAILY");
        crate.set("cooldowns.individual.value", 0L);
        crate.set("cooldowns.global.enabled", false);
        crate.set("cooldowns.global.mode", "DAILY");
        crate.set("cooldowns.global.value", 0L);
        crate.set("effect.enabled", false);
        crate.set("effect.profile", "helix:default");
        crate.set("animation.enabled", false);
        crate.set("animation.profile", "none:null");
        crate.set("preview.enabled", false);
        crate.set("preview.config", "inventory:default");

        String lockKey = createCostReviewLockKey();
        crate.set("key_requirements.enabled", true);
        crate.set("key_requirements.keys." + lockKey + ".amount", 1);
        this.cratesLockedForCostReview.add(crateId);
        this.manualSafeDefaultsApplied++;
        return crate;
    }

    private void forceSanitizeKey(YamlConfiguration key, String keyId) {
        if (key.contains("item.item") && !isSafeAdaptedItem(key.get("item.item"))) {
            key.set("item.item", null);
            this.manualSafeDefaultsApplied++;
            this.warnings.add("Key '" + keyId + "' had an invalid item-provider setting; v7's default key item will be used.");
        }
        key.set("base.virtual", key.getBoolean("base.virtual", false));
        key.set("display.name", nonBlank(key.getString("display.name"), keyId));
        if (!key.contains("display.lore")) key.set("display.lore", List.of("A key to open a crate."));
        key.set("item.stackable", key.getBoolean("item.stackable", true));
        key.set("item.inherit_display_settings", true);
    }

    private void forceSanitizeCrate(YamlConfiguration crate, String crateId) {
        if (crate.contains("item.item") && !isSafeAdaptedItem(crate.get("item.item"))) {
            crate.set("item.item", null);
            this.manualSafeDefaultsApplied++;
            this.warnings.add("Crate '" + crateId + "' had an invalid item-provider setting; v7's default crate item will be used.");
        }
        crate.set("base.permission_required", crate.getBoolean("base.permission_required", false));
        crate.set("display.name", nonBlank(crate.getString("display.name"), crateId));
        if (!(crate.get("display.lore") instanceof List<?>)) crate.set("display.lore", List.of());
        crate.set("item.item_stackable", crate.getBoolean("item.item_stackable", true));
        crate.set("item.use_display", true);
        crate.set("opening.enabled", true);
        if (!(crate.get("opening.opening_commands") instanceof List<?>)) crate.set("opening.opening_commands", List.of());

        crate.set("preview.enabled", false);
        crate.set("preview.config", "inventory:default");
        crate.set("animation.enabled", false);
        crate.set("animation.profile", "none:null");
        crate.set("effect.enabled", false);
        crate.set("effect.profile", "helix:default");
        crate.set("hologram.enabled", false);
        crate.set("hologram.text", defaultHologramText());
        crate.set("hologram.offset.x", 0.0D);
        crate.set("hologram.offset.y", 0.7D);
        crate.set("hologram.offset.z", 0.0D);
        crate.set("cooldowns.individual.enabled", false);
        crate.set("cooldowns.individual.mode", "DAILY");
        crate.set("cooldowns.individual.value", 0L);
        crate.set("cooldowns.global.enabled", false);
        crate.set("cooldowns.global.mode", "DAILY");
        crate.set("cooldowns.global.value", 0L);

        crate.set("opening.max_openings", null);
        crate.set("opening.opening_limit", null);
        crate.set("opening.count_limit", null);
        this.manualSafeDefaultsApplied++;
    }

    private void noteForcedFeatureRemoval(YamlConfiguration oldCrate, String crateId) {
        if (oldCrate.getBoolean("Preview.Enabled", false)) {
            this.manualFeaturesDisabled++;
            this.successes.add("crate " + crateId + ": disabled legacy preview for a v7-safe editor configuration");
        }
        if (oldCrate.getBoolean("Animation.Enabled", false)) {
            this.manualFeaturesDisabled++;
            this.successes.add("crate " + crateId + ": disabled legacy opening animation in force mode");
        }
        if (oldCrate.getBoolean("Block.Effect.Enabled", false)
            || !"none".equalsIgnoreCase(oldCrate.getString("Block.Effect.Model", "none"))) {
            this.manualFeaturesDisabled++;
            this.successes.add("crate " + crateId + ": disabled legacy block effect in force mode");
        }
        if (oldCrate.contains("OpeningLimits.Amount") || oldCrate.contains("OpeningLimits.Enabled")) {
            this.manualFeaturesDisabled++;
            this.successes.add("crate " + crateId + ": removed the unsupported v6 opening-count limit");
        }
        if (oldCrate.getBoolean("Block.Hologram.Enabled", false)) {
            this.manualFeaturesDisabled++;
            this.successes.add("crate " + crateId + ": disabled legacy hologram settings in force mode");
        }
    }

    private void forceSanitizeReward(YamlConfiguration reward, String crateId, String rewardId) {
        if (reward.getBoolean("Rarity.enabled", false) || reward.contains("limit") || reward.contains("cooldown")) {
            this.manualFeaturesDisabled++;
        }
        double weight = reward.getDouble("base.weight", 10.0D);
        if (!Double.isFinite(weight) || weight <= 0D) {
            reward.set("base.weight", 1.0D);
            this.manualSafeDefaultsApplied++;
        }
        reward.set("Rarity.enabled", false);
        reward.set("Rarity.rarity_id", null);
        reward.set("preview.name", nonBlank(reward.getString("preview.name"), rewardId));
        reward.set("preview.lore", rewriteLegacyPlaceholders(reward.getStringList("preview.lore")));
        reward.set("limit", null);
        reward.set("cooldown", null);
        reward.set("broadcast.enabled", reward.getBoolean("broadcast.enabled", false));
        if (reward.contains("commands.enabled") || reward.getConfigurationSection("commands") != null) {
            reward.set("commands.give_mode", "NORMAL");
            reward.set("commands.iterations", Math.max(1, reward.getInt("commands.iterations", 1)));
            if (reward.getBoolean("commands.enabled", false)
                && sectionKeys(reward, "commands.bundles").isEmpty()) {
                reward.set("commands.enabled", false);
                this.warnings.add("Command reward '" + crateId + "/" + rewardId
                    + "' had no valid command bundle and was disabled in force mode.");
            }
        }

        Object rawItems = reward.get("items.item_list");
        Map<?, ?> itemEntries = rawItems instanceof ConfigurationSection section ? section.getValues(false)
            : rawItems instanceof Map<?, ?> map ? map : null;
        if (rawItems != null && itemEntries == null) {
            reward.set("items.item_list", Map.of());
            this.manualSafeDefaultsApplied++;
            this.failures.add("Reward '" + crateId + "/" + rewardId + "' had a malformed item list; it was cleared in force mode.");
        }
        else if (itemEntries != null) {
            for (Map.Entry<?, ?> entry : itemEntries.entrySet()) {
                String itemId = String.valueOf(entry.getKey());
                if (!isSafeAdaptedItem(entry.getValue())) {
                    reward.set("items.item_list." + itemId, null);
                    this.manualSafeDefaultsApplied++;
                    this.failures.add("Reward '" + crateId + "/" + rewardId + "' item '" + itemId
                        + "' could not be converted; the invalid entry was removed in force mode.");
                }
            }
        }

        boolean commandReward = reward.getBoolean("commands.enabled", false)
            || reward.getConfigurationSection("commands") != null;
        if (commandReward) {
            if (!isSafeAdaptedItem(reward.get("preview.icon"))) {
                reward.set("preview.icon", fallbackPreviewIcon("command_block", "command reward preview " + crateId + "/" + rewardId));
            }
            reward.set("preview.use_icon_data", false);
        }
        else {
            Map<String, Object> firstItem = firstSafeAdaptedItem(reward.get("items.item_list"));
            if (firstItem != null) {
                reward.set("preview.icon", firstItem);
                reward.set("preview.use_icon_data", true);
            }
            else {
                reward.set("preview.icon", fallbackPreviewIcon("barrier", "failed item reward preview " + crateId + "/" + rewardId));
                reward.set("preview.use_icon_data", false);
                this.failures.add("Reward '" + crateId + "/" + rewardId
                    + "' has no successfully converted given item; its preview icon was set to a barrier.");
            }
        }
    }

    private YamlConfiguration createSafeReward(String rewardId, String type) {
        YamlConfiguration reward = new YamlConfiguration();
        reward.set("base.weight", 1.0D);
        reward.set("Rarity.enabled", false);
        reward.set("preview.name", rewardId);
        reward.set("preview.lore", List.of("Legacy reward settings need to be reviewed."));
        reward.set("preview.use_icon_data", false);
        reward.set("broadcast.enabled", false);
        if ("COMMAND".equalsIgnoreCase(type)) {
            reward.set("preview.icon", fallbackPreviewIcon("command_block", "safe command reward preview " + rewardId));
            reward.set("commands.enabled", false);
            reward.set("commands.iterations", 1);
            reward.set("commands.give_mode", "NORMAL");
        }
        else {
            reward.set("preview.icon", fallbackPreviewIcon("barrier", "failed item reward preview " + rewardId));
            reward.set("items.item_list", Map.of());
        }
        return reward;
    }

    private Map<String, Object> fallbackPreviewIcon(String material, String context) {
        return vanillaItem("{id:\"minecraft:" + material + "\",count:1}", LEGACY_ITEM_DATA_VERSION, context);
    }

    private Map<String, Object> firstSafeAdaptedItem(Object rawItems) {
        Map<?, ?> items = rawItems instanceof ConfigurationSection section ? section.getValues(false)
            : rawItems instanceof Map<?, ?> map ? map : null;
        if (items == null) return null;
        for (Object rawItem : items.values()) {
            if (!isSafeAdaptedItem(rawItem)) continue;
            if (rawItem instanceof ConfigurationSection section) return normalizeMap(section.getValues(false));
            if (rawItem instanceof Map<?, ?> map) return normalizeMap(map);
        }
        return null;
    }

    private static boolean isSafeAdaptedItem(Object raw) {
        Map<?, ?> item;
        if (raw instanceof ConfigurationSection section) item = section.getValues(false);
        else if (raw instanceof Map<?, ?> map) item = map;
        else return false;
        Object provider = mapValue(item, "Provider");
        Object data = mapValue(item, "Data");
        if (!(provider instanceof String providerId) || providerId.isBlank()) return false;
        Map<?, ?> dataMap;
        if (data instanceof ConfigurationSection section) dataMap = section.getValues(false);
        else if (data instanceof Map<?, ?> map) dataMap = map;
        else return false;
        if (dataMap.isEmpty()) return false;
        if ("vanilla".equalsIgnoreCase(providerId)) {
            Object value = mapValue(dataMap, "Value");
            return value instanceof String tag && !tag.isBlank();
        }
        return true;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void mergeManualStage() throws IOException {
        Path stagedKeys = this.stageRoot.resolve("objects").resolve("key");
        for (Path file : topLevelYamlFiles(stagedKeys)) {
            String id = fileBase(file);
            try {
                Path existingKey = existingDefinition(this.targetRoot.resolve("objects").resolve("key"), id);
                if (this.manualForceMode && this.forceProcessedKeyIds.contains(id)) {
                    this.manualDefinitionsSkipped++;
                    continue;
                }
                if (existingKey != null && !this.manualForceMode) {
                    this.manualDefinitionsSkipped++;
                    this.successes.add("kept existing v7 key " + id + "; legacy definition was not overwritten");
                    continue;
                }
                if (existingKey != null) {
                    this.backupAndReplaceFile(file, existingKey);
                    this.manualKeysUpdated++;
                    this.forceProcessedKeyIds.add(id);
                    this.successes.add("key " + id + " force-converted and replaced after backup");
                }
                else {
                    Path destination = this.targetRoot.resolve("objects").resolve("key").resolve(id + ".yml");
                    if (copyIfMissing(file, destination)) {
                        this.manualKeysAdded++;
                        this.successes.add("key " + id + " added to objects/key");
                    }
                    if (this.manualForceMode) this.forceProcessedKeyIds.add(id);
                }
            }
            catch (IOException exception) {
                this.failures.add("key " + id + " publish: " + concise(exception));
            }
        }

        Path stagedCrates = this.stageRoot.resolve("objects").resolve("crate");
        for (Path stagedCrate : topLevelYamlFiles(stagedCrates)) {
            String crateId = fileBase(stagedCrate);
            Path crateDirectory = this.targetRoot.resolve("objects").resolve("crate");
            Path existingCrate = existingDefinition(crateDirectory, crateId);
            YamlConfiguration convertedCrate;
            YamlConfiguration oldV7Crate = null;
            try {
                convertedCrate = loadYaml(stagedCrate, stagedCrate.toString());
                if (existingCrate != null && !this.manualForceMode) {
                    oldV7Crate = loadYaml(existingCrate, existingCrate.toString());
                }
            }
            catch (IOException exception) {
                this.failures.add("crate " + crateId + " read before merge: " + concise(exception));
                continue;
            }

            Path stagedRewards = this.stageRoot.resolve("objects").resolve("reward").resolve(crateId);
            Set<String> newlyAddedRewards = new LinkedHashSet<>();
            for (Path stagedReward : allYamlFiles(stagedRewards)) {
                String rewardId = fileBase(stagedReward);
                Path rewardDirectory = this.targetRoot.resolve("objects").resolve("reward").resolve(crateId);
                try {
                    Path existingReward = existingDefinition(rewardDirectory, rewardId);
                    if (existingReward != null && !this.manualForceMode) {
                        this.manualDefinitionsSkipped++;
                        continue;
                    }
                    if (existingReward != null) {
                        this.backupAndReplaceFile(stagedReward, existingReward);
                        newlyAddedRewards.add(rewardId);
                        this.manualRewardsUpdated++;
                        this.successes.add("reward " + crateId + "/" + rewardId + " force-converted and replaced after backup");
                    }
                    else {
                        Path destination = rewardDirectory.resolve(rewardId + ".yml");
                        if (copyIfMissing(stagedReward, destination)) {
                            newlyAddedRewards.add(rewardId);
                            this.manualRewardsAdded++;
                            this.successes.add("reward " + crateId + "/" + rewardId + " added to objects/reward");
                        }
                    }
                }
                catch (IOException exception) {
                    this.failures.add("reward " + crateId + "/" + rewardId + " publish: " + concise(exception));
                }
            }

            try {
                if (this.manualForceMode) {
                    this.removeRewardLinksWithoutFiles(convertedCrate, crateId);
                    if (existingCrate == null) {
                        Path destination = crateDirectory.resolve(crateId + ".yml");
                        if (copyYamlIfMissing(convertedCrate, destination)) {
                            this.manualCratesAdded++;
                            this.successes.add("crate " + crateId + " force-converted and added");
                        }
                    }
                    else {
                        this.backupAndSaveExistingCrate(existingCrate, convertedCrate);
                        this.manualCratesUpdated++;
                        this.successes.add("crate " + crateId + " force-converted and replaced after backup");
                    }
                    this.forceProcessedCrateIds.add(crateId);
                    if (this.currentSourceFallbackCrateIds.contains(crateId)) this.forceFallbackCrateIds.add(crateId);
                    else this.forceFallbackCrateIds.remove(crateId);
                    continue;
                }

                if (existingCrate == null) {
                    this.removeRewardLinksWithoutFiles(convertedCrate, crateId);
                    Path destination = crateDirectory.resolve(crateId + ".yml");
                    if (copyYamlIfMissing(convertedCrate, destination)) {
                        this.manualCratesAdded++;
                        this.successes.add("crate " + crateId + " added to objects/crate");
                    }
                    continue;
                }

                int addedLinks = this.addNewRewardLinks(oldV7Crate, convertedCrate, crateId, newlyAddedRewards);
                if (addedLinks > 0) {
                    this.backupAndSaveExistingCrate(existingCrate, oldV7Crate);
                    this.manualCratesUpdated++;
                    this.successes.add("crate " + crateId + " kept its existing settings and received " + addedLinks + " missing reward link(s)");
                }
                else {
                    this.manualDefinitionsSkipped++;
                    this.successes.add("kept existing v7 crate " + crateId + "; no missing, newly converted rewards needed linking");
                }
            }
            catch (Exception exception) {
                this.failures.add("crate " + crateId + " merge: " + concise(exception));
            }
        }

        this.copyManualSupportingFiles();
    }

    private int addNewRewardLinks(YamlConfiguration existing, YamlConfiguration converted,
                                  String crateId, Set<String> newlyAddedRewards) {
        Map<String, String> convertedLinks = rewardLinks(converted);
        Set<String> existingIds = new LinkedHashSet<>();
        for (String rewardId : rewardLinks(existing).values()) existingIds.add(rewardId.toLowerCase(Locale.ROOT));
        Set<String> entryIds = new LinkedHashSet<>(sectionKeys(existing, "rewards.reward_list"));
        int added = 0;
        for (Map.Entry<String, String> link : convertedLinks.entrySet()) {
            String rewardId = link.getValue();
            if (existingIds.contains(rewardId) || !newlyAddedRewards.contains(rewardId)) continue;

            String entryId = link.getKey();
            if (entryIds.contains(entryId)) {
                entryId = UUID.nameUUIDFromBytes((crateId + "/" + rewardId + "/manual-recovery")
                    .getBytes(StandardCharsets.UTF_8)).toString();
                int suffix = 2;
                String baseEntryId = entryId;
                while (entryIds.contains(entryId)) entryId = baseEntryId + "_" + suffix++;
            }
            String path = "rewards.reward_list." + entryId;
            existing.set(path + ".reward_id", rewardId);
            existingIds.add(rewardId);
            entryIds.add(entryId);
            added++;
        }
        if (added > 0 && !existing.contains("rewards.required_rewards")) {
            existing.set("rewards.required_rewards", 1);
        }
        return added;
    }

    private void removeRewardLinksWithoutFiles(YamlConfiguration crate, String crateId) {
        for (Map.Entry<String, String> link : rewardLinks(crate).entrySet()) {
            Path rewardDirectory = this.targetRoot.resolve("objects").resolve("reward").resolve(crateId);
            if (existingDefinition(rewardDirectory, link.getValue()) == null) {
                crate.set("rewards.reward_list." + link.getKey(), null);
                this.warnings.add("Crate '" + crateId + "' was not linked to reward '" + link.getValue()
                    + "' because its reward file could not be published.");
            }
        }
    }

    private void backupAndSaveExistingCrate(Path file, YamlConfiguration configuration) throws IOException {
        this.backupExistingFile(file);
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
        try {
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void backupAndReplaceFile(Path source, Path existing) throws IOException {
        this.backupExistingFile(existing);
        Path temporary = Files.createTempFile(existing.getParent(), existing.getFileName().toString(), ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, existing, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, existing, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void backupExistingFile(Path file) throws IOException {
        Path relative = this.targetRoot.relativize(file);
        Path backup = this.manualBackupRoot.resolve(relative);
        if (!Files.exists(backup)) {
            Files.createDirectories(backup.getParent());
            Files.copy(file, backup);
        }
    }

    private void copyManualSupportingFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(this.stageRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                Path relative = this.stageRoot.relativize(file);
                if (isManualDefinitionFile(relative)) continue;
                Path destination = this.targetRoot.resolve(relative);
                try {
                    if (this.manualForceMode && Files.isRegularFile(destination)) {
                        this.backupAndReplaceFile(file, destination);
                        this.manualSupportingFilesUpdated++;
                        this.successes.add("supporting file " + relative + " force-replaced after backup");
                    }
                    else if (copyIfMissing(file, destination)) {
                        this.manualSupportingFilesAdded++;
                        this.successes.add("supporting file " + relative + " added");
                    }
                }
                catch (IOException exception) {
                    this.failures.add("supporting file " + relative + " publish: " + concise(exception));
                }
            }
        }
    }

    private static boolean isManualDefinitionFile(Path relative) {
        if (relative.getNameCount() < 2 || !"objects".equals(relative.getName(0).toString())) return false;
        String type = relative.getName(1).toString();
        return "crate".equals(type) || "key".equals(type) || "reward".equals(type);
    }

    private void writeManualReport() throws IOException {
        List<String> report = new ArrayList<>();
        report.add("");
        report.add((this.manualForceMode ? "Forced" : "Manual") + " v6.6.1 recovery: " + LocalDateTime.now().format(STAMP));
        report.add("Force mode: " + this.manualForceMode);
        report.add("Automatic legacy backup replay disabled: " + Files.exists(this.targetRoot.resolve(MANUAL_REPLAY_GUARD)));
        report.add("Crates added: " + this.manualCratesAdded);
        report.add(this.manualForceMode ? "Existing crates force-replaced: " + this.manualCratesUpdated
            : "Existing crates updated with missing reward links: " + this.manualCratesUpdated);
        report.add("Keys added: " + this.manualKeysAdded);
        report.add("Existing keys force-replaced: " + this.manualKeysUpdated);
        report.add("Rewards added: " + this.manualRewardsAdded);
        report.add("Existing rewards force-replaced: " + this.manualRewardsUpdated);
        report.add("Supporting files added: " + this.manualSupportingFilesAdded);
        report.add("Supporting files force-replaced: " + this.manualSupportingFilesUpdated);
        report.add("Unsupported features removed: " + this.manualFeaturesDisabled);
        report.add("Safe defaults applied: " + this.manualSafeDefaultsApplied);
        report.add("Existing definitions kept: " + this.manualDefinitionsSkipped);
        if (this.manualBackupRoot != null && Files.exists(this.manualBackupRoot)) {
            report.add("Existing v7 backup: " + this.manualBackupRoot.toAbsolutePath());
        }
        report.add("Results:");
        report.addAll(this.successes.stream().map(value -> "SUCCESS: " + value).toList());
        report.addAll(this.warnings.stream().map(value -> "WARNING: " + value).toList());
        report.addAll(this.failures.stream().map(value -> "FAILED: " + value).toList());
        Files.createDirectories(this.targetRoot);
        Files.write(this.targetRoot.resolve("conversion-report.txt"), report, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void writeManualReplayGuard(boolean force) throws IOException {
        String content = "recovery=" + LocalDateTime.now().format(STAMP) + "\n"
            + "force=" + force + "\n"
            + "legacy_backup_replay=disabled\n";
        Files.writeString(this.targetRoot.resolve(MANUAL_REPLAY_GUARD), content, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void printManualReport(CommandSender sender) {
        String summary = (this.manualForceMode ? "Force recovery finished: " : "Manual recovery finished: ")
            + this.manualCratesAdded + " crate(s) added, "
            + this.manualCratesUpdated + (this.manualForceMode ? " crate(s) replaced, " : " crate(s) updated with reward links, ")
            + this.manualKeysAdded + " key(s) added, " + this.manualKeysUpdated + " key(s) replaced, "
            + this.manualRewardsAdded + " reward(s) added, " + this.manualRewardsUpdated + " reward(s) replaced, "
            + this.manualSupportingFilesUpdated + " supporting file(s) replaced, "
            + this.manualFeaturesDisabled + " unsupported feature(s) removed, " + this.manualSafeDefaultsApplied + " safe default(s) applied, "
            + this.manualDefinitionsSkipped + " existing definition(s) preserved, "
            + this.failures.size() + " failure(s), " + this.warnings.size() + " review notice(s).";
        getLogger().info(summary);
        for (String success : this.successes) getLogger().info("SUCCESS: " + success);
        for (String warning : this.warnings) getLogger().warning("REVIEW: " + warning);
        for (String failure : this.failures) getLogger().severe("FAILED: " + failure);
        if (this.manualBackupRoot != null && Files.exists(this.manualBackupRoot)) {
            getLogger().info("Existing v7 backup: " + this.manualBackupRoot.toAbsolutePath());
        }
        getLogger().info("Full results appended to " + this.targetRoot.resolve("conversion-report.txt").toAbsolutePath());
        getLogger().info("Restart the server after recovery so ExcellentCrates loads the added definitions.");

        sender.sendMessage("[ExcellentCratesV6Converter] " + summary);
        int shown = 0;
        for (String success : this.successes) {
            if (shown++ >= 15) break;
            sender.sendMessage("[Converter] SUCCESS: " + success);
        }
        shown = 0;
        for (String warning : this.warnings) {
            if (shown++ >= 10) break;
            sender.sendMessage("[Converter] REVIEW: " + warning);
        }
        shown = 0;
        for (String failure : this.failures) {
            if (shown++ >= 10) break;
            sender.sendMessage("[Converter] FAILED: " + failure);
        }
        if (this.successes.size() > 15 || this.warnings.size() > 10 || this.failures.size() > 10) {
            sender.sendMessage("[Converter] Additional details are in the server console and ExcellentCrates/conversion-report.txt.");
        }
        sender.sendMessage("[Converter] Restart the server to load converted definitions.");
    }

    private static Map<String, String> rewardLinks(YamlConfiguration crate) {
        Map<String, String> links = new LinkedHashMap<>();
        for (String entryId : sectionKeys(crate, "rewards.reward_list")) {
            String rewardId = crate.getString("rewards.reward_list." + entryId + ".reward_id");
            if (rewardId != null && !rewardId.isBlank()) links.put(entryId, rewardId);
        }
        return links;
    }

    private static Path existingDefinition(Path directory, String id) {
        Path yml = directory.resolve(id + ".yml");
        if (Files.isRegularFile(yml)) return yml;
        Path yaml = directory.resolve(id + ".yaml");
        if (Files.isRegularFile(yaml)) return yaml;
        if (!Files.isDirectory(directory)) return null;
        try (Stream<Path> files = Files.list(directory)) {
            String ymlName = (id + ".yml").toLowerCase(Locale.ROOT);
            String yamlName = (id + ".yaml").toLowerCase(Locale.ROOT);
            return files.filter(Files::isRegularFile)
                .filter(file -> {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.equals(ymlName) || name.equals(yamlName);
                })
                .findFirst().orElse(null);
        }
        catch (IOException ignored) {
            return null;
        }
    }

    private static boolean copyIfMissing(Path source, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.copy(source, destination);
            return true;
        }
        catch (java.nio.file.FileAlreadyExistsException ignored) {
            return false;
        }
    }

    private static boolean copyYamlIfMissing(YamlConfiguration configuration, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        if (Files.exists(destination)) return false;
        Path temporary = Files.createTempFile(destination.getParent(), destination.getFileName().toString(), ".tmp");
        try {
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination);
            }
            return true;
        }
        catch (java.nio.file.FileAlreadyExistsException ignored) {
            return false;
        }
        finally {
            Files.deleteIfExists(temporary);
        }
    }

    private YamlConfiguration convertKey(YamlConfiguration old) {
        YamlConfiguration result = new YamlConfiguration();
        String displayName = old.getString("Name", "A key");
        result.set("base.virtual", old.getBoolean("Virtual", false));
        result.set("display.name", rewriteLegacyPlaceholders(displayName));
        result.set("display.lore", List.of("A key to open a crate."));
        setAdaptedItem(result, "item.item", firstValue(old, "ItemData", "ItemProvider", "Item"), "key " + displayName);
        result.set("item.stackable", old.getBoolean("ItemStackable", true));
        result.set("item.inherit_display_settings", true);
        if (!old.contains("ItemData") && !old.contains("ItemProvider") && !old.contains("Item")) {
            this.warnings.add("Key '" + displayName + "' had no readable item data; v7 will use its default item.");
        }
        return result;
    }

    private YamlConfiguration convertCrate(YamlConfiguration old, String crateId,
                                              Map<String, String> knownKeys) throws IOException {
        YamlConfiguration result = new YamlConfiguration();

        result.set("base.permission_required", old.getBoolean("Permission_Required", false));
        result.set("display.name", rewriteLegacyPlaceholders(old.getString("Name", crateId)));
        result.set("display.lore", rewriteLegacyPlaceholders(old.getStringList("Description")));
        setAdaptedItem(result, "item.item", firstValue(old, "ItemProvider", "ItemData", "Item"), "crate " + crateId);
        result.set("item.item_stackable", old.getBoolean("ItemStackable", true));
        result.set("item.use_display", true);

        result.set("opening.enabled", true);
        result.set("opening.opening_commands", rewriteCommands(old.getStringList("Post-Open.Commands"), "crate " + crateId));

        List<String> rawPositions = old.getStringList("Block.Positions");
        int convertedPositions = convertBlockPositions(rawPositions, result, crateId);
        this.blockPositionsConverted += convertedPositions;

        boolean hologramEnabled = old.getBoolean("Block.Hologram.Enabled", false);
        String templateId = old.getString("Block.Hologram.Template", "default");
        List<String> text = readHologramTemplate(templateId);
        if (text.isEmpty()) {
            if (!"default".equalsIgnoreCase(templateId) && !"none".equalsIgnoreCase(templateId)) {
                String warningKey = "hologram-template:" + templateId.toLowerCase(Locale.ROOT);
                if (this.reportedPlaceholderWarnings.add(warningKey)) {
                    this.warnings.add("Crate '" + crateId + "' references missing legacy hologram template '" + templateId + "'; the v7 default text was used.");
                }
            }
            text = defaultHologramText();
        }
        else {
            this.referencedHologramTemplates.add(templateId.toLowerCase(Locale.ROOT));
            this.hologramTemplatesApplied++;
        }
        result.set("hologram.enabled", hologramEnabled);
        result.set("hologram.text", rewriteLegacyPlaceholders(text));
        result.set("hologram.offset.x", 0.0D);
        result.set("hologram.offset.y", old.getDouble("Block.Hologram.Y_Offset", 0.7D));
        result.set("hologram.offset.z", 0.0D);

        int cooldownSeconds = old.getInt("OpeningCooldown.Value", 0);
        boolean cooldownEnabled = old.getBoolean("OpeningCooldown.Enabled", false) && cooldownSeconds > 0;
        result.set("cooldowns.individual.enabled", cooldownEnabled);
        result.set("cooldowns.individual.mode", "CUSTOM");
        result.set("cooldowns.individual.value", Math.max(0, cooldownSeconds));
        result.set("cooldowns.global.enabled", false);
        result.set("cooldowns.global.mode", "DAILY");
        result.set("cooldowns.global.value", 0L);

        convertCosts(old, result, knownKeys, crateId);

        if (this.manualForceMode) {
            result.set("effect.enabled", false);
            result.set("effect.profile", "helix:default");
        }
        else {
            convertBlockEffect(old, result, crateId);
        }

        boolean animationEnabled = old.getBoolean("Animation.Enabled", false);
        String animationId = old.getString("Animation.Id", "default");
        String animationProfile = this.manualForceMode ? null
            : resolveLegacyAnimationProfile(animationId, crateId, animationEnabled);
        result.set("animation.enabled", animationProfile != null);
        result.set("animation.profile", animationProfile == null ? "none:null" : animationProfile);

        boolean previewEnabled = old.getBoolean("Preview.Enabled", false);
        String previewId = old.getString("Preview.Id", "default");
        result.set("preview.enabled", previewEnabled);
        result.set("preview.config", "inventory:default");
        if (previewEnabled && previewId != null && !previewId.isBlank()
            && !"default".equalsIgnoreCase(previewId) && !"none".equalsIgnoreCase(previewId)) {
            this.warnings.add("Crate '" + crateId + "' uses legacy preview layout '" + previewId
                + (this.manualForceMode
                    ? "'; the layout remains in the backup and previews were disabled in force mode."
                    : "'; the custom layout was left in the backup and the v7.2.0 built-in default preview will be used."));
        }

        if (old.contains("OpeningLimits.Amount") && old.getInt("OpeningLimits.Amount", -1) >= 0) {
            this.warnings.add(this.manualForceMode
                ? "Crate '" + crateId + "' had an opening-count limit; force mode removed it because v7.2.0 does not store it."
                : "Crate '" + crateId + "' has an opening-count limit that v7.2.0 does not store in the crate file. See the backup.");
        }
        if (old.contains("Milestones.List") && !sectionKeys(old, "Milestones.List").isEmpty()) {
            this.warnings.add("Crate '" + crateId + "' has milestones; milestone rewards were retained in the backup but are not converted.");
        }
        return result;
    }

    private void convertBlockEffect(YamlConfiguration old, YamlConfiguration crate, String crateId) throws IOException {
        String legacyModel = old.getString("Block.Effect.Model", "none");
        String modelId = legacyModel == null ? "none" : legacyModel.trim().toLowerCase(Locale.ROOT);
        boolean enabled = old.getBoolean("Block.Effect.Enabled", !"none".equals(modelId));

        LegacyEffectSpec spec = legacyEffectSpec(modelId);
        if (spec == null) {
            crate.set("effect.enabled", false);
            crate.set("effect.profile", "helix:default");
            if (!modelId.isBlank() && !"none".equals(modelId) && !"null".equals(modelId)) {
                this.blockEffectProfilesSkipped++;
                this.warnings.add("Crate '" + crateId + "' uses legacy block effect model '" + legacyModel
                    + "', which has no built-in v7.2.0 equivalent. The effect was disabled; the original settings remain in the backup.");
            }
            return;
        }

        String profileId = "v6_" + safeId(crateId);
        String profileKey = spec.modelId() + ":" + profileId;
        String profileIdentity = profileKey.toLowerCase(Locale.ROOT);
        if (!this.convertedBlockEffectProfiles.add(profileIdentity)) {
            throw new IOException("Block effect profile ID collision: " + profileKey);
        }

        Map<String, Object> particle = convertBlockEffectParticle(old, crateId);
        YamlConfiguration profile = new YamlConfiguration();
        profile.set("base-settings.name", "Converted " + spec.modelId() + " (" + crateId + ")");
        profile.set("base-settings.particle", particle);
        profile.set("base-settings.max_frames", spec.maxFrames());
        profile.set("base-settings.tick_interval", spec.tickInterval());
        profile.set("base-settings.pause_ticks", 10);
        saveYaml(profile, this.stageRoot.resolve("objects").resolve("effects").resolve(spec.modelId()).resolve(profileId + ".yml"));

        crate.set("effect.enabled", enabled);
        crate.set("effect.profile", profileKey);
        this.blockEffectProfilesConverted++;
        this.successes.add("block effect profile " + legacyModel + " -> " + profileKey);
    }

    private Map<String, Object> convertBlockEffectParticle(YamlConfiguration old, String crateId) {
        String legacyName = old.getString("Block.Effect.Particle.Name", "FLAME");
        String targetName = mapLegacyParticleName(legacyName);
        Particle targetParticle = null;
        if (targetName != null) {
            try {
                targetParticle = Particle.valueOf(targetName.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException ignored) { }
        }

        if (targetParticle == null) {
            return blockEffectParticleFallback(crateId, legacyName,
                "the particle name is not available in the v7 server API");
        }

        Class<?> dataType = targetParticle.getDataType();
        Map<String, Object> particle = new LinkedHashMap<>();
        particle.put("name", "minecraft:" + targetParticle.name().toLowerCase(Locale.ROOT));

        if (dataType == Void.class) return particle;

        Map<String, Object> data = new LinkedHashMap<>();
        String path = "Block.Effect.Particle.";
        if (dataType == Particle.DustOptions.class) {
            int[] color = legacyColor(old.getString(path + "Color"), new int[]{255, 255, 255});
            if (color == null) {
                color = new int[]{255, 255, 255};
                this.warnings.add("Crate '" + crateId + "' has an invalid legacy particle color; white was used.");
            }
            data.put("color", colorMap(color));
            data.put("size", safeParticleSize(old.getDouble(path + "Size", 1D), crateId));
        }
        else if (dataType == Particle.DustTransition.class) {
            int[] from = legacyColor(old.getString(path + "Color_From"), new int[]{255, 255, 255});
            int[] to = legacyColor(old.getString(path + "Color_To"), new int[]{255, 255, 255});
            if (from == null || to == null) {
                this.warnings.add("Crate '" + crateId + "' has an invalid legacy dust-transition color; white was used for the invalid color.");
            }
            data.put("color_from", colorMap(from == null ? new int[]{255, 255, 255} : from));
            data.put("color_to", colorMap(to == null ? new int[]{255, 255, 255} : to));
            data.put("size", safeParticleSize(old.getDouble(path + "Size", 1D), crateId));
        }
        else if (Color.class.equals(dataType)) {
            int[] color = legacyColor(old.getString(path + "Color"), new int[]{255, 255, 255});
            if (color == null) {
                color = new int[]{255, 255, 255};
                this.warnings.add("Crate '" + crateId + "' has an invalid legacy particle color; white was used.");
            }
            data.putAll(colorMap(color));
        }
        else if ("org.bukkit.Particle$Spell".equals(dataType.getName()) && supportsV7SpellParticleCodec()) {
            int[] color = legacyColor(old.getString(path + "Color"), new int[]{255, 255, 255});
            if (color == null) {
                color = new int[]{255, 255, 255};
                this.warnings.add("Crate '" + crateId + "' has an invalid legacy particle color; white was used.");
            }
            data.put("color", colorMap(color));
            data.put("power", old.getDouble(path + "Power", 1D));
        }
        else {
            return blockEffectParticleFallback(crateId, legacyName,
                "v7.2.0 has no registered codec for particle data type " + dataType.getSimpleName());
        }

        particle.put("data", data);
        return particle;
    }

    private Map<String, Object> blockEffectParticleFallback(String crateId, String legacyName, String reason) {
        this.blockEffectParticleFallbacks++;
        this.warnings.add("Crate '" + crateId + "' uses legacy block-effect particle '" + legacyName
            + "', which could not be loaded safely because " + reason + "; FLAME was used instead. The original particle data remains in the backup.");
        return new LinkedHashMap<>(Map.of("name", "minecraft:flame"));
    }

    private static LegacyEffectSpec legacyEffectSpec(String modelId) {
        return switch (modelId) {
            case "beacon" -> new LegacyEffectSpec("beacon", 40, 3);
            case "helix" -> new LegacyEffectSpec("helix", 24, 1);
            case "pulsar" -> new LegacyEffectSpec("pulsar", 38, 2);
            case "sphere" -> new LegacyEffectSpec("sphere", 8, 1);
            case "spiral" -> new LegacyEffectSpec("spiral", 50, 1);
            case "vortex" -> new LegacyEffectSpec("vortex", 34, 1);
            default -> null;
        };
    }

    private static String mapLegacyParticleName(String rawName) {
        if (rawName == null || rawName.isBlank() || "null".equalsIgnoreCase(rawName)) return "FLAME";
        return switch (rawName.trim().toUpperCase(Locale.ROOT)) {
            case "EXPLOSION_NORMAL" -> "POOF";
            case "EXPLOSION_LARGE" -> "EXPLOSION";
            case "EXPLOSION_HUGE" -> "EXPLOSION_EMITTER";
            case "FIREWORKS_SPARK" -> "FIREWORK";
            case "WATER_BUBBLE" -> "BUBBLE";
            case "WATER_SPLASH" -> "SPLASH";
            case "WATER_WAKE" -> "FISHING";
            case "SUSPENDED", "SUSPENDED_DEPTH" -> "UNDERWATER";
            case "CRIT_MAGIC" -> "ENCHANTED_HIT";
            case "SMOKE_NORMAL" -> "SMOKE";
            case "SMOKE_LARGE" -> "LARGE_SMOKE";
            case "SPELL" -> "EFFECT";
            case "SPELL_INSTANT" -> "INSTANT_EFFECT";
            case "SPELL_MOB", "SPELL_MOB_AMBIENT" -> "ENTITY_EFFECT";
            case "SPELL_WITCH" -> "WITCH";
            case "DRIP_WATER" -> "DRIPPING_WATER";
            case "DRIP_LAVA" -> "DRIPPING_LAVA";
            case "VILLAGER_ANGRY" -> "ANGRY_VILLAGER";
            case "VILLAGER_HAPPY" -> "HAPPY_VILLAGER";
            case "TOWN_AURA" -> "MYCELIUM";
            case "ENCHANTMENT_TABLE" -> "ENCHANT";
            case "REDSTONE" -> "DUST";
            case "ITEM_CRACK" -> "ITEM";
            case "BLOCK_CRACK" -> "BLOCK";
            case "BLOCK_DUST" -> "FALLING_DUST";
            case "SNOWBALL" -> "ITEM_SNOWBALL";
            case "SLIME" -> "ITEM_SLIME";
            case "WATER_DROP" -> "RAIN";
            case "MOB_APPEARANCE" -> "ELDER_GUARDIAN";
            case "TOTEM" -> "TOTEM_OF_UNDYING";
            case "DUST_COLOR_TRANSITION" -> "DUST_COLOR_TRANSITION";
            default -> rawName.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static Map<String, Object> colorMap(int[] rgb) {
        Map<String, Object> color = new LinkedHashMap<>();
        color.put("red", rgb[0]);
        color.put("green", rgb[1]);
        color.put("blue", rgb[2]);
        color.put("alpha", 255);
        return color;
    }

    private static int[] legacyColor(String value, int[] fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        String hex = normalized.startsWith("#") ? normalized.substring(1)
            : normalized.startsWith("0x") || normalized.startsWith("0X") ? normalized.substring(2) : null;
        if (hex != null && hex.matches("(?i)[0-9a-f]{6}")) {
            int color = Integer.parseInt(hex, 16);
            return new int[]{(color >> 16) & 0xFF, (color >> 8) & 0xFF, color & 0xFF};
        }
        String[] parts = normalized.split("[,;\\s]+");
        if (parts.length == 3) {
            try {
                int red = Integer.parseInt(parts[0]);
                int green = Integer.parseInt(parts[1]);
                int blue = Integer.parseInt(parts[2]);
                if (red >= 0 && red <= 255 && green >= 0 && green <= 255 && blue >= 0 && blue <= 255) {
                    return new int[]{red, green, blue};
                }
            }
            catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private double safeParticleSize(double size, String crateId) {
        if (Double.isFinite(size) && size > 0D) return size;
        this.warnings.add("Crate '" + crateId + "' has an invalid legacy particle size; size 1.0 was used.");
        return 1D;
    }

    private static boolean supportsV7SpellParticleCodec() {
        String version = Bukkit.getMinecraftVersion();
        if (version.startsWith("26.")) return true;
        Matcher matcher = Pattern.compile("^1\\.(\\d+)\\.(\\d+)").matcher(version);
        if (!matcher.find()) return false;
        try {
            int minor = Integer.parseInt(matcher.group(1));
            int patch = Integer.parseInt(matcher.group(2));
            return minor > 21 || minor == 21 && patch >= 10;
        }
        catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void reportLegacyPreviewLayouts() {
        try {
            int count = allYamlFiles(this.sourceRoot.resolve("previews")).size();
            if (count > 0) {
                this.warnings.add(count + " legacy preview layout file(s) were not converted. "
                    + (this.manualForceMode
                        ? "Force mode disables the converted crate previews; the originals remain in the backup."
                        : "Crates with previews enabled use v7.2.0's built-in inventory:default profile; the originals remain in the backup."));
            }
        }
        catch (IOException exception) {
            this.warnings.add("Legacy preview layouts could not be inventoried: " + concise(exception) + ". Their originals remain in the backup.");
        }
    }

    private void convertSimpleRollProfiles() {
        Path profileDirectory = this.sourceRoot.resolve("openings").resolve("simple_roll");
        try {
            for (Path file : topLevelYamlFiles(profileDirectory)) {
                String rawId = fileBase(file);
                try {
                    String id = safeId(rawId);
                    if (this.convertedSimpleRollProfiles.contains(id)) {
                        throw new IOException("Simple Roll profile ID collision after normalization: " + id);
                    }
                    YamlConfiguration legacy = loadYaml(file, file.toString());
                    YamlConfiguration converted = convertSimpleRollProfile(legacy, id);
                    saveYaml(converted, this.stageRoot.resolve("objects").resolve("animations").resolve("simple_roll").resolve(id + ".yml"));
                    this.convertedSimpleRollProfiles.add(id);
                    this.openingProfilesConverted++;
                    this.successes.add("opening profile " + rawId + " -> simple_roll:" + id);
                }
                catch (Exception exception) {
                    this.failures.add("opening profile " + file + ": " + concise(exception));
                }
            }
        }
        catch (IOException exception) {
            this.warnings.add("Legacy Simple Roll profiles could not all be enumerated: " + concise(exception) + ". Their originals remain in the backup.");
        }
    }

    private void reportUnsupportedOpeningProfiles() {
        Path openingsDirectory = this.sourceRoot.resolve("openings");
        try {
            List<String> unsupported = new ArrayList<>();
            for (Path file : allYamlFiles(openingsDirectory)) {
                Path relative = openingsDirectory.relativize(file);
                if (relative.getNameCount() > 1 && "simple_roll".equalsIgnoreCase(relative.getName(0).toString())) continue;
                unsupported.add(relative.toString().replace('\\', '/'));
            }
            if (!unsupported.isEmpty()) {
                this.warnings.add("Legacy opening profile file(s) " + String.join(", ", unsupported)
                    + " do not map to a built-in v7 animation style and remain in the backup.");
            }
        }
        catch (IOException exception) {
            this.warnings.add("Legacy opening profiles could not all be inventoried: " + concise(exception) + ". Their originals remain in the backup.");
        }
    }

    private YamlConfiguration convertSimpleRollProfile(YamlConfiguration old, String id) {
        YamlConfiguration result = new YamlConfiguration();
        result.set("settings.name", old.getString("Settings.Name", "Simple Roll (" + id + ")"));
        result.set("settings.roll_amount", old.getInt("Settings.Steps_Amount", 15));
        result.set("settings.roll_interval", old.getInt("Settings.Steps_Tick", 3));
        result.set("settings.finish_delay", old.getInt("Settings.Complete_Pause", 40));
        return result;
    }

    private String resolveLegacyAnimationProfile(String legacyId, String crateId, boolean enabled) {
        if (!enabled || legacyId == null || legacyId.isBlank() || "none".equalsIgnoreCase(legacyId)) return null;

        String profileId;
        try {
            profileId = safeId(legacyId);
        }
        catch (IllegalArgumentException exception) {
            this.warnings.add("Crate '" + crateId + "' references invalid legacy opening profile '" + legacyId + "'; v7 animation was disabled and the source remains in the backup.");
            return null;
        }

        if (this.convertedSimpleRollProfiles.contains(profileId)) return "simple_roll:" + profileId;

        if ("simple_roll".equalsIgnoreCase(legacyId)) {
            try {
                YamlConfiguration fallback = new YamlConfiguration();
                fallback.set("settings.name", "Simple Roll (Legacy Default)");
                fallback.set("settings.roll_amount", 15);
                fallback.set("settings.roll_interval", 3);
                fallback.set("settings.finish_delay", 40);
                saveYaml(fallback, this.stageRoot.resolve("objects").resolve("animations").resolve("simple_roll").resolve(profileId + ".yml"));
                this.convertedSimpleRollProfiles.add(profileId);
                this.openingProfilesConverted++;
                this.successes.add("created legacy default opening profile simple_roll:" + profileId);
                return "simple_roll:" + profileId;
            }
            catch (IOException exception) {
                this.failures.add("opening profile simple_roll:" + profileId + ": " + concise(exception));
                return null;
            }
        }

        if (!"default".equalsIgnoreCase(legacyId)) {
            this.warnings.add("Crate '" + crateId + "' uses legacy opening profile '" + legacyId + "'. Only Simple Roll profiles map directly to v7; this opening animation was left disabled and the old profile remains in the backup.");
        }
        return null;
    }

    private List<String> readHologramTemplate(String templateId) {
        for (String path : List.of("Crate.Holograms.TemplateList", "Crate.Holograms.Templates")) {
            ConfigurationSection templates = this.oldGlobalConfig.getConfigurationSection(path);
            if (templates == null) continue;

            String foundId = templates.getKeys(false).stream()
                .filter(id -> id.equalsIgnoreCase(templateId))
                .findFirst().orElse(null);
            if (foundId == null) continue;

            List<String> text = this.oldGlobalConfig.getStringList(path + "." + foundId + ".Text");
            if (text.isEmpty()) text = this.oldGlobalConfig.getStringList(path + "." + foundId);
            if (!text.isEmpty()) return text;
        }
        return List.of();
    }

    private void convertHologramSettings() {
        if (this.oldGlobalConfig.contains("Crate.Holograms.Update_Interval")) {
            int seconds = Math.max(1, this.oldGlobalConfig.getInt("Crate.Holograms.Update_Interval", 5));
            YamlConfiguration settings = new YamlConfiguration();
            settings.set("update_interval", seconds * 20L);
            try {
                saveYaml(settings, this.stageRoot.resolve("config").resolve("crates.holograms.yml"));
                this.successes.add("hologram update interval " + seconds + " seconds -> " + (seconds * 20L) + " ticks");
            }
            catch (IOException exception) {
                this.failures.add("hologram settings: " + concise(exception));
            }
        }

        if (Math.abs(this.oldGlobalConfig.getDouble("Crate.Holograms.LineGap", 0.3D) - 0.3D) > 0.0001D
            || !"VERTICAL".equalsIgnoreCase(this.oldGlobalConfig.getString("Crate.Holograms.Billboard", "VERTICAL"))
            || this.oldGlobalConfig.getInt("Crate.Holograms.TextOpacity", -1) != -1
            || this.oldGlobalConfig.getBoolean("Crate.Holograms.SeeThrough", false)
            || !this.oldGlobalConfig.getBoolean("Crate.Holograms.Shadow", true)) {
            this.warnings.add("Legacy hologram line spacing, billboard, opacity, see-through, or shadow settings differ from v6 defaults; v7 has no direct equivalents for these visual options. The v6 config remains in the backup.");
        }

        Set<String> allTemplateIds = new LinkedHashSet<>();
        for (String path : List.of("Crate.Holograms.TemplateList", "Crate.Holograms.Templates")) {
            allTemplateIds.addAll(sectionKeys(this.oldGlobalConfig, path));
        }
        long unusedTemplates = allTemplateIds.stream()
            .filter(id -> !this.referencedHologramTemplates.contains(id.toLowerCase(Locale.ROOT)))
            .count();
        if (unusedTemplates > 0) {
            this.warnings.add(unusedTemplates + " unreferenced legacy hologram template(s) have no global template registry in v7. Used templates were embedded in their crate files; unused templates remain in the backup.");
        }
    }

    private int convertRewards(YamlConfiguration oldCrate, String crateId, YamlConfiguration newCrate) throws IOException {
        List<String> rewardKeys = sectionKeys(oldCrate, "Rewards.List");
        if (rewardKeys.isEmpty()) return 0;

        if (this.manualForceMode) {
            List<String> validRewardKeys = new ArrayList<>();
            for (String rewardId : rewardKeys) {
                try { safeId(rewardId); validRewardKeys.add(rewardId); }
                catch (IllegalArgumentException exception) {
                    this.failures.add("reward " + crateId + "/" + rewardId + ": invalid ID, skipped in force mode");
                }
            }
            rewardKeys = validRewardKeys;
            if (rewardKeys.isEmpty()) return 0;
        }

        int count = 0;
        Map<String, String> resolvedRewardIds = resolveRewardIds(crateId, rewardKeys);
        for (String oldRewardId : rewardKeys) {
            String rewardId = resolvedRewardIds.get(oldRewardId);
            String type = "ITEM";
            try {
                String oldPath = "Rewards.List." + oldRewardId;
                YamlConfiguration reward = loadYamlFromSection(oldCrate, oldPath);
                type = reward.getString("Type", inferRewardType(reward));
                YamlConfiguration target = convertReward(reward, crateId, rewardId, type);
                if (this.manualForceMode) this.forceSanitizeReward(target, crateId, rewardId);
                Path out = this.stageRoot.resolve("objects").resolve("reward").resolve(crateId).resolve(rewardId + ".yml");
                saveYaml(target, out);
            }
            catch (Exception exception) {
                if (!this.manualForceMode) {
                    if (exception instanceof IOException ioException) throw ioException;
                    throw new IOException("Could not convert reward " + crateId + "/" + oldRewardId, exception);
                }
                this.failures.add("reward " + crateId + "/" + oldRewardId + " could not be converted normally; a safe placeholder reward was generated: " + concise(exception));
                YamlConfiguration fallback = createSafeReward(rewardId, type);
                saveYaml(fallback, this.stageRoot.resolve("objects").resolve("reward").resolve(crateId).resolve(rewardId + ".yml"));
            }

            String entryId = UUID.nameUUIDFromBytes((crateId + "/" + rewardId).getBytes(StandardCharsets.UTF_8)).toString();
            String entryPath = "rewards.reward_list." + entryId;
            newCrate.set(entryPath + ".reward_id", rewardId);
            count++;
            this.successes.add("reward " + crateId + "/" + oldRewardId + " -> " + rewardId);
        }
        newCrate.set("rewards.required_rewards", 1);

        if (oldCrate.contains("Milestones.List") && !sectionKeys(oldCrate, "Milestones.List").isEmpty()) {
            this.warnings.add("Crate '" + crateId + "' milestone reward links were not included in the v7 reward list.");
        }
        return count;
    }

    private Map<String, String> resolveRewardIds(String crateId, List<String> legacyIds) {
        Map<String, List<String>> groups = new java.util.TreeMap<>();
        for (String legacyId : legacyIds) {
            groups.computeIfAbsent(safeId(legacyId), ignored -> new ArrayList<>()).add(legacyId);
        }

        Set<String> reservedIds = new LinkedHashSet<>(groups.keySet());
        Set<String> usedIds = new LinkedHashSet<>(reservedIds);
        Set<String> targetRewardIds = this.manualMode && !this.manualForceMode ? manualExistingRewardIds(crateId) : Set.of();
        Set<String> targetLinkedRewardIds = this.manualMode && !this.manualForceMode ? manualLinkedRewardIds(crateId) : Set.of();
        Set<String> targetRewardIdsNotLinked = new LinkedHashSet<>(targetRewardIds);
        targetRewardIdsNotLinked.removeAll(targetLinkedRewardIds);
        reservedIds.addAll(targetRewardIds);
        usedIds.addAll(targetRewardIds);
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            String baseId = group.getKey();
            List<String> candidates = new ArrayList<>(group.getValue());
            candidates.sort((left, right) -> {
                int byOpeningLog = Integer.compare(
                    openingLogRewardIdCount(crateId, right), openingLogRewardIdCount(crateId, left));
                if (byOpeningLog != 0) return byOpeningLog;
                int byCaseInsensitive = String.CASE_INSENSITIVE_ORDER.compare(left, right);
                return byCaseInsensitive != 0 ? byCaseInsensitive : left.compareTo(right);
            });

            String preferredId = candidates.remove(0);
            boolean baseOccupiedByUnlinkedV7Reward = targetRewardIdsNotLinked.contains(baseId);
            if (!baseOccupiedByUnlinkedV7Reward) {
                resolved.put(preferredId, baseId);
                usedIds.add(baseId);
            }
            else {
                int suffix = 2;
                String uniqueId;
                do {
                    uniqueId = baseId + "_" + suffix++;
                }
                while (reservedIds.contains(uniqueId) || usedIds.contains(uniqueId));
                usedIds.add(uniqueId);
                resolved.put(preferredId, uniqueId);
            }

            if (candidates.isEmpty() && !baseOccupiedByUnlinkedV7Reward) continue;

            int matchingLogReferences = openingLogRewardIdCount(crateId, preferredId);
            this.rewardIdCollisionsResolved += candidates.size() + (baseOccupiedByUnlinkedV7Reward ? 1 : 0);
            if (!candidates.isEmpty() && matchingLogReferences > 0) this.rewardIdCollisionGroupsMatchedToOpeningLog++;

            List<String> assignmentNotes = new ArrayList<>();
            assignmentNotes.add(preferredId + " -> " + resolved.get(preferredId));
            for (String legacyId : candidates) {
                int suffix = 2;
                String uniqueId;
                do {
                    uniqueId = baseId + "_" + suffix++;
                }
                while (reservedIds.contains(uniqueId) || usedIds.contains(uniqueId));
                usedIds.add(uniqueId);
                resolved.put(legacyId, uniqueId);
                assignmentNotes.add(legacyId + " -> " + uniqueId);
            }

            String preferenceNote = baseOccupiedByUnlinkedV7Reward
                ? "the base ID already belongs to an unlinked v7 reward, so a new unique ID was used"
                : matchingLogReferences > 0
                ? "openings.log matched '" + preferredId + "' " + matchingLogReferences + " time(s)"
                : "no exact reward ID match was found in openings.log; used a stable alphabetical choice";
            this.warnings.add("Crate '" + crateId + "' had legacy reward IDs " + group.getValue()
                + " that normalize to the same v7 ID. Unique IDs were assigned (" + String.join(", ", assignmentNotes)
                + "); " + preferenceNote + ".");
            this.successes.add("resolved duplicate reward IDs in crate " + crateId + ": " + String.join(", ", assignmentNotes));
        }
        return resolved;
    }

    private Set<String> manualExistingRewardIds(String crateId) {
        Path directory = this.targetRoot.resolve("objects").resolve("reward").resolve(crateId);
        Set<String> ids = new LinkedHashSet<>();
        try {
            for (Path file : allYamlFiles(directory)) ids.add(fileBase(file).toLowerCase(Locale.ROOT));
        }
        catch (IOException exception) {
            this.warnings.add("Could not inventory existing v7 rewards for crate '" + crateId + "': "
                + concise(exception) + ". Conflicting reward files will be kept untouched.");
        }
        return ids;
    }

    private Set<String> manualLinkedRewardIds(String crateId) {
        Path crateFile = existingDefinition(this.targetRoot.resolve("objects").resolve("crate"), crateId);
        if (crateFile == null) return Set.of();
        try {
            Set<String> linked = new LinkedHashSet<>();
            for (String rewardId : rewardLinks(loadYaml(crateFile, crateFile.toString())).values()) {
                linked.add(rewardId.toLowerCase(Locale.ROOT));
            }
            return linked;
        }
        catch (IOException exception) {
            this.warnings.add("Could not inspect existing v7 reward links for crate '" + crateId + "': "
                + concise(exception) + ". Potential ID conflicts will be preserved for review.");
            return Set.of();
        }
    }

    private int openingLogRewardIdCount(String crateId, String rewardId) {
        Map<String, Integer> rewardCounts = this.openingLogRewardIdCounts.get(crateId.toLowerCase(Locale.ROOT));
        if (rewardCounts == null) return 0;
        return rewardCounts.getOrDefault(rewardId.trim().toLowerCase(Locale.ROOT), 0);
    }

    private void loadOpeningLogRewardIds() {
        Path logFile = this.sourceRoot.resolve("openings.log");
        if (!Files.isRegularFile(logFile)) return;

        try (var reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = OPENING_LOG_REWARD.matcher(line);
                if (!matcher.find()) continue;

                String rewardId = matcher.group(1).trim();
                String crateId;
                try {
                    crateId = safeId(matcher.group(2).trim());
                }
                catch (IllegalArgumentException ignored) {
                    continue;
                }
                Map<String, Integer> rewardCounts = this.openingLogRewardIdCounts.computeIfAbsent(crateId, ignored -> new LinkedHashMap<>());
                rewardCounts.merge(rewardId.toLowerCase(Locale.ROOT), 1, Integer::sum);
                this.openingLogRewardReferencesIndexed++;
            }
        }
        catch (IOException exception) {
            this.warnings.add("Could not read legacy openings.log for reward ID matching: " + concise(exception)
                + ". Duplicate reward IDs will be resolved deterministically without log references; the log remains in the backup.");
            return;
        }

        if (this.openingLogRewardReferencesIndexed > 0) {
            this.successes.add("indexed " + this.openingLogRewardReferencesIndexed
                + " reward ID reference(s) from openings.log for duplicate resolution");
        }
    }

    private YamlConfiguration convertReward(YamlConfiguration old, String crateId, String rewardId, String type) {
        YamlConfiguration result = new YamlConfiguration();
        result.set("base.weight", old.getDouble("Weight", 10.0D));
        result.set("Rarity.enabled", true);
        result.set("Rarity.rarity_id", safeId(old.getString("Rarity", defaultRarityId())));

        Object icon = this.manualForceMode ? null : firstValue(old, "PreviewData", "Preview");
        result.set("preview.name", rewriteLegacyPlaceholders(old.getString("Name", rewardId)));
        result.set("preview.lore", rewriteLegacyPlaceholders(old.getStringList("Description")));
        result.set("broadcast.enabled", old.getBoolean("Broadcast", false));

        convertRewardLimits(old, result);

        if ("COMMAND".equalsIgnoreCase(type)) {
            Map<String, Object> previewIcon = icon == null ? null
                : convertPreviewIcon(icon, "command reward preview " + crateId + "/" + rewardId);
            boolean useIconData = previewIcon != null && old.getBoolean("Custom_Preview", false);
            if (previewIcon == null) {
                String fallback = icon != null && !this.manualForceMode ? "barrier" : "command_block";
                previewIcon = fallbackPreviewIcon(fallback, "command reward preview " + crateId + "/" + rewardId);
            }
            result.set("preview.icon", previewIcon);
            result.set("preview.use_icon_data", useIconData);
            List<String> commands = rewriteCommands(old.getStringList("Commands"), "reward " + crateId + "/" + rewardId);
            result.set("commands.enabled", true);
            result.set("commands.iterations", 1);
            result.set("commands.give_mode", "NORMAL");
            if (!commands.isEmpty()) {
                String bundleId = UUID.nameUUIDFromBytes((crateId + "/" + rewardId + "/commands").getBytes(StandardCharsets.UTF_8)).toString();
                result.set("commands.bundles." + bundleId + ".commands", commands);
                result.set("commands.bundles." + bundleId + ".weight", 1.0D);
            }
        }
        else {
            Map<String, Object> rewardItems = convertItemList(old, crateId, rewardId);
            result.set("items.item_list", rewardItems);
            boolean customPreview = old.getBoolean("Custom_Preview", false);
            Map<String, Object> previewIcon = null;
            boolean useIconData = false;
            if (!this.manualForceMode && icon != null && (customPreview || rewardItems.isEmpty())) {
                previewIcon = convertPreviewIcon(icon, "reward preview " + crateId + "/" + rewardId);
                useIconData = previewIcon != null && customPreview;
            }
            if (previewIcon == null && !rewardItems.isEmpty()) {
                Object firstItem = rewardItems.values().iterator().next();
                if (firstItem instanceof Map<?, ?> map) {
                    previewIcon = normalizeMap(map);
                    useIconData = true;
                    this.rewardPreviewIconsFromItems++;
                }
            }
            if (previewIcon == null) {
                previewIcon = fallbackPreviewIcon("barrier", "failed item reward preview " + crateId + "/" + rewardId);
                useIconData = false;
                this.failures.add("Reward '" + crateId + "/" + rewardId
                    + "' has no successfully converted preview or given item; its preview icon was set to a barrier.");
            }
            result.set("preview.icon", previewIcon);
            result.set("preview.use_icon_data", useIconData);
            if (old.getBoolean("Placeholder_Apply", false)) {
                this.warnings.add("Reward '" + crateId + "/" + rewardId + "' used item placeholders; review its v7 item reward after conversion.");
            }
        }
        if (old.contains("Ignored_For_Permissions") || old.contains("Required_Permissions")) {
            if (!old.getStringList("Ignored_For_Permissions").isEmpty() || !old.getStringList("Required_Permissions").isEmpty()) {
                this.warnings.add("Reward '" + crateId + "/" + rewardId + "' has permission filters that v7.2.0 does not store in its reward data file.");
            }
        }
        return result;
    }

    private Map<String, Object> convertPreviewIcon(Object raw, String context) {
        try {
            Map<String, Object> converted = convertAdaptedItem(raw, context);
            if (converted == null) {
                this.failures.add(context + " could not be converted from the legacy item format.");
            }
            return converted;
        }
        catch (Exception exception) {
            this.failures.add(context + " conversion failed: " + concise(exception));
            return null;
        }
    }

    private Map<String, Object> convertItemList(YamlConfiguration old, String crateId, String rewardId) {
        Map<String, Object> items = new LinkedHashMap<>();
        Object itemData = old.get("ItemsData");
        int itemDataEntries = 0;
        if (itemData instanceof ConfigurationSection section) {
            for (String itemId : section.getKeys(false)) {
                itemDataEntries++;
                addConvertedRewardItem(items, section.get(itemId), itemId, crateId, rewardId);
            }
        }
        else if (itemData instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                itemDataEntries++;
                addConvertedRewardItem(items, entry.getValue(), String.valueOf(entry.getKey()), crateId, rewardId);
            }
        }
        else if (itemData instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                itemDataEntries++;
                addConvertedRewardItem(items, list.get(index), String.valueOf(index), crateId, rewardId);
            }
        }
        List<String> oldEncodedItems = old.getStringList("Items");
        if (items.isEmpty() && !oldEncodedItems.isEmpty()) {
            for (int index = 0; index < oldEncodedItems.size(); index++) {
                try {
                    String snbt = decodeLegacyItemNbt(oldEncodedItems.get(index));
                    Map<String, Object> converted = vanillaItem(snbt, LEGACY_ITEM_DATA_VERSION,
                        "reward " + crateId + "/" + rewardId + " item " + index);
                    String key = UUID.nameUUIDFromBytes((crateId + "/" + rewardId + "/legacy-" + index).getBytes(StandardCharsets.UTF_8)).toString();
                    items.put(key, converted);
                    this.rewardItemsConverted++;
                }
                catch (Exception exception) {
                    this.rewardItemsFailed++;
                    this.failures.add("Reward '" + crateId + "/" + rewardId + "' legacy item " + index
                        + " could not be decoded and was skipped: " + concise(exception) + ". See the backup.");
                }
            }
        }
        if (itemDataEntries == 0 && oldEncodedItems.isEmpty()) {
            this.failures.add("Reward '" + crateId + "/" + rewardId
                + "' has no readable legacy ItemsData or Items entries; its v7 item list is empty. See the backup.");
        }
        return items;
    }

    private void addConvertedRewardItem(Map<String, Object> items, Object item, String itemId, String crateId, String rewardId) {
        if (item == null) return;
        String context = "reward " + crateId + "/" + rewardId + " item " + itemId;
        Map<String, Object> converted = convertAdaptedItem(item, context);
        if (converted == null) {
            this.rewardItemsFailed++;
            this.failures.add("Reward '" + crateId + "/" + rewardId + "' item '" + itemId
                + "' has an unsupported legacy item format and was skipped. See the backup.");
            return;
        }
        String key = UUID.nameUUIDFromBytes((crateId + "/" + rewardId + "/" + itemId).getBytes(StandardCharsets.UTF_8)).toString();
        items.put(key, converted);
        this.rewardItemsConverted++;
    }

    private void setAdaptedItem(YamlConfiguration target, String path, Object raw, String context) {
        if (raw == null) return;
        Map<String, Object> converted = convertAdaptedItem(raw, context);
        if (converted == null) {
            this.warnings.add("" + context + " item could not be converted to the v7 item-provider format; the v7 default item will be used. See the backup.");
            return;
        }
        target.set(path, converted);
    }

    private Map<String, Object> convertAdaptedItem(Object raw, String context) {
        Map<?, ?> source;
        if (raw instanceof Map<?, ?> rawMap) source = normalizeMap(rawMap);
        else if (raw instanceof ConfigurationSection section) source = normalizeMap(section.getValues(false));
        else return null;

        Object typeValue = mapValue(source, "Type");
        String type = typeValue == null ? "" : String.valueOf(typeValue).trim().toLowerCase(Locale.ROOT);
        if ("vanilla".equals(type) || mapValue(source, "ItemTag") != null) {
            Object tag = mapValue(source, "ItemTag");
            if (tag == null) tag = mapValue(source, "Tag");
            Object dataVersion = mapValue(source, "DataVersion");
            if (tag instanceof Map<?, ?> tagMap) {
                Object nestedValue = mapValue(tagMap, "Value");
                if (nestedValue != null) tag = nestedValue;
                Object nestedVersion = mapValue(tagMap, "DataVersion");
                if (nestedVersion != null) dataVersion = nestedVersion;
            }
            if (tag == null) return null;
            return vanillaItem(String.valueOf(tag), integerValue(dataVersion, LEGACY_ITEM_DATA_VERSION), context);
        }

        if ("custom".equals(type) || mapValue(source, "Handler") != null || mapValue(source, "ItemId") != null) {
            Object handlerValue = firstMapValue(source, "Handler", "Provider");
            Object itemIdValue = firstMapValue(source, "ItemId", "ID", "Id");
            if (handlerValue == null || itemIdValue == null) return null;
            String provider = normalizeProviderId(String.valueOf(handlerValue));
            recordItemProviderUse(provider, context);
            Object amountValue = firstMapValue(source, "Amount", "amount");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("ID", String.valueOf(itemIdValue));
            data.put("Amount", integerValue(amountValue, 1));
            return adaptedItem(provider, data);
        }

        Object providerValue = mapValue(source, "Provider");
        Object dataValue = mapValue(source, "Data");
        if (providerValue != null && dataValue instanceof Map<?, ?> rawData) {
            String provider = normalizeProviderId(String.valueOf(providerValue));
            recordItemProviderUse(provider, context);
            Map<String, Object> data = normalizeMap(rawData);
            sanitizeProviderData(provider, data, context);
            return adaptedItem(provider, data);
        }

        return null;
    }

    private Map<String, Object> vanillaItem(String itemTag, int dataVersion, String context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("Value", sanitizeUnsupportedHiddenComponents(itemTag, context));
        data.put("DataVersion", dataVersion);
        return adaptedItem("vanilla", data);
    }

    private static Map<String, Object> adaptedItem(String provider, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("Provider", provider);
        result.put("Data", data);
        return result;
    }

    private void sanitizeProviderData(String provider, Map<String, Object> data, String context) {
        if (!"vanilla".equalsIgnoreCase(provider)) return;
        Object value = mapValue(data, "Value");
        if (value instanceof String itemTag) {
            String sanitized = sanitizeUnsupportedHiddenComponents(itemTag, context);
            for (String key : new ArrayList<>(data.keySet())) {
                if ("value".equalsIgnoreCase(key)) data.put(key, sanitized);
            }
        }
    }

    private String sanitizeUnsupportedHiddenComponents(String itemTag, String context) {
        Matcher matcher = HIDDEN_COMPONENTS.matcher(itemTag);
        StringBuffer result = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            String[] entries = matcher.group(1).split(",");
            List<String> retained = new ArrayList<>();
            for (String entry : entries) {
                String id = entry.trim().replace("\"", "").replace("'", "");
                if ("minecraft:swing_animation".equalsIgnoreCase(id) || "minecraft:map_color".equalsIgnoreCase(id)) {
                    changed = true;
                    this.tooltipComponentsRemoved++;
                }
                else if (!entry.isBlank()) retained.add(entry.trim());
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("hidden_components:[" + String.join(",", retained) + "]"));
        }
        matcher.appendTail(result);
        if (changed && !this.warnings.contains("Removed unsupported minecraft:swing_animation and minecraft:map_color names from hidden tooltip components in legacy item data; item behavior and other item data were retained.")) {
            this.warnings.add("Removed unsupported minecraft:swing_animation and minecraft:map_color names from hidden tooltip components in legacy item data; item behavior and other item data were retained.");
        }
        return changed ? result.toString() : itemTag;
    }

    private String decodeLegacyItemNbt(String encoded) throws Exception {
        byte[] bytes = new BigInteger(encoded, 32).toByteArray();
        ClassLoader serverLoader = Bukkit.getServer().getClass().getClassLoader();
        Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo", true, serverLoader);
        Method read = nbtIo.getMethod("read", DataInput.class);
        Object tag;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            tag = read.invoke(null, input);
        }
        if (tag == null) throw new IOException("NBT decoder returned no tag");
        return tag.toString();
    }

    private static Object mapValue(Map<?, ?> map, String key) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (String.valueOf(entry.getKey()).equalsIgnoreCase(key)) return entry.getValue();
        }
        return null;
    }

    private static Object firstMapValue(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            Object value = mapValue(map, key);
            if (value != null) return value;
        }
        return null;
    }

    private static Map<String, Object> normalizeMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), normalizeValue(value)));
        return result;
    }

    private static int integerValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static String normalizeProviderId(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String compact = value.replace("_", "");
        return switch (compact) {
            case "bukkit", "minecraft", "vanilla" -> "vanilla";
            case "excellentcrates", "excellentcratesitems" -> "excellent_crates";
            case "executableitems" -> "executableitems";
            case "itemsadder" -> "itemsadder";
            case "mmoitems" -> "mmoitems";
            case "oraxen" -> "oraxen";
            case "nexo" -> "nexo";
            case "craftengine" -> "craftengine";
            default -> value;
        };
    }

    private void recordItemProviderUse(String provider, String context) {
        if ("vanilla".equalsIgnoreCase(provider)) return;
        this.itemProviderUses.computeIfAbsent(provider.toLowerCase(Locale.ROOT), ignored -> new LinkedHashSet<>()).add(context);
    }

    private void checkItemProviderPlugins() {
        PluginManager pluginManager = Bukkit.getPluginManager();
        for (Map.Entry<String, Set<String>> entry : this.itemProviderUses.entrySet()) {
            String provider = entry.getKey();
            String pluginName = ITEM_PROVIDER_PLUGINS.get(provider);
            if (pluginName == null) {
                Plugin matchingPlugin = pluginManager.getPlugin(provider);
                String installedNote = matchingPlugin == null ? " No plugin with that name is loaded." :
                    " Plugin '" + matchingPlugin.getName() + "' is loaded, but this converter cannot confirm that it registers the required v7 item provider.";
                this.warnings.add("Custom item provider '" + provider + "' is not one of the converter's recognized v7 providers; "
                    + "its stored item data may load as a dummy item." + installedNote + " Used by: " + providerUseSummary(entry.getValue()));
                continue;
            }

            Plugin plugin = pluginManager.getPlugin(pluginName);
            if (plugin == null) {
                this.warnings.add("Required plugin '" + pluginName + "' for item provider '" + provider + "' was not found among loaded plugins. "
                    + "Those custom items cannot resolve until the matching plugin and v7 integration are installed. Used by: "
                    + providerUseSummary(entry.getValue()));
            }
            else {
                this.successes.add("item provider '" + provider + "' matched plugin '" + plugin.getName() + "'"
                    + (plugin.isEnabled() ? " (enabled)" : " (loaded; awaiting startup enable)"));
            }
        }
    }

    private void scheduleFinalItemProviderCheck() {
        if (this.itemProviderUses.isEmpty()) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            List<String> statuses = new ArrayList<>();
            PluginManager pluginManager = Bukkit.getPluginManager();
            for (Map.Entry<String, Set<String>> entry : this.itemProviderUses.entrySet()) {
                String provider = entry.getKey();
                String pluginName = ITEM_PROVIDER_PLUGINS.get(provider);
                ItemProviderCheck check = inspectRegisteredItemProvider(provider);
                Plugin dependency = pluginName == null ? pluginManager.getPlugin(provider) : pluginManager.getPlugin(pluginName);
                boolean dependencyReady = dependency != null && dependency.isEnabled();
                String dependencyLabel = dependency == null ? (pluginName == null ? "a matching plugin" : "'" + pluginName + "'")
                    : "'" + dependency.getName() + "'";

                if (check == null || !check.registryReady()) {
                    String message = "PROVIDER CHECK INCOMPLETE: could not inspect NightCore's item-provider registry for '" + provider
                        + "'. Confirm NightCore initialized and that the v7 integration registers this provider. Affected data: "
                        + providerUseSummary(entry.getValue());
                    getLogger().warning(message);
                    statuses.add("WARNING: " + message);
                }
                else if ("itemsadder".equals(provider) && !nightCoreCanLoadClass("dev.lone.itemsadder.api.CustomStack")) {
                    String message = "PROVIDER API MISSING: NightCore cannot load dev.lone.itemsadder.api.CustomStack, which its ItemsAdder integration requires. "
                        + "The ItemsAdder build may be incompatible with NightCore 2.16.7 or its API may not be available to NightCore. "
                        + "Affected data: " + providerUseSummary(entry.getValue());
                    getLogger().severe(message);
                    statuses.add("WARNING: " + message);
                }
                else if (!check.registered()) {
                    String message = "PROVIDER NOT REGISTERED: NightCore has no v7 item provider for '" + provider
                        + "'; these custom items will load as dummy items. Check that " + dependencyLabel
                        + " and its ExcellentCrates integration are installed. Affected data: " + providerUseSummary(entry.getValue());
                    getLogger().severe(message);
                    statuses.add("WARNING: " + message);
                }
                else if (!check.available()) {
                    String message = "PROVIDER UNAVAILABLE: NightCore registered '" + provider + "', but its provider reports unavailable; "
                        + "check that " + dependencyLabel + " is installed, enabled, and compatible. Affected data: "
                        + providerUseSummary(entry.getValue());
                    getLogger().severe(message);
                    statuses.add("WARNING: " + message);
                }
                else if (pluginName != null && !dependencyReady) {
                    String message = "PROVIDER CHECK MISMATCH: NightCore reports '" + provider + "' available, but required plugin '"
                        + pluginName + "' is missing or disabled. Affected data: " + providerUseSummary(entry.getValue());
                    getLogger().severe(message);
                    statuses.add("WARNING: " + message);
                }
                else {
                    String providerType = pluginName == null ? "custom" : "v7";
                    String message = "PROVIDER READY: '" + provider + "' " + providerType + " provider is registered and available"
                        + (dependency == null ? "." : " through '" + dependency.getName() + "'.");
                    getLogger().info(message);
                    statuses.add(message);
                }
            }
            if (statuses.isEmpty()) return;
            Path report = this.targetRoot.resolve("conversion-report.txt");
            try {
                Files.write(report, List.of("", "Item provider startup check:"), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                Files.write(report, statuses, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            }
            catch (IOException exception) {
                getLogger().warning("Could not append the final item provider check to conversion-report.txt: " + concise(exception));
            }
        }, 1L);
    }

    private ItemProviderCheck inspectRegisteredItemProvider(String provider) {
        PluginManager pluginManager = Bukkit.getPluginManager();
        Plugin nightCore = pluginManager.getPlugin("NightCore");
        if (nightCore == null) nightCore = pluginManager.getPlugin("nightcore");
        if (nightCore == null) return null;

        try {
            ClassLoader loader = nightCore.getClass().getClassLoader();
            Class<?> bridge = Class.forName("su.nightexpress.nightcore.integration.item.ItemBridge", true, loader);
            boolean bridgeReady = (boolean) bridge.getMethod("isAvailable").invoke(null);
            if (!bridgeReady) return new ItemProviderCheck(false, false, false);

            Object bridgeProvider = bridge.getMethod("get").invoke(null);
            Object registeredProvider = bridgeProvider.getClass().getMethod("getRegisteredProvider", String.class)
                .invoke(bridgeProvider, provider);
            if (registeredProvider == null) return new ItemProviderCheck(true, false, false);

            Object itemProvider = registeredProvider.getClass().getMethod("provider").invoke(registeredProvider);
            boolean available = (boolean) itemProvider.getClass().getMethod("isAvailable").invoke(itemProvider);
            return new ItemProviderCheck(true, true, available);
        }
        catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            getLogger().warning("Could not inspect NightCore's provider registry for '" + provider + "': " + concise(exception));
            return null;
        }
    }

    private boolean nightCoreCanLoadClass(String className) {
        Plugin nightCore = Bukkit.getPluginManager().getPlugin("NightCore");
        if (nightCore == null) nightCore = Bukkit.getPluginManager().getPlugin("nightcore");
        if (nightCore == null) return false;
        try {
            Class.forName(className, false, nightCore.getClass().getClassLoader());
            return true;
        }
        catch (ClassNotFoundException | LinkageError | RuntimeException exception) {
            return false;
        }
    }

    private static String providerUseSummary(Set<String> contexts) {
        List<String> firstUses = contexts.stream().limit(5).toList();
        String result = String.join(", ", firstUses);
        if (contexts.size() > firstUses.size()) result += ", and " + (contexts.size() - firstUses.size()) + " more";
        return result;
    }

    private List<String> rewriteCommands(List<String> commands, String context) {
        List<String> rewritten = new ArrayList<>(commands.size());
        for (String command : commands) {
            Matcher matcher = LEGACY_KEY_COMMAND.matcher(command);
            boolean legacyKeyCommand = matcher.find();
            if (legacyKeyCommand) this.legacyKeyCommandsRewritten++;
            String updated = legacyKeyCommand ? matcher.replaceFirst(Matcher.quoteReplacement(matcher.group(1) + "cratekey")) : command;
            rewritten.add(rewriteLegacyPlaceholders(updated));
        }
        return rewritten;
    }

    private List<String> rewriteLegacyPlaceholders(List<String> values) {
        return values.stream().map(this::rewriteLegacyPlaceholders).toList();
    }

    private String rewriteLegacyPlaceholders(String text) {
        String result = text;
        for (String legacy : LEGACY_PAPI_PLACEHOLDERS) {
            Pattern pattern = Pattern.compile("%excellentcrates_" + Pattern.quote(legacy) + "_([^%]+)%", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(result);
            StringBuffer output = new StringBuffer();
            while (matcher.find()) {
                String oldPlaceholder = matcher.group();
                if ("cooldown".equals(legacy)) {
                    String crateId;
                    try { crateId = safeId(matcher.group(1)); }
                    catch (IllegalArgumentException exception) {
                        if (this.reportedPlaceholderWarnings.add(oldPlaceholder.toLowerCase(Locale.ROOT))) {
                            this.warnings.add("Legacy cooldown placeholder '" + oldPlaceholder + "' has no usable crate ID and was left unchanged.");
                        }
                        matcher.appendReplacement(output, Matcher.quoteReplacement(oldPlaceholder));
                        continue;
                    }
                    this.legacyCooldownPlaceholdersRewritten++;
                    String replacement = "%excellentcrates_crate_effective_cooldown_" + crateId + "%";
                    if (crateId.contains("_") && this.reportedPlaceholderWarnings.add("underscore-id:" + crateId)) {
                        this.warnings.add("Rewritten cooldown placeholder uses crate ID '" + crateId + "' with underscores; v7's PlaceholderAPI resolver splits IDs at the final underscore, so this token may not resolve. Rename the crate to an ID without underscores if needed.");
                    }
                    matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
                }
                else {
                    if (this.reportedPlaceholderWarnings.add(oldPlaceholder.toLowerCase(Locale.ROOT))) {
                        this.warnings.add("Legacy PlaceholderAPI token '" + oldPlaceholder + "' has no direct v7.2.0 equivalent and was left unchanged.");
                    }
                    matcher.appendReplacement(output, Matcher.quoteReplacement(oldPlaceholder));
                }
            }
            matcher.appendTail(output);
            result = output.toString();
        }
        return result;
    }

    private void convertRewardLimits(YamlConfiguration old, YamlConfiguration target) {
        String limitsPath = old.contains("Limits") ? "Limits" : old.contains("Win_Limit") ? "Win_Limit" : "Win_Limits";
        if (!old.contains(limitsPath)) return;

        boolean masterEnabled = old.getBoolean(limitsPath + ".Enabled", false);
        int globalAmount = old.getInt(limitsPath + ".GlobalAmount", old.getInt(limitsPath + ".Global.Amount", -1));
        int playerAmount = old.getInt(limitsPath + ".PlayerAmount", old.getInt(limitsPath + ".Player.Amount", -1));
        boolean globalEnabled = old.getBoolean(limitsPath + ".Global.Enabled", masterEnabled && globalAmount >= 0) && globalAmount >= 0;
        boolean playerEnabled = old.getBoolean(limitsPath + ".Player.Enabled", masterEnabled && playerAmount >= 0) && playerAmount >= 0;
        long globalCooldown = old.getLong(limitsPath + ".GlobalCooldown", old.getLong(limitsPath + ".Global.Cooldown", 0L));
        long playerCooldown = old.getLong(limitsPath + ".PlayerCooldown", old.getLong(limitsPath + ".Player.Cooldown", 0L));
        String modeRaw = old.getString(limitsPath + ".CooldownType", "CUSTOM");

        if ("Win_Limits".equals(limitsPath)) {
            int legacyAmount = old.getInt(limitsPath + ".Amount", -1);
            long legacyCooldown = old.getLong(limitsPath + ".Cooldown", 0L);
            if (legacyAmount >= 0) {
                playerEnabled = true;
                playerAmount = legacyAmount;
                masterEnabled = true;
            }
            if (legacyCooldown != 0L) {
                playerCooldown = legacyCooldown;
                masterEnabled = true;
            }
        }
        boolean hasLimits = masterEnabled && (globalEnabled || playerEnabled);

        if (hasLimits) {
            target.set("limit.enabled", true);
            target.set("limit.global.Enabled", globalEnabled);
            target.set("limit.global.Amount", Math.max(0, globalAmount));
            target.set("limit.individual.Enabled", playerEnabled);
            target.set("limit.individual.Amount", Math.max(0, playerAmount));
        }

        if (masterEnabled && (globalCooldown != 0L || playerCooldown != 0L)) {
            boolean daily = "DAILY".equalsIgnoreCase(modeRaw) || globalCooldown == -2L || playerCooldown == -2L;
            String mode = daily ? "DAILY" : "CUSTOM";
            long globalDuration = daily ? (globalCooldown == 0L ? 0L : 1L) : Math.max(0L, globalCooldown);
            long playerDuration = daily ? (playerCooldown == 0L ? 0L : 1L) : Math.max(0L, playerCooldown);
            target.set("cooldown.global.enabled", globalCooldown != 0L);
            target.set("cooldown.global.mode", mode);
            target.set("cooldown.global.value", globalDuration);
            target.set("cooldown.individual.enabled", playerCooldown != 0L);
            target.set("cooldown.individual.mode", mode);
            target.set("cooldown.individual.value", playerDuration);
        }
    }

    private void convertCosts(YamlConfiguration old, YamlConfiguration result,
                              Map<String, String> knownKeys, String crateId) throws IOException {
        Map<String, Integer> keyChoices = new LinkedHashMap<>();
        int unsupportedActiveCosts = 0;
        boolean hasActiveCostOption = false;

        List<String> legacyKeyIds = old.getStringList("Key.Ids");
        if (!legacyKeyIds.isEmpty()) {
            if (old.getBoolean("Key.Required", false)) {
                for (String rawKeyId : legacyKeyIds) {
                    String keyId = knownKeys.get(rawKeyId.toLowerCase(Locale.ROOT));
                    if (keyId == null) {
                        unsupportedActiveCosts++;
                        this.warnings.add("Crate '" + crateId + "' requires legacy key '" + rawKeyId
                            + "', but that key was not converted. A cost lock will be used if no supported key cost remains.");
                        continue;
                    }
                    keyChoices.merge(keyId, 1, Math::min);
                }
            }
            else {
                this.warnings.add("Crate '" + crateId + "' had legacy key entries with Key.Required disabled. They were not enabled, matching the v6 setting.");
            }
        }

        for (String optionId : sectionKeys(old, "CostOptions")) {
            String optionPath = "CostOptions." + optionId;
            if (!old.getBoolean(optionPath + ".Enabled", true)) continue;
            hasActiveCostOption = true;

            List<String> entryIds = sectionKeys(old, optionPath + ".Entries");
            if (entryIds.size() != 1) {
                // In v6, entries within one option can be combined. v7 key requirements
                // cannot express a mixed or multi-entry cost as a single atomic option.
                unsupportedActiveCosts++;
                continue;
            }

            String entryPath = optionPath + ".Entries." + entryIds.get(0);
            String type = old.getString(entryPath + ".Type", "").trim();
            if (!"key".equalsIgnoreCase(type)) {
                unsupportedActiveCosts++;
                continue;
            }

            String rawKeyId = old.getString(entryPath + ".Key", "").trim();
            String keyId = knownKeys.get(rawKeyId.toLowerCase(Locale.ROOT));
            int amount = old.getInt(entryPath + ".Amount", 1);
            if (keyId == null || amount < 1) {
                unsupportedActiveCosts++;
                continue;
            }
            // Separate v6 cost options are alternatives. If the same key appears at
            // multiple amounts, the lowest valid amount preserves the cheapest option.
            keyChoices.merge(keyId, amount, Math::min);
        }

        // In the v6.6.1 loader, legacy Opening.Cost entries inherit Key.Required
        // as their Enabled flag. Do not turn a disabled legacy cost into a lock.
        if (!hasActiveCostOption && old.getBoolean("Key.Required", false) && hasActiveLegacyOpeningCost(old)) {
            unsupportedActiveCosts++;
        }

        if (!keyChoices.isEmpty()) {
            result.set("key_requirements.enabled", true);
            for (Map.Entry<String, Integer> choice : keyChoices.entrySet()) {
                result.set("key_requirements.keys." + choice.getKey() + ".amount", choice.getValue());
            }
            this.crateCostRequirementsEnabled++;
            this.successes.add("crate " + crateId + ": enabled v7 key requirement with "
                + keyChoices.size() + " supported key payment choice(s)");
            if (unsupportedActiveCosts > 0) {
                this.warnings.add("Crate '" + crateId + "' had " + unsupportedActiveCosts
                    + " active currency, custom, combined, or invalid cost option(s) that v7.2.0 cannot represent. "
                    + "Its supported key choices were converted and enabled; unsupported options remain in the backup.");
            }
            return;
        }

        if (unsupportedActiveCosts > 0) {
            String lockKey = createCostReviewLockKey();
            result.set("key_requirements.enabled", true);
            result.set("key_requirements.keys." + lockKey + ".amount", 1);
            this.crateCostRequirementsEnabled++;
            this.cratesLockedForCostReview.add(crateId);
            this.warnings.add("Crate '" + crateId + "' had active costs that could not be safely converted. "
                + "An enabled v7 key requirement now requires virtual key '" + lockKey
                + "', so the crate cannot open for ordinary players until an administrator configures a supported cost. "
                + "Original cost settings remain in the backup.");
        }
    }

    private boolean hasActiveLegacyOpeningCost(YamlConfiguration old) {
        String path = "Opening.Cost";
        if (!old.contains(path)) return false;
        if (old.contains(path + ".Enabled") && !old.getBoolean(path + ".Enabled", true)) return false;

        ConfigurationSection section = old.getConfigurationSection(path);
        if (section == null) return hasPositiveOrUnrecognizedCostValue(old.get(path));

        boolean hasAmount = false;
        for (String key : section.getKeys(true)) {
            Object value = section.get(key);
            if (value instanceof ConfigurationSection || "Enabled".equalsIgnoreCase(key)) continue;
            if (value == null) continue;
            hasAmount = true;
            if (hasPositiveOrUnrecognizedCostValue(value)) return true;
        }
        // An explicitly enabled but incomplete cost configuration must fail closed.
        return !hasAmount && old.getBoolean(path + ".Enabled", false);
    }

    private static boolean hasPositiveOrUnrecognizedCostValue(Object value) {
        if (value instanceof Number number) return number.doubleValue() > 0D;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) {
            String text = string.trim();
            if (text.isEmpty() || "false".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text)) return false;
            try {
                return Double.parseDouble(text) > 0D;
            }
            catch (NumberFormatException ignored) {
                return true;
            }
        }
        return value != null;
    }

    private String createCostReviewLockKey() throws IOException {
        String id;
        Path file;
        do {
            id = "migration_cost_review_lock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            file = this.stageRoot.resolve("objects").resolve("key").resolve(id + ".yml");
        }
        while (Files.exists(file));

        YamlConfiguration lockKey = new YamlConfiguration();
        lockKey.set("base.virtual", true);
        lockKey.set("display.name", "Cost Migration Lock (Review Required)");
        lockKey.set("display.lore", List.of(
            "This key blocks a crate whose old cost could not be converted.",
            "Configure a supported cost, then remove this migration requirement."
        ));
        lockKey.set("item.stackable", true);
        lockKey.set("item.inherit_display_settings", true);
        saveYaml(lockKey, file);
        this.costReviewLockKeysCreated++;
        this.successes.add("created virtual cost-review lock key " + id);
        return id;
    }

    private int convertBlockPositions(List<String> positions, YamlConfiguration result, String crateId) {
        int count = 0;
        for (String raw : positions) {
            String[] parts = raw.split(",", 4);
            if (parts.length != 4) {
                this.warnings.add("Crate '" + crateId + "' has an unreadable block position: " + raw);
                continue;
            }
            try {
                int x = (int) Double.parseDouble(parts[0].trim());
                int y = (int) Double.parseDouble(parts[1].trim());
                int z = (int) Double.parseDouble(parts[2].trim());
                String legacyWorldName = parts[3].trim();
                String world = resolveWorldKey(legacyWorldName, crateId, raw);
                String key = "blocks.positions." + world;
                List<String> values = new ArrayList<>(result.getStringList(key));
                // v7 ExactPos serializes x,y,z,pitch,yaw. Legacy block locations
                // have no orientation, so both rotation values default to zero.
                values.add(x + "," + y + "," + z + ",0,0");
                result.set(key, values);
                count++;
            }
            catch (NumberFormatException exception) {
                this.warnings.add("Crate '" + crateId + "' has an unreadable block position: " + raw);
            }
        }
        return count;
    }

    private int convertRarities() throws IOException {
        Map<String, Map<String, Object>> rarities = new LinkedHashMap<>();
        ConfigurationSection section = this.oldGlobalConfig.getConfigurationSection("Rewards.Rarities");
        if (section != null) {
            for (String oldId : section.getKeys(false)) {
                ConfigurationSection rarity = section.getConfigurationSection(oldId);
                if (rarity == null) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("name", rarity.getString("Name", oldId));
                values.put("weight", rarity.getDouble("Weight", rarity.getDouble("Chance", 0.0D)));
                values.put("color", Map.of("red", 255, "green", 255, "blue", 255, "alpha", 255));
                rarities.put(safeId(oldId), values);
            }
        }
        if (rarities.isEmpty()) {
            Path oldRarityFile = this.sourceRoot.resolve("rarity.yml");
            if (Files.isRegularFile(oldRarityFile)) {
                YamlConfiguration oldRarities = loadYaml(oldRarityFile, oldRarityFile.toString());
                for (String oldId : oldRarities.getKeys(false)) {
                    ConfigurationSection rarity = oldRarities.getConfigurationSection(oldId);
                    if (rarity == null) continue;
                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put("name", rarity.getString("Name", oldId));
                    values.put("weight", rarity.getDouble("Weight", rarity.getDouble("Chance", 0.0D)));
                    values.put("color", Map.of("red", 255, "green", 255, "blue", 255, "alpha", 255));
                    rarities.put(safeId(oldId), values);
                }
            }
        }

        for (Map.Entry<String, Map<String, Object>> rarity : rarities.entrySet()) {
            YamlConfiguration target = new YamlConfiguration();
            for (Map.Entry<String, Object> field : rarity.getValue().entrySet()) {
                target.set("base." + field.getKey(), field.getValue());
            }
            saveYaml(target, this.stageRoot.resolve("objects").resolve("rarity").resolve(rarity.getKey() + ".yml"));
            this.successes.add("rarity " + rarity.getKey());
        }
        if (rarities.isEmpty()) this.warnings.add("No legacy rarities were found. ExcellentCrates 7.2.0 will create its defaults.");
        return rarities.size();
    }

    private DatabaseResult migrateSqliteKeyBalances(Path oldRoot, Path newRoot) {
        List<Path> databases;
        try (Stream<Path> stream = Files.walk(oldRoot)) {
            databases = stream.filter(Files::isRegularFile)
                .filter(ExcellentCratesV6Converter::isSqliteFile)
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
        catch (IOException exception) {
            return new DatabaseResult(0, null, "Could not inspect old data files for a local SQLite database: " + concise(exception));
        }

        if (databases.isEmpty()) {
            return new DatabaseResult(0, null, "No local SQLite database was found. Remote database settings and any external data remain in the backup; player balances were not imported.");
        }
        if (databases.size() > 1) {
            return new DatabaseResult(0, null, "Found multiple local SQLite databases. To avoid importing the wrong one, all were retained in the backup and no database rows were imported.");
        }

        Path oldDb = databases.getFirst();
        Driver driver = findSqliteDriver();
        if (driver == null) {
            return new DatabaseResult(0, oldDb.getFileName().toString(), "SQLite data was found, but no SQLite JDBC driver was available at startup. The complete database remains in the backup; player balances were not imported.");
        }

        Map<KeyBalanceKey, KeyBalance> balances = new LinkedHashMap<>();
        boolean foundKeyColumns = false;
        try (Connection connection = connect(driver, oldDb); Statement statement = connection.createStatement();
             ResultSet tables = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            List<String> tableNames = new ArrayList<>();
            while (tables.next()) tableNames.add(tables.getString(1));
            for (String tableName : tableNames) {
                String quoted = quoteSqlIdentifier(tableName);
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT * FROM " + quoted)) {
                    ResultSetMetaData metadata = rows.getMetaData();
                    int keyColumn = -1;
                    int heldColumn = -1;
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        String name = normalizeColumn(metadata.getColumnLabel(i));
                        if (name.equals("keys")) keyColumn = i;
                        if (name.equals("keysonhold")) heldColumn = i;
                    }
                    if (keyColumn < 0 && heldColumn < 0) continue;
                    foundKeyColumns = true;
                    while (rows.next()) {
                        UUID uuid = findUuid(rows, metadata);
                        if (uuid == null) continue;
                        if (keyColumn > 0) addBalances(balances, uuid, rows.getString(keyColumn), true);
                        if (heldColumn > 0) addBalances(balances, uuid, rows.getString(heldColumn), false);
                    }
                }
            }
        }
        catch (Exception exception) {
            return new DatabaseResult(0, oldDb.getFileName().toString(), "Could not read the old SQLite user table: " + concise(exception) + ". The database remains in the backup.");
        }

        if (!foundKeyColumns) {
            return new DatabaseResult(0, oldDb.getFileName().toString(), "The old SQLite database has no 6.6.1 key balance columns. It remains in the backup; other historical database records were not imported.");
        }
        if (balances.isEmpty()) {
            return new DatabaseResult(0, oldDb.getFileName().toString(), "The old SQLite database had key columns but no readable key balances. It remains in the backup.");
        }

        Path targetDb = newRoot.resolve("data.db");
        try (Connection target = connect(driver, targetDb)) {
            target.setAutoCommit(false);
            try (Statement ddl = target.createStatement()) {
                ddl.executeUpdate("CREATE TABLE IF NOT EXISTS key_storage (owner_id TEXT NOT NULL, key_id TEXT NOT NULL, virtual_balance INTEGER NOT NULL DEFAULT 0, unclaimed_amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (owner_id, key_id))");
            }
            try (PreparedStatement insert = target.prepareStatement("INSERT OR REPLACE INTO key_storage (owner_id, key_id, virtual_balance, unclaimed_amount) VALUES (?, ?, ?, ?)")) {
                for (Map.Entry<KeyBalanceKey, KeyBalance> entry : balances.entrySet()) {
                    insert.setString(1, entry.getKey().owner.toString());
                    insert.setString(2, entry.getKey().key);
                    insert.setInt(3, entry.getValue().virtualBalance);
                    insert.setInt(4, entry.getValue().unclaimedAmount);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            target.commit();
        }
        catch (Exception exception) {
            return new DatabaseResult(0, oldDb.getFileName().toString(), "Could not write the v7 key storage table: " + concise(exception) + ". The old database remains in the backup.");
        }

        return new DatabaseResult(balances.size(), oldDb.getFileName().toString(), "Only virtual and unclaimed key balances were imported from SQLite. Reward limits, crate history, and other legacy database rows remain in the backup.");
    }

    private Driver findSqliteDriver() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        loaders.add(getClass().getClassLoader());
        try {
            for (Plugin plugin : getServer().getPluginManager().getPlugins()) loaders.add(plugin.getClass().getClassLoader());
        }
        catch (Exception exception) {
            getLogger().fine("Could not inspect plugin classloaders for SQLite: " + concise(exception));
        }
        for (ClassLoader loader : loaders) {
            try {
                Class<?> driverClass = Class.forName("org.sqlite.JDBC", true, loader);
                return (Driver) driverClass.getDeclaredConstructor().newInstance();
            }
            catch (Exception ignored) {
                // Try the next loaded plugin classloader.
            }
        }
        try {
            var drivers = DriverManager.getDrivers().asIterator();
            while (drivers.hasNext()) {
                Driver driver = drivers.next();
                if (driver.getClass().getName().equals("org.sqlite.JDBC")) return driver;
            }
        }
        catch (Exception ignored) {
            // DriverManager can be empty during STARTUP; no action is needed.
        }
        return null;
    }

    private static Connection connect(Driver driver, Path file) throws SQLException {
        Properties properties = new Properties();
        Connection connection = driver.connect("jdbc:sqlite:" + file.toAbsolutePath(), properties);
        if (connection == null) throw new SQLException("SQLite driver rejected the database URL");
        return connection;
    }

    private static UUID findUuid(ResultSet row, ResultSetMetaData metadata) throws SQLException {
        List<Integer> preferred = new ArrayList<>();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            String column = normalizeColumn(metadata.getColumnLabel(i));
            if (column.contains("uuid") || column.equals("userid") || column.equals("ownerid") || column.equals("playerid")) preferred.add(i);
        }
        for (int i = 1; i <= metadata.getColumnCount(); i++) if (!preferred.contains(i)) preferred.add(i);
        for (int index : preferred) {
            String value = row.getString(index);
            if (value == null) continue;
            Matcher matcher = UUID_PATTERN.matcher(value);
            if (matcher.find()) {
                try { return UUID.fromString(matcher.group()); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return null;
    }

    private static void addBalances(Map<KeyBalanceKey, KeyBalance> destination, UUID owner, String json, boolean virtual) {
        if (json == null || json.isBlank()) return;
        Matcher matcher = JSON_INT_ENTRY.matcher(json);
        while (matcher.find()) {
            String keyId = unescapeJsonString(matcher.group(1));
            int amount;
            try { amount = Integer.parseInt(matcher.group(2)); }
            catch (NumberFormatException ignored) { continue; }
            if (amount == 0) continue;
            String safeKey = safeId(keyId);
            KeyBalanceKey key = new KeyBalanceKey(owner, safeKey);
            KeyBalance current = destination.computeIfAbsent(key, ignored -> new KeyBalance());
            if (virtual) current.virtualBalance = amount;
            else current.unclaimedAmount = amount;
        }
    }

    private void writeStatusFiles(int crateCount, int keyCount) throws IOException {
        String stamp = LocalDateTime.now().format(STAMP);
        Path marker = this.stageRoot.resolve(MARKER);
        String markerContent = "source=ExcellentCrates 6.6.1\ntarget=ExcellentCrates 7.2.0\nconverted=" + stamp + "\n";
        if (this.backupRoot != null) markerContent += "backup=" + this.backupRoot.getFileName() + "\n";
        Files.writeString(marker, markerContent, StandardCharsets.UTF_8);

        List<String> report = new ArrayList<>();
        report.add("ExcellentCrates migration report");
        report.add("Source: 6.6.1");
        report.add("Target: 7.2.0 rewrite format");
        report.add("Converted crate files: " + crateCount);
        report.add("Converted key files: " + keyCount);
        report.add("Converted reward files: " + this.rewardsConverted);
        report.add("Converted reward item stacks: " + this.rewardItemsConverted);
        report.add("Reward item stacks failed or skipped: " + this.rewardItemsFailed);
        report.add("Openings.log reward ID references indexed: " + this.openingLogRewardReferencesIndexed);
        report.add("Duplicate reward ID collisions resolved: " + this.rewardIdCollisionsResolved);
        report.add("Duplicate reward ID groups matched to openings.log: " + this.rewardIdCollisionGroupsMatchedToOpeningLog);
        report.add("Reward preview icons inherited from first given item: " + this.rewardPreviewIconsFromItems);
        report.add("Unsupported hidden tooltip component references removed: " + this.tooltipComponentsRemoved);
        report.add("Legacy key commands rewritten: " + this.legacyKeyCommandsRewritten);
        report.add("Legacy cooldown placeholders rewritten: " + this.legacyCooldownPlaceholdersRewritten);
        report.add("Legacy preview layouts converted: 0; enabled crate previews use the v7 built-in default profile.");
        report.add("Crate cost requirements enabled: " + this.crateCostRequirementsEnabled);
        report.add("Crates locked for cost review: " + this.cratesLockedForCostReview.size());
        report.add("Virtual cost-review lock keys created: " + this.costReviewLockKeysCreated);
        if (!this.cratesLockedForCostReview.isEmpty()) {
            report.add("Crates locked: " + String.join(", ", this.cratesLockedForCostReview));
        }
        report.add("Converted rarity files: " + this.raritiesConverted);
        report.add("Converted Simple Roll opening profiles: " + this.openingProfilesConverted);
        report.add("Converted block-effect profiles: " + this.blockEffectProfilesConverted);
        report.add("Unsupported block-effect models skipped: " + this.blockEffectProfilesSkipped);
        report.add("Block-effect particle fallbacks to FLAME: " + this.blockEffectParticleFallbacks);
        report.add("Crate holograms using converted legacy templates: " + this.hologramTemplatesApplied);
        report.add("Imported player key balance rows: " + this.keyBalancesConverted);
        if (this.backupRoot != null) report.add("Backup: " + this.backupRoot.toAbsolutePath());
        if (this.previousConvertedRoot != null) report.add("Previous converted folder: " + this.previousConvertedRoot.toAbsolutePath());
        report.add("");
        report.add("Warnings and failures:");
        report.addAll(this.warnings.stream().map(s -> "WARNING: " + s).toList());
        report.addAll(this.failures.stream().map(s -> "FAILED: " + s).toList());
        if (this.warnings.isEmpty() && this.failures.isEmpty()) report.add("None");
        Files.write(this.stageRoot.resolve("conversion-report.txt"), report, StandardCharsets.UTF_8);
    }

    private void printReport(Path backup) {
        getLogger().info("============================================================");
        getLogger().info("ExcellentCrates 6.6.1 -> 7.2.0 conversion summary");
        getLogger().info("Crates: " + this.cratesConverted + " successful; keys: " + this.keysConverted
            + " successful; rewards: " + this.rewardsConverted + " successful; rarities: " + this.raritiesConverted + " successful.");
        getLogger().info("Reward item stacks: " + this.rewardItemsConverted + " converted; " + this.rewardItemsFailed + " failed or skipped; unsupported hidden tooltip references removed: " + this.tooltipComponentsRemoved + ".");
        getLogger().info("Reward IDs: " + this.rewardIdCollisionsResolved + " duplicate collision(s) resolved; "
            + this.rewardIdCollisionGroupsMatchedToOpeningLog + " duplicate group(s) matched to openings.log; "
            + this.openingLogRewardReferencesIndexed + " log reference(s) indexed.");
        getLogger().info("Reward display icons inherited from first given item: " + this.rewardPreviewIconsFromItems + ".");
        getLogger().info("Legacy crate-key commands rewritten: " + this.legacyKeyCommandsRewritten + "; legacy cooldown placeholders rewritten: " + this.legacyCooldownPlaceholdersRewritten + ".");
        getLogger().info("Crate cost requirements enabled: " + this.crateCostRequirementsEnabled
            + "; crates locked for cost review: " + this.cratesLockedForCostReview.size()
            + "; virtual lock keys created: " + this.costReviewLockKeysCreated + ".");
        getLogger().info("Block locations converted: " + this.blockPositionsConverted
            + "; player key balance rows imported: " + this.keyBalancesConverted + ".");
        getLogger().info("Legacy preview layouts converted: 0; enabled crate previews use the v7 built-in default profile.");
        getLogger().info("Simple Roll profiles converted: " + this.openingProfilesConverted
            + "; block-effect profiles converted: " + this.blockEffectProfilesConverted
            + "; unsupported block-effect models skipped: " + this.blockEffectProfilesSkipped
            + "; block-effect particles replaced with FLAME: " + this.blockEffectParticleFallbacks
            + "; crate holograms using legacy template text: " + this.hologramTemplatesApplied + ".");
        for (String success : this.successes) getLogger().info("SUCCESS: " + success);
        for (String warning : this.warnings) getLogger().warning("NOT IMPORTED / REVIEW: " + warning);
        for (String failure : this.failures) getLogger().severe("FAILED: " + failure);
        if (backup != null) getLogger().info("Complete v6.6.1 data folder moved to: " + backup.toAbsolutePath());
        if (this.previousConvertedRoot != null) {
            getLogger().info("Previous converted folder preserved at: " + this.previousConvertedRoot.toAbsolutePath());
        }
        if (this.failures.isEmpty() && this.warnings.isEmpty()) {
            getLogger().info("All detected data was converted successfully.");
        }
        else {
            getLogger().warning("Conversion completed with items needing review. See conversion-report.txt in plugins/ExcellentCrates.");
        }
        getLogger().info("============================================================");
    }

    private static YamlConfiguration loadYaml(Path file, String label) throws IOException {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file.toFile());
            return config;
        }
        catch (Exception exception) {
            throw new IOException("Could not read YAML " + label + ": " + concise(exception), exception);
        }
    }

    private static YamlConfiguration loadYamlFromSection(YamlConfiguration parent, String path) throws IOException {
        Object raw = parent.get(path);
        if (!(raw instanceof Map<?, ?> map)) {
            ConfigurationSection section = parent.getConfigurationSection(path);
            if (section == null) throw new IOException("Reward section is not a YAML mapping: " + path);
            YamlConfiguration config = new YamlConfiguration();
            copyMap(config, "", section.getValues(false));
            return config;
        }
        YamlConfiguration config = new YamlConfiguration();
        copyMap(config, "", map);
        return config;
    }

    private static void copyMap(YamlConfiguration config, String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = entry.getValue();
            if (value instanceof ConfigurationSection section) copyMap(config, path, section.getValues(false));
            else if (value instanceof Map<?, ?> nested) copyMap(config, path, nested);
            else config.set(path, normalizeValue(value));
        }
    }

    private static void saveYaml(YamlConfiguration config, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        try {
            config.save(output.toFile());
        }
        catch (IOException exception) {
            throw new IOException("Could not write " + output + ": " + concise(exception), exception);
        }
    }

    private static Object firstValue(YamlConfiguration config, String... paths) {
        for (String path : paths) {
            if (config.contains(path)) return normalizeValue(config.get(path));
        }
        return null;
    }

    private static Object normalizeValue(Object value) {
        if (value instanceof ConfigurationSection section) {
            Map<String, Object> converted = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) converted.put(key, normalizeValue(section.get(key)));
            return converted;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, nested) -> converted.put(String.valueOf(key), normalizeValue(nested)));
            return converted;
        }
        if (value instanceof List<?> list) return list.stream().map(ExcellentCratesV6Converter::normalizeValue).toList();
        return value;
    }

    private static String inferRewardType(YamlConfiguration config) {
        return config.getStringList("Commands").isEmpty() ? "ITEM" : "COMMAND";
    }

    private String defaultRarityId() {
        ConfigurationSection section = this.oldGlobalConfig.getConfigurationSection("Rewards.Rarities");
        if (section != null && !section.getKeys(false).isEmpty()) return safeId(section.getKeys(false).iterator().next());
        if (Files.isRegularFile(this.sourceRoot.resolve("rarity.yml"))) {
            try {
                YamlConfiguration rarities = loadYaml(this.sourceRoot.resolve("rarity.yml"), "rarity.yml");
                if (!rarities.getKeys(false).isEmpty()) return safeId(rarities.getKeys(false).iterator().next());
            }
            catch (IOException ignored) { }
        }
        return "common";
    }

    private static List<String> defaultHologramText() {
        return List.of("%crate_name%", "", "%crate_description%", "",
            "<white>Left-Click</white><gray> to preview the crate",
            "<white>Right-Click</white><gray> to open the crate");
    }

    private static String safeWorldKey(String name) {
        String raw = name.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (raw.contains(":")) return raw;
        return "minecraft:" + raw.replaceAll("[^a-z0-9_.-]", "_");
    }

    private String resolveWorldKey(String legacyWorldName, String crateId, String rawPosition) {
        for (World world : Bukkit.getWorlds()) {
            if (!world.getName().equalsIgnoreCase(legacyWorldName)) continue;

            return world.getKey().toString().toLowerCase(Locale.ROOT);
        }

        String normalized = legacyWorldName.toLowerCase(Locale.ROOT).replace(' ', '_');
        String worldKey = switch (normalized) {
            case "world" -> "minecraft:overworld";
            case "world_nether" -> "minecraft:the_nether";
            case "world_the_end" -> "minecraft:the_end";
            default -> safeWorldKey(legacyWorldName);
        };
        String warningKey = crateId.toLowerCase(Locale.ROOT) + ":" + legacyWorldName.toLowerCase(Locale.ROOT);
        if (this.reportedWorldWarnings.add(warningKey)) {
            this.warnings.add("Crate '" + crateId + "' has a placement in world '" + legacyWorldName
                + "', which was not loaded during conversion. Used fallback world key '" + worldKey
                + "'; verify those placements if this is a custom world.");
        }
        return worldKey;
    }

    private static String safeId(String raw) {
        String id = raw == null ? "" : raw.toLowerCase(Locale.ROOT).trim().replace(' ', '_');
        id = id.replaceAll("[^a-z0-9_-]", "_").replaceAll("_+", "_");
        while (id.startsWith("_") || id.startsWith("-")) id = id.substring(1);
        while (id.endsWith("_") || id.endsWith("-")) id = id.substring(0, id.length() - 1);
        if (id.isEmpty()) throw new IllegalArgumentException("Identifier is blank after normalization");
        return id;
    }

    private static List<Path> topLevelYamlFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")
                    || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yaml"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static List<Path> allYamlFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml")
                    || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yaml"))
                .sorted(Comparator.comparing(Path::toString))
                .toList();
        }
    }

    private static List<String> sectionKeys(YamlConfiguration config, String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return List.of();
        return new ArrayList<>(section.getKeys(false));
    }

    private static String fileBase(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static String normalizeColumn(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String quoteSqlIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String unescapeJsonString(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static boolean isSqliteFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".db") || name.endsWith(".sqlite") || name.endsWith(".sqlite3"))) return false;
        try {
            byte[] header = new byte[16];
            try (var input = Files.newInputStream(file)) {
                if (input.read(header) != header.length) return false;
            }
            return new String(header, StandardCharsets.US_ASCII).equals("SQLite format 3\u0000");
        }
        catch (IOException exception) {
            return false;
        }
    }

    private static Path uniqueBackupPath(Path pluginsDir) {
        return uniquePath(pluginsDir, SOURCE_FOLDER + "-v6.6.1-backup-" + LocalDateTime.now().format(STAMP));
    }

    private static Path uniqueConvertedBackupPath(Path pluginsDir) {
        return uniquePath(pluginsDir, SOURCE_FOLDER + "-v7-previous-conversion-" + LocalDateTime.now().format(STAMP));
    }

    private static Path uniquePath(Path pluginsDir, String base) {
        Path path = pluginsDir.resolve(base);
        int suffix = 1;
        while (Files.exists(path)) path = pluginsDir.resolve(base + "-" + suffix++);
        return path;
    }

    private static Path findLatestLegacyBackup(Path pluginsDir, Path marker) throws IOException {
        String prefix = SOURCE_FOLDER + "-v6.6.1-backup-";
        String recordedBackup = null;
        for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
            if (line.startsWith("backup=")) {
                recordedBackup = line.substring("backup=".length()).trim();
                break;
            }
        }
        try (Stream<Path> paths = Files.list(pluginsDir)) {
            List<Path> candidates = paths
                .filter(Files::isDirectory)
                .filter(path -> path.getFileName().toString().startsWith(prefix))
                .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                .toList();
            if (recordedBackup != null && recordedBackup.matches("[A-Za-z0-9._-]+")) {
                for (Path candidate : candidates) {
                    if (candidate.getFileName().toString().equals(recordedBackup) && isLegacyBackup(candidate)) {
                        return candidate;
                    }
                }
            }
            for (Path candidate : candidates) {
                if (isLegacyBackup(candidate)) return candidate;
            }
        }
        return null;
    }

    private static boolean isLegacyBackup(Path candidate) {
        return Files.isRegularFile(candidate.resolve("config.yml"))
            || Files.isDirectory(candidate.resolve("crates"))
            || Files.isDirectory(candidate.resolve("keys"));
    }

    private static void movePath(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void deleteTreeQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); }
                catch (IOException ignored) { }
            });
        }
        catch (IOException ignored) { }
    }

    private static String concise(Throwable exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private record LegacyEffectSpec(String modelId, int maxFrames, int tickInterval) { }
    private record DatabaseResult(int importedRows, String sourceName, String warning) { }
    private record ItemProviderCheck(boolean registryReady, boolean registered, boolean available) { }
    private record KeyBalanceKey(UUID owner, String key) { }
    private static final class KeyBalance {
        private int virtualBalance;
        private int unclaimedAmount;
    }
}
