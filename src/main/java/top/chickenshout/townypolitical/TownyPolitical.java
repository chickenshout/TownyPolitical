// 文件名: TownyPolitical.java
// 结构位置: top/chickenshout/townypolitical/TownyPolitical.java
package top.chickenshout.townypolitical;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

import top.chickenshout.townypolitical.commands.PoliticalCommands;
import top.chickenshout.townypolitical.commands.PoliticalTabCompleter;
import top.chickenshout.townypolitical.economy.EconomyService;
import top.chickenshout.townypolitical.economy.VaultEconomyService;
import top.chickenshout.townypolitical.listeners.PlayerEventListener;
import top.chickenshout.townypolitical.listeners.TownyHookListener;
import top.chickenshout.townypolitical.managers.ElectionManager;
import top.chickenshout.townypolitical.managers.NationManager;
import top.chickenshout.townypolitical.managers.PartyManager;
import top.chickenshout.townypolitical.managers.BillManager;
import top.chickenshout.townypolitical.utils.MessageManager;

import java.io.File;
import java.util.logging.Level;

public class TownyPolitical extends JavaPlugin {

    private static TownyPolitical instance;

    // Managers and Services
    private MessageManager messageManager;
    private EconomyService economyService;
    private PartyManager partyManager;
    private NationManager nationManager;
    private ElectionManager electionManager;
    private BillManager billManager;
    // ParliamentManager is not included as per decision to exclude complex GUI/Bill features for now

    // Listeners
    private TownyHookListener townyHookListener;
    private PlayerEventListener playerEventListener;

    // --- Constructors for Bukkit and potentially testing ---
    public TownyPolitical() {
        super();
    }

    // This constructor is normally used by Bukkit for testing/mocking.
    // It's good practice to include if you plan on writing unit tests with a mock Bukkit server.
    protected TownyPolitical(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
        super(loader, description, dataFolder, file);
    }
    // --- End of constructors ---

    @Override
    public void onLoad() {
        instance = this; // Set instance as early as possible
        getLogger().info(getName() + " is loading...");
        // Perform very early checks if necessary, e.g., critical dependency classes (not instances)
    }

