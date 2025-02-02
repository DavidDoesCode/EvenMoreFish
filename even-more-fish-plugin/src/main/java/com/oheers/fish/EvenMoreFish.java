package com.oheers.fish;

import co.aikar.commands.ConditionFailedException;
import co.aikar.commands.InvalidCommandArgument;
import co.aikar.commands.PaperCommandManager;
import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import com.oheers.fish.addons.AddonManager;
import com.oheers.fish.addons.DefaultAddons;
import com.oheers.fish.api.EMFAPI;
import com.oheers.fish.api.plugin.EMFPlugin;
import com.oheers.fish.api.requirement.RequirementManager;
import com.oheers.fish.api.reward.RewardManager;
import com.oheers.fish.baits.BaitListener;
import com.oheers.fish.baits.BaitManager;
import com.oheers.fish.commands.AdminCommand;
import com.oheers.fish.commands.EMFCommand;
import com.oheers.fish.competition.AutoRunner;
import com.oheers.fish.competition.Competition;
import com.oheers.fish.competition.CompetitionQueue;
import com.oheers.fish.competition.JoinChecker;
import com.oheers.fish.competition.rewardtypes.*;
import com.oheers.fish.competition.rewardtypes.external.*;
import com.oheers.fish.config.*;
import com.oheers.fish.config.messages.ConfigMessage;
import com.oheers.fish.config.messages.Message;
import com.oheers.fish.config.messages.Messages;
import com.oheers.fish.database.DataManager;
import com.oheers.fish.database.DatabaseV3;
import com.oheers.fish.database.FishReport;
import com.oheers.fish.database.UserReport;
import com.oheers.fish.events.*;
import com.oheers.fish.fishing.FishingProcessor;
import com.oheers.fish.fishing.items.Fish;
import com.oheers.fish.fishing.items.FishManager;
import com.oheers.fish.fishing.items.Rarity;
import com.oheers.fish.requirements.*;
import com.oheers.fish.utils.AntiCraft;
import com.oheers.fish.utils.HeadDBIntegration;
import com.oheers.fish.utils.ItemFactory;
import com.oheers.fish.utils.nbt.NbtKeys;
import de.themoep.inventorygui.InventoryGui;
import de.tr7zw.changeme.nbtapi.NBT;
import me.arcaniax.hdb.api.HeadDatabaseAPI;
import net.milkbowl.vault.permission.Permission;
import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import uk.firedev.vanishchecker.VanishChecker;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class EvenMoreFish extends JavaPlugin implements EMFPlugin {
    private final Random random = new Random();

    private Permission permission = null;
    private Economy economy;
    private Map<Integer, Set<String>> fish = new HashMap<>();
    private ItemStack customNBTRod;
    private boolean checkingEatEvent;
    private boolean checkingIntEvent;
    // Do some fish in some rarities have the comp-check-exempt: true.
    private boolean raritiesCompCheckExempt = false;
    private Competition active;
    private CompetitionQueue competitionQueue;
    private Logger logger;
    private PluginManager pluginManager;
    private int metric_fishCaught = 0;
    private int metric_baitsUsed = 0;
    private int metric_baitsApplied = 0;

    // this is for pre-deciding a rarity and running particles if it will be chosen
    // it's a work-in-progress solution and probably won't stick.
    private Map<UUID, Rarity> decidedRarities;
    private boolean isUpdateAvailable;
    private boolean usingVault;
    private boolean usingPAPI;
    private boolean usingMcMMO;
    private boolean usingHeadsDB;
    private boolean usingPlayerPoints;
    private boolean usingGriefPrevention;

    private DatabaseV3 databaseV3;
    private HeadDatabaseAPI HDBapi;

    private static EvenMoreFish instance;
    private static TaskScheduler scheduler;
    private EMFAPI api;

    private AddonManager addonManager;

    public static EvenMoreFish getInstance() {
        return instance;
    }

    public static TaskScheduler getScheduler() {return scheduler;}

    public AddonManager getAddonManager() {
        return addonManager;
    }

    /**
     * @deprecated Use {@link FishManager#getRarityMap()} instead. This method will be removed in EMF 1.8
     */
    @Deprecated(forRemoval = true)
    public Map<Rarity, List<Fish>> getFishCollection() {
        return FishManager.getInstance().getRarityMap();
    }

    @Override
    public void onEnable() {

        if (!NBT.preloadApi()) {
            throw new RuntimeException("NBT-API wasn't initialized properly, disabling the plugin");
        }

        instance = this;
        scheduler = UniversalScheduler.getScheduler(this);
        this.api = new EMFAPI();


        decidedRarities = new HashMap<>();

        logger = getLogger();
        pluginManager = getServer().getPluginManager();

        usingVault = Bukkit.getPluginManager().isPluginEnabled("Vault");
        usingGriefPrevention = Bukkit.getPluginManager().isPluginEnabled("GriefPrevention");
        usingPlayerPoints = Bukkit.getPluginManager().isPluginEnabled("PlayerPoints");

        getConfig().options().copyDefaults();
        saveDefaultConfig();

        new MainConfig();
        new Messages();

        saveAdditionalDefaultAddons();
        this.addonManager = new AddonManager(this);
        this.addonManager.load();

        new FishFile();
        new RaritiesFile();
        new BaitFile();
        new CompetitionConfig();

        new GUIConfig();
        new GUIFillerConfig();

        checkPapi();

        if (MainConfig.getInstance().requireNBTRod()) {
            customNBTRod = createCustomNBTRod();
        }

        if (MainConfig.getInstance().isEconomyEnabled()) {
            // could not set up economy.
            if (!setupEconomy()) {
                EvenMoreFish.getInstance().getLogger().warning("EvenMoreFish won't be hooking into economy. If this wasn't by choice in config.yml, please install Economy handling plugins.");
            }
        }

        setupPermissions();

        loadRequirementManager();

        FishManager.getInstance().load();
        BaitManager.getInstance().load();

        // Do this before anything competition related.
        loadRewardManager();

        competitionQueue = new CompetitionQueue();
        competitionQueue.load();

        // async check for updates on the spigot page
        getScheduler().runTaskAsynchronously(() -> isUpdateAvailable = checkUpdate());

        listeners();
        loadCommandManager();

        if (!MainConfig.getInstance().debugSession()) {
            metrics();
        }

        AutoRunner.init();

        if (MainConfig.getInstance().databaseEnabled()) {

            DataManager.init();

            databaseV3 = new DatabaseV3(this);
            //load user reports into cache
            getScheduler().runTaskAsynchronously(() -> {
                for (Player player : getServer().getOnlinePlayers()) {
                    UserReport playerReport = databaseV3.readUserReport(player.getUniqueId());
                    if (playerReport == null) {
                        EvenMoreFish.getInstance().getLogger().warning("Could not read report for player (" + player.getUniqueId() + ")");
                        continue;
                    }
                    DataManager.getInstance().putUserReportCache(player.getUniqueId(), playerReport);
                }
            });

        }

        logger.log(Level.INFO, "EvenMoreFish by Oheers : Enabled");
    }

    @Override
    public void onDisable() {
        terminateGUIS();
        // Don't use the scheduler here because it will throw errors on disable
        saveUserData(false);

        // Ends the current competition in case the plugin is being disabled when the server will continue running
        if (Competition.isActive()) {
            active.end(false);
        }

        RewardManager.getInstance().unload();

        if (MainConfig.getInstance().databaseEnabled()) {
            databaseV3.shutdown();
        }

        FishManager.getInstance().unload();
        BaitManager.getInstance().unload();

        logger.log(Level.INFO, "EvenMoreFish by Oheers : Disabled");
    }

    private void saveAdditionalDefaultAddons() {
        if (!MainConfig.getInstance().useAdditionalAddons()) {
            return;
        }

        for (final String fileName : Arrays.stream(DefaultAddons.values())
                .map(DefaultAddons::getFullFileName)
                .toList()) {
            final File addonFile = new File(getDataFolder(), "addons/" + fileName);
            final File jarFile = new File(getDataFolder(), "addons/" + fileName.replace(".addon", ".jar"));
            if (!jarFile.exists()) {
                try {
                    this.saveResource("addons/" + fileName, true);
                    addonFile.renameTo(jarFile);
                } catch (IllegalArgumentException e) {
                    debug(Level.WARNING, String.format("Default addon %s does not exist.", fileName));
                }
            }
        }
    }

    public static void debug(final String message) {
        debug(Level.INFO, message);
    }

    public static void debug(final Level level, final String message) {
        if (MainConfig.getInstance().debugSession()) {
            getInstance().getLogger().log(level, () -> message);
        }
    }

    private void listeners() {

        getServer().getPluginManager().registerEvents(new JoinChecker(), this);
        getServer().getPluginManager().registerEvents(new FishingProcessor(), this);
        getServer().getPluginManager().registerEvents(new UpdateNotify(), this);
        getServer().getPluginManager().registerEvents(new SkullSaver(), this);
        getServer().getPluginManager().registerEvents(new BaitListener(), this);

        optionalListeners();
    }

    private void optionalListeners() {
        if (checkingEatEvent) {
            getServer().getPluginManager().registerEvents(FishEatEvent.getInstance(), this);
        }

        if (checkingIntEvent) {
            getServer().getPluginManager().registerEvents(FishInteractEvent.getInstance(), this);
        }

        if (MainConfig.getInstance().blockCrafting()) {
            getServer().getPluginManager().registerEvents(new AntiCraft(), this);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("mcMMO")) {
            usingMcMMO = true;
            if (MainConfig.getInstance().disableMcMMOTreasure()) {
                getServer().getPluginManager().registerEvents(McMMOTreasureEvent.getInstance(), this);
            }
        }

        if (Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) {
            usingHeadsDB = true;
            getServer().getPluginManager().registerEvents(new HeadDBIntegration(), this);
        }

        if (Bukkit.getPluginManager().isPluginEnabled("AureliumSkills")) {
            if (MainConfig.getInstance().disableAureliumSkills()) {
                getServer().getPluginManager().registerEvents(new AureliumSkillsFishingEvent(), this);
            }
        }
        if (Bukkit.getPluginManager().isPluginEnabled("AuraSkills")) {
            if (MainConfig.getInstance().disableAureliumSkills()) {
                getServer().getPluginManager().registerEvents(new AuraSkillsFishingEvent(), this);
            }
        }
    }

    private void metrics() {
        Metrics metrics = new Metrics(this, 11054);

        metrics.addCustomChart(new SingleLineChart("fish_caught", () -> {
            int returning = metric_fishCaught;
            metric_fishCaught = 0;
            return returning;
        }));

        metrics.addCustomChart(new SingleLineChart("baits_applied", () -> {
            int returning = metric_baitsApplied;
            metric_baitsApplied = 0;
            return returning;
        }));

        metrics.addCustomChart(new SingleLineChart("baits_used", () -> {
            int returning = metric_baitsUsed;
            metric_baitsUsed = 0;
            return returning;
        }));

        metrics.addCustomChart(new SimplePie("database", () -> MainConfig.getInstance().databaseEnabled() ? "true" : "false"));
    }

    private void loadCommandManager() {
        PaperCommandManager manager = new PaperCommandManager(this);

        manager.enableUnstableAPI("brigadier");
        manager.enableUnstableAPI("help");

        StringBuilder main = new StringBuilder(MainConfig.getInstance().getMainCommandName());
        List<String> aliases = MainConfig.getInstance().getMainCommandAliases();
        if (!aliases.isEmpty()) {
            aliases.forEach(alias -> main.append("|").append(alias));
        }
        manager.getCommandReplacements().addReplacement("main", main.toString());
        manager.getCommandReplacements().addReplacement("duration", String.valueOf(MainConfig.getInstance().getCompetitionDuration() * 60));
        //desc_admin_<command>_<id>
        manager.getCommandReplacements().addReplacements(
                "desc_admin_bait", new Message(ConfigMessage.HELP_ADMIN_BAIT).getRawMessage(),
                "desc_admin_competition", new Message(ConfigMessage.HELP_ADMIN_COMPETITION).getRawMessage(),
                "desc_admin_clearbaits", new Message(ConfigMessage.HELP_ADMIN_CLEARBAITS).getRawMessage(),
                "desc_admin_fish", new Message(ConfigMessage.HELP_ADMIN_FISH).getRawMessage(),
                "desc_admin_nbtrod", new Message(ConfigMessage.HELP_ADMIN_NBTROD).getRawMessage(),
                "desc_admin_reload", new Message(ConfigMessage.HELP_ADMIN_RELOAD).getRawMessage(),
                "desc_admin_version", new Message(ConfigMessage.HELP_ADMIN_VERSION).getRawMessage(),
                "desc_admin_migrate", new Message(ConfigMessage.HELP_ADMIN_MIGRATE).getRawMessage(),
                "desc_admin_rewardtypes", new Message(ConfigMessage.HELP_ADMIN_REWARDTYPES).getRawMessage(),
                "desc_admin_addons", new Message(ConfigMessage.HELP_ADMIN_ADDONS).getRawMessage(),

                "desc_list_fish", new Message(ConfigMessage.HELP_LIST_FISH).getRawMessage(),
                "desc_list_rarities", new Message(ConfigMessage.HELP_LIST_RARITIES).getRawMessage(),

                "desc_competition_start", new Message(ConfigMessage.HELP_COMPETITION_START).getRawMessage(),
                "desc_competition_end", new Message(ConfigMessage.HELP_COMPETITION_END).getRawMessage(),

                "desc_general_top", new Message(ConfigMessage.HELP_GENERAL_TOP).getRawMessage(),
                "desc_general_help", new Message(ConfigMessage.HELP_GENERAL_HELP).getRawMessage(),
                "desc_general_shop", new Message(ConfigMessage.HELP_GENERAL_SHOP).getRawMessage(),
                "desc_general_toggle", new Message(ConfigMessage.HELP_GENERAL_TOGGLE).getRawMessage(),
                "desc_general_gui", new Message(ConfigMessage.HELP_GENERAL_GUI).getRawMessage(),
                "desc_general_admin", new Message(ConfigMessage.HELP_GENERAL_ADMIN).getRawMessage(),
                "desc_general_next", new Message(ConfigMessage.HELP_GENERAL_NEXT).getRawMessage(),
                "desc_general_sellall", new Message(ConfigMessage.HELP_GENERAL_SELLALL).getRawMessage()
        );


        manager.getCommandConditions().addCondition(Integer.class, "limits", (c, exec, value) -> {
            if (value == null) {
                return;
            }
            if (c.hasConfig("min") && c.getConfigValue("min", 0) > value) {
                throw new ConditionFailedException("Min value must be " + c.getConfigValue("min", 0));
            }

            if (c.hasConfig("max") && c.getConfigValue("max", 0) < value) {
                throw new ConditionFailedException("Max value must be " + c.getConfigValue("max", 0));
            }
        });
        manager.getCommandContexts().registerContext(Rarity.class, c -> {
            final String rarityId = c.popFirstArg().replace("\"", "");
            Rarity rarity = FishManager.getInstance().getRarity(rarityId);
            if (rarity == null) {
                throw new InvalidCommandArgument("No such rarity: " + rarityId);
            }
            return rarity;
        });
        manager.getCommandContexts().registerContext(Fish.class, c -> {
            final Rarity rarity = (Rarity) c.getResolvedArg(Rarity.class);
            final String fishId = c.popFirstArg();
            Fish fish = FishManager.getInstance().getFish(rarity, fishId);
            if (fish == null) {
                fish = FishManager.getInstance().getFish(rarity, fishId.replace("_", " "));
            }
            if (fish == null) {
                throw new InvalidCommandArgument("No such fish: " + fishId);
            }
            return fish;
        });
        manager.getCommandCompletions().registerCompletion("baits", c -> BaitManager.getInstance().getBaitMap().keySet().stream().map(s -> s.replace(" ", "_")).collect(Collectors.toList()));
        manager.getCommandCompletions().registerCompletion("rarities", c -> FishManager.getInstance().getRarityMap().keySet().stream().map(Rarity::getValue).collect(Collectors.toList()));
        manager.getCommandCompletions().registerCompletion("fish", c -> {
            final Rarity rarity = c.getContextValue(Rarity.class);
            return FishManager.getInstance().getRarityMap().get(rarity).stream().map(f -> f.getName().replace(" ", "_")).collect(Collectors.toList());
        });

        manager.registerCommand(new EMFCommand());
        manager.registerCommand(new AdminCommand());

        // Make server admins aware the deprecation warning is nothing to worry about
        getLogger().warning("The above warning, if you are on Paper, can safely be ignored for now, we are waiting for a fix from the developers of our command library.");
    }


    private boolean setupPermissions() {
        if (!usingVault) {
            return false;
        }
        RegisteredServiceProvider<Permission> rsp = getServer().getServicesManager().getRegistration(Permission.class);
        permission = rsp.getProvider();
        return permission != null;
    }

    private boolean setupEconomy() {
        if (MainConfig.getInstance().isEconomyEnabled()) {
            economy = new Economy(MainConfig.getInstance().economyType());
            return economy.isEnabled();
        } else {
            return false;
        }
    }

    // gets called on server shutdown to simulate all players closing their GUIs
    private void terminateGUIS() {
        getServer().getOnlinePlayers().forEach(player -> {
            InventoryGui inventoryGui = InventoryGui.getOpen(player);
            if (inventoryGui != null) {
                inventoryGui.close();
            }
        });
    }

    private void saveUserData(boolean scheduler) {
        Runnable save = () -> {
            if (!(MainConfig.getInstance().isDatabaseOnline())) {
                return;
            }

            saveFishReports();
            saveUserReports();

            DataManager.getInstance().uncacheAll();
        };
        if (scheduler) {
            getScheduler().runTask(save);
        } else {
            save.run();
        }
    }

    private void saveFishReports() {
        ConcurrentMap<UUID, List<FishReport>> allReports = DataManager.getInstance().getAllFishReports();
        logger.info("Saving " + allReports.keySet().size() + " fish reports.");
        for (Map.Entry<UUID, List<FishReport>> entry : allReports.entrySet()) {
            databaseV3.writeFishReports(entry.getKey(), entry.getValue());


            if (!databaseV3.hasUser(entry.getKey())) {
                databaseV3.createUser(entry.getKey());
            }

        }
    }

    private void saveUserReports() {
        logger.info("Saving " + DataManager.getInstance().getAllUserReports().size() + " user reports.");
        for (UserReport report : DataManager.getInstance().getAllUserReports()) {
            databaseV3.writeUserReport(report.getUUID(), report);
        }
    }

    public ItemStack createCustomNBTRod() {
        ItemFactory itemFactory = new ItemFactory("nbt-rod-item");
        itemFactory.enableDefaultChecks();
        itemFactory.setItemDisplayNameCheck(true);
        itemFactory.setItemLoreCheck(true);

        ItemStack customRod = itemFactory.createItem(null, 0);
        NBT.modify(customRod,nbt -> {
            nbt.getOrCreateCompound(NbtKeys.EMF_COMPOUND).setBoolean(NbtKeys.EMF_ROD_NBT,true);
        });
        return customRod;
    }

    public void reload(@Nullable CommandSender sender) {

        terminateGUIS();

        fish.clear();

        reloadConfig();
        saveDefaultConfig();

        MainConfig.getInstance().reload();
        Messages.getInstance().reload();
        CompetitionConfig.getInstance().reload();
        GUIConfig.getInstance().reload();
        GUIFillerConfig.getInstance().reload();
        FishFile.getInstance().reload();
        RaritiesFile.getInstance().reload();
        BaitFile.getInstance().reload();

        setupEconomy();

        FishManager.getInstance().reload();
        BaitManager.getInstance().reload();

        HandlerList.unregisterAll(FishEatEvent.getInstance());
        HandlerList.unregisterAll(FishInteractEvent.getInstance());
        HandlerList.unregisterAll(McMMOTreasureEvent.getInstance());
        optionalListeners();

        if (MainConfig.getInstance().requireNBTRod()) {
            customNBTRod = createCustomNBTRod();
        }

        competitionQueue.load();

        if (sender != null) {
            new Message(ConfigMessage.RELOAD_SUCCESS).broadcast(sender);
        }

    }

    // Checks for updates, surprisingly
    private boolean checkUpdate() {

        String[] spigotVersion = new UpdateChecker(this, 91310).getVersion().split("\\.");
        String[] serverVersion = getDescription().getVersion().split("\\.");

        for (int i = 0; i < serverVersion.length; i++) {
            if (i < spigotVersion.length) {
                if (Integer.parseInt(spigotVersion[i]) > Integer.parseInt(serverVersion[i])) {
                    return true;
                }
            } else {
                return false;
            }
        }
        return false;
    }

    private void checkPapi() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            usingPAPI = true;
            new PlaceholderReceiver(this).register();
        }
    }

    public Random getRandom() {
        return random;
    }

    public Permission getPermission() {
        return permission;
    }

    public Economy getEconomy() {
        return economy;
    }

    public Map<Integer, Set<String>> getFish() {
        return fish;
    }

    public ItemStack getCustomNBTRod() {
        return customNBTRod;
    }

    public boolean isCheckingEatEvent() {
        return checkingEatEvent;
    }

    public void setCheckingEatEvent(boolean bool) {
        this.checkingEatEvent = bool;
    }

    public boolean isCheckingIntEvent() {
        return checkingIntEvent;
    }

    public void setCheckingIntEvent(boolean bool) {
        this.checkingIntEvent = bool;
    }

    public boolean isRaritiesCompCheckExempt() {
        return raritiesCompCheckExempt;
    }

    public void setRaritiesCompCheckExempt(boolean bool) {
        this.raritiesCompCheckExempt = bool;
    }

    public Competition getActiveCompetition() {
        return active;
    }

    public void setActiveCompetition(Competition competition) {
        this.active = competition;
    }

    public CompetitionQueue getCompetitionQueue() {
        return competitionQueue;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public int getMetricFishCaught() {
        return metric_fishCaught;
    }

    public void incrementMetricFishCaught(int value) {
        this.metric_fishCaught = (metric_fishCaught + value);
    }

    public int getMetricBaitsUsed() {
        return metric_baitsUsed;
    }

    public void incrementMetricBaitsUsed(int value) {
        this.metric_baitsUsed = (metric_baitsUsed + value);
    }

    public int getMetricBaitsApplied() {
        return metric_baitsApplied;
    }

    public void incrementMetricBaitsApplied(int value) {
        this.metric_baitsApplied = (metric_baitsApplied + value);
    }

    public Map<UUID, Rarity> getDecidedRarities() {
        return decidedRarities;
    }

    public boolean isUpdateAvailable() {
        return isUpdateAvailable;
    }

    public boolean isUsingVault() {return usingVault;}

    public boolean isUsingPAPI() {
        return usingPAPI;
    }

    public boolean isUsingMcMMO() {
        return usingMcMMO;
    }

    public boolean isUsingHeadsDB() {
        return usingHeadsDB;
    }

    public boolean isUsingPlayerPoints() {
        return usingPlayerPoints;
    }

    public boolean isUsingGriefPrevention() {return usingGriefPrevention;}

    public DatabaseV3 getDatabaseV3() {
        return databaseV3;
    }

    public HeadDatabaseAPI getHDBapi() {
        return HDBapi;
    }

    public void setHDBapi(HeadDatabaseAPI api) {
        this.HDBapi = api;
    }

    public EMFAPI getApi() {
        return api;
    }


    private void loadRewardManager() {
        // Load RewardManager
        RewardManager.getInstance().load();
        getServer().getPluginManager().registerEvents(RewardManager.getInstance(), this);

        // Load RewardTypes
        new CommandRewardType().register();
        new EffectRewardType().register();
        new HealthRewardType().register();
        new HungerRewardType().register();
        new ItemRewardType().register();
        new MessageRewardType().register();
        new EXPRewardType().register();
        loadExternalRewardTypes();
    }

    private void loadRequirementManager() {
        // Load RequirementManager
        RequirementManager.getInstance().load();
        getServer().getPluginManager().registerEvents(RequirementManager.getInstance(), this);

        // Load RequirementTypes
        new BiomeRequirementType().register();
        new BiomeSetRequirementType().register();
        new DisabledRequirementType().register();
        new InGameTimeRequirementType().register();
        new IRLTimeRequirementType().register();
        new MoonPhaseRequirementType().register();
        new NearbyPlayersRequirementType().register();
        new PermissionRequirementType().register();
        new RegionRequirementType().register();
        new WeatherRequirementType().register();
        new WorldRequirementType().register();
    }

    private void loadExternalRewardTypes() {
        PluginManager pm = Bukkit.getPluginManager();
        if (pm.isPluginEnabled("PlayerPoints")) {
            new PlayerPointsRewardType().register();
        }
        if (pm.isPluginEnabled("GriefPrevention")) {
            new GPClaimBlocksRewardType().register();
        }
        if (pm.isPluginEnabled("AuraSkills")) {
            new AuraSkillsXPRewardType().register();
        }
        if (pm.isPluginEnabled("mcMMO")) {
            new McMMOXPRewardType().register();
        }
        // Only enable the PERMISSION type if Vault perms is found.
        if (getPermission() != null && getPermission().isEnabled()) {
            new PermissionRewardType().register();
        }
        // Only enable the MONEY type if the economy is loaded.
        if (getEconomy() != null && getEconomy().isEnabled()) {
            new MoneyRewardType().register();
        }
    }

    public List<Player> getVisibleOnlinePlayers() {
        return MainConfig.getInstance().shouldRespectVanish() ? VanishChecker.getVisibleOnlinePlayers() : new ArrayList<>(Bukkit.getOnlinePlayers());
    }

    // FISH TOGGLE METHODS
    // We use Strings here because Spigot 1.16.5 does not have PersistentDataType.BOOLEAN.

    public void performFishToggle(@NotNull Player player) {
        NamespacedKey key = new NamespacedKey(this, "fish-enabled");
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        // If it is enabled, disable it
        if (isCustomFishing(player)) {
            pdc.set(key, PersistentDataType.STRING, "false");
            new Message(ConfigMessage.TOGGLE_OFF).broadcast(player);
        // If it is disabled, enable it
        } else {
            pdc.set(key, PersistentDataType.STRING, "true");
            new Message(ConfigMessage.TOGGLE_ON).broadcast(player);
        }
    }

    public boolean isCustomFishing(@NotNull Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(this, "fish-enabled");
        String toggleValue = pdc.getOrDefault(key, PersistentDataType.STRING, "true");
        return toggleValue.equals("true");
    }

}