    @Override
    public void onEnable() {
        long startTime = System.currentTimeMillis();
        // Ensure instance is set (onLoad should have done it)
        if (instance == null) {
            instance = this;
        }
        getLogger().info("==================================================");
        getLogger().info("Enabling " + getName() + " version " + getDescription().getVersion());

        // 1. Load/save default configuration files
        getLogger().info("Loading configurations...");
        saveDefaultConfig(); // Saves config.yml from JAR if it doesn't exist
        getConfig().options().copyDefaults(true); // Copies defaults from JAR's config to user's config for missing keys
        // saveConfig(); // Usually called after making changes to the config in memory, not needed here typically

        // 2. Initialize Message Manager (must be first for other components to use messages)
        getLogger().info("Initializing Message Manager...");
        this.messageManager = new MessageManager(this);
        this.messageManager.loadMessages(); // Explicitly load messages after MessageManager construction
        if (this.messageManager == null) { // Highly unlikely unless constructor throws an unhandled error
            getLogger().severe("Message Manager failed to initialize! Plugin cannot enable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Initialize Economy Service
        getLogger().info("Initializing Economy Service (Vault)...");
        this.economyService = new VaultEconomyService(this); // Pass 'this' (TownyPolitical instance)
        if (!this.economyService.initialize()) {
            getLogger().warning("Economy service (Vault) could not be initialized or no economy plugin found. Economic features will be limited or disabled.");
            // Decide if this is critical enough to disable the plugin
            // For now, we allow it to run with limited/no economy.
        } else {
            getLogger().info("Economy Service initialized successfully using: " + this.economyService.getProviderName());
        }

        // 4. Initialize Core Managers (order can be important due to dependencies)
        // PartyManager, NationManager, then ElectionManager (as ElectionManager uses the other two)

        getLogger().info("Initializing Party Manager...");
        this.partyManager = new PartyManager(this);
        if (this.partyManager == null) { disableCritical("Party Manager"); return; }

        getLogger().info("Initializing Nation Manager...");
        this.nationManager = new NationManager(this);
        if (this.nationManager == null) { disableCritical("Nation Manager"); return; }

        getLogger().info("Initializing Election Manager...");
        this.electionManager = new ElectionManager(this); // ElectionManager's constructor now calls load and schedule
        if (this.electionManager == null) { disableCritical("Election Manager"); return; }

        getLogger().info("Initializing Bill Manager..."); // <--- 新增
        this.billManager = new BillManager(this);          // <--- 新增
        if (this.billManager == null) { disableCritical("Bill Manager"); return; } // <--- 新增


        // 5. Register Event Listeners
        getLogger().info("Registering event listeners...");
        // TownyHookListener requires Towny plugin
        if (getServer().getPluginManager().getPlugin("Towny") != null) {
            this.townyHookListener = new TownyHookListener(this);
            // Listener registration is done inside TownyHookListener's constructor
        } else {
            getLogger().severe("Towny plugin not found! TownyPolitical is heavily dependent on Towny and cannot function. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.playerEventListener = new PlayerEventListener(this);
        // Listener registration is done inside PlayerEventListener's constructor

        // 6. Register Command Executors and Tab Completers
        getLogger().info("Registering commands...");
        registerCommands();

        long endTime = System.currentTimeMillis();
        getLogger().info(getName() + " has been enabled successfully! (Took " + (endTime - startTime) + "ms)");
        getLogger().info("==================================================");
    }

    private void disableCritical(String componentName) {
        getLogger().severe(componentName + " failed to initialize! Plugin cannot enable.");
        getServer().getPluginManager().disablePlugin(this);
    }


    private void registerCommands() {
        PoliticalCommands politicalCommandHandler = new PoliticalCommands(this);
        PoliticalTabCompleter politicalTabCompleter = new PoliticalTabCompleter(this, politicalCommandHandler);

        String mainCommandName = "townypolitical";
        PluginCommand mainCommand = getCommand(mainCommandName);
        if (mainCommand != null) {
            mainCommand.setExecutor(politicalCommandHandler);
            mainCommand.setTabCompleter(politicalTabCompleter);
            getLogger().info("Command '/" + mainCommandName + "' (and aliases) registered.");
        } else {
            getLogger().severe("Failed to register main command '/" + mainCommandName + "'! Check plugin.yml definition.");
        }

        // Register alias commands from plugin.yml if they point to the same executor
        String partyAliasName = "tparty";
        PluginCommand partyAliasCommand = getCommand(partyAliasName);
        if (partyAliasCommand != null) {
            partyAliasCommand.setExecutor(politicalCommandHandler); // Re-use the same handler
            partyAliasCommand.setTabCompleter(politicalTabCompleter); // Re-use the same tab completer
            getLogger().info("Alias command '/" + partyAliasName + "' registered.");
        } else {
            // This is not necessarily an error if you didn't define 'tparty' in plugin.yml
            getLogger().finer("Alias command '/" + partyAliasName + "' not found in plugin.yml, not registered.");
        }
        // Add similar blocks if you define other top-level alias commands like /tnation, /telection in plugin.yml
    }


    @Override
    public void onDisable() {
        long startTime = System.currentTimeMillis();
        getLogger().info("==================================================");
        getLogger().info("Disabling " + getName() + "...");

        // 1. Shutdown managers (in reverse order of dependency, or as appropriate)
        if (electionManager != null) {
            getLogger().info("Shutting down Election Manager...");
            electionManager.shutdown();
        }
        if (billManager != null) { // <--- 新增
            getLogger().info("Shutting down Bill Manager..."); // <--- 新增
            billManager.shutdown(); // <--- 新增
        } // <--- 新增
        if (partyManager != null) {
            getLogger().info("Shutting down Party Manager...");
            partyManager.shutdown();
        }
        if (nationManager != null) {
            getLogger().info("Shutting down Nation Manager...");
            nationManager.shutdown();
        }
        // EconomyService and MessageManager don't typically need a shutdown method for saving data.

        // 2. Unregister listeners (Bukkit does this automatically, but explicit can be clearer)
        // HandlerList.unregisterAll(this); // If main class itself was a listener (not our case)
        // Individual listeners don't need manual unregistration if plugin is disabled.

        // 3. Clear resources and references
        this.messageManager = null;
        this.economyService = null;
        this.partyManager = null;
        this.nationManager = null;
        this.electionManager = null;
        this.billManager = null; // <--- 新增
        this.townyHookListener = null;
        this.playerEventListener = null;
        getLogger().info("Managers and listeners nulled.");

        instance = null; // Clear static instance last

        long endTime = System.currentTimeMillis();
        getLogger().info(getName() + " has been disabled. (Took " + (endTime - startTime) + "ms)");
        getLogger().info("==================================================");
    }

    /**
     * Gets the static instance of the TownyPolitical plugin.
     * @return The plugin instance.
     * @throws IllegalStateException if the plugin is not loaded or has been disabled.
     */
    public static TownyPolitical getInstance() {
        if (instance == null) {
            // This might happen if called before onLoad or after onDisable.
            // Or if another plugin tries to get it when this one is not properly loaded.
            JavaPlugin plugin = getPlugin(TownyPolitical.class); // Bukkit's way to get a loaded plugin
            if (plugin instanceof TownyPolitical) {
                instance = (TownyPolitical) plugin;
            } else {
                // Fallback or error if plugin not found by Bukkit (e.g., it's disabled)
                throw new IllegalStateException("TownyPolitical plugin instance is not available. Is the plugin loaded and enabled?");
            }
        }
        return instance;
    }

    // --- Public Getters for Managers and Services ---
    public MessageManager getMessageManager() {
        if (messageManager == null) throw new IllegalStateException("MessageManager is not initialized (TownyPolitical might be disabled or failed to enable).");
        return messageManager;
    }

    public EconomyService getEconomyService() {
        if (economyService == null) throw new IllegalStateException("EconomyService is not initialized.");
        // Optional: Log if accessed when !isEnabled, but don't throw, let calling code handle it.
        if (!economyService.isEnabled()) {
            getLogger().finer("EconomyService is being accessed but is not fully enabled (e.g., no Vault or economy plugin).");
        }
        return economyService;
    }

    public PartyManager getPartyManager() {
        if (partyManager == null) throw new IllegalStateException("PartyManager is not initialized.");
        return partyManager;
    }

    public NationManager getNationManager() {
        if (nationManager == null) throw new IllegalStateException("NationManager is not initialized.");
        return nationManager;
    }

    public ElectionManager getElectionManager() {
        if (electionManager == null) throw new IllegalStateException("ElectionManager is not initialized.");
        return electionManager;
    }

    public BillManager getBillManager() { // <--- 新增
        if (billManager == null) throw new IllegalStateException("BillManager is not initialized."); // <--- 新增
        return billManager; // <--- 新增
    } // <--- 新增

    /**
     * Reloads the plugin's configuration files (config.yml and messages.yml)
     * and attempts to reload data for managers.
     * Note: Full, robust hot-reloading of complex plugins is difficult and often discouraged
     * in favor of server restarts for major changes. This provides a basic reload.
     * @return true if reload process initiated successfully, false otherwise.
     */
    public boolean reloadPlugin() {
        getLogger().info("Reloading " + getName() + "...");
        try {
            // 1. Shutdown existing managers to save data and cancel tasks (if they are running)
            if (electionManager != null) electionManager.shutdown(); // Cancels tasks, saves active elections
            if (billManager != null) billManager.shutdown();
            if (partyManager != null) partyManager.saveAllParties();
            if (nationManager != null) nationManager.saveAllNationPoliticsData();

            // 2. Reload Bukkit's config.yml
            super.reloadConfig(); // This reloads from disk into memory getConfig()
            // Update any config-dependent settings in managers if necessary
            // For example, party name rules in PartyManager
            if (partyManager != null) partyManager.reloadPartyConfigAndData(); // Reloads rules and all party data
            if (nationManager != null) nationManager.reloadNationConfigAndData(); // Reloads all nation politics data
            if (billManager != null) billManager.loadBills();

            // 3. Reload messages.yml
            if (messageManager != null) messageManager.reloadMessages();

            // 4. Re-initialize ElectionManager's data and scheduling
            // This is the trickiest part for a hot reload.
            // A simple approach: re-create it or have a dedicated reload method.
            if (electionManager != null) {
                getLogger().info("Re-initializing ElectionManager data and schedules for reload...");
                // ElectionManager's constructor calls loadActiveElections and scheduleNextElectionsForAllValidContexts
                // To properly reload it, we might need to clear its internal state first if not re-instantiating.
                // A simple but potentially disruptive way for full data reload:
                // this.electionManager = new ElectionManager(this);
                // Or, if ElectionManager has its own reload:
                // electionManager.reloadDataAndReschedule();
                // For now, let's assume its existing load and schedule methods are called by constructor
                // and if we want a "deeper" reload of elections, it might be complex.
                // The shutdown above cancelled its tasks. The constructor will re-load and re-schedule.
                // If not re-instantiating, we'd need:
                electionManager.loadActiveElections(); // Reloads from active files
                electionManager.scheduleNextElectionsForAllValidContexts(); // Re-schedules based on current data
            }

            getLogger().info(getName() + " reloaded successfully.");
            return true;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error reloading " + getName() + ": " + e.getMessage(), e);
            return false;
        }
    }
}