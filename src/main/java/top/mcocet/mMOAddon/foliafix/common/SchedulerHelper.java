package top.mcocet.mMOAddon.foliafix.common;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Folia-compatible replacement for Bukkit.getScheduler() calls inside third-party plugins.
 *
 * This helper returns a BukkitScheduler proxy that routes legacy scheduler calls to
 * Folia's GlobalRegionScheduler / AsyncScheduler. On non-Folia servers it falls back
 * to the original BukkitScheduler.
 */
public class SchedulerHelper {

    private static final Logger LOGGER = Logger.getLogger("MMOAddon-FoliaFix");

    private static final boolean isFolia = checkFolia();

    // Folia scheduler instances and methods (reflection, so this compiles on Paper too)
    private static Object globalRegionScheduler = null;
    private static Object asyncScheduler = null;
    private static Object regionScheduler = null;
    private static Method globalExecute = null;
    private static Method globalRunDelayed = null;
    private static Method globalRunAtFixedRate = null;
    private static Method globalCancelTasks = null;
    private static Method asyncRunNow = null;
    private static Method asyncRunDelayed = null;
    private static Method asyncRunAtFixedRate = null;
    private static Method asyncCancelTasks = null;
    private static Method regionRunDelayed = null;
    private static Method regionRunAtFixedRate = null;
    private static Method scheduledTaskCancel = null;

    // Task ID management for BukkitTask compatibility
    private static final int ID_START = 1_000_000;
    private static final AtomicInteger nextId = new AtomicInteger(ID_START);
    private static final Map<Integer, Object> foliaTasks = new ConcurrentHashMap<>();

    // Cached BukkitScheduler proxy used by bytecode-patched plugins.
    private static BukkitScheduler schedulerProxy;

    static {
        if (isFolia) {
            initFolia();
        }
    }

    /**
     * Returns a BukkitScheduler proxy that routes scheduler calls through this helper.
     * This is returned by bytecode-patched code in place of Bukkit.getScheduler().
     */
    public static BukkitScheduler getScheduler() {
        if (schedulerProxy == null) {
            schedulerProxy = (BukkitScheduler) Proxy.newProxyInstance(
                    BukkitScheduler.class.getClassLoader(),
                    new Class<?>[] { BukkitScheduler.class },
                    new SchedulerProxyHandler()
            );
        }
        return schedulerProxy;
    }

    private static boolean checkFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void initFolia() {
        try {
            Object server = Bukkit.getServer();
            Class<?> serverClass = server.getClass();

            globalRegionScheduler = serverClass.getMethod("getGlobalRegionScheduler").invoke(server);
            asyncScheduler = serverClass.getMethod("getAsyncScheduler").invoke(server);
            regionScheduler = serverClass.getMethod("getRegionScheduler").invoke(server);

            Class<?> pluginClass = Class.forName("org.bukkit.plugin.Plugin");
            Class<?> runnableClass = Runnable.class;
            Class<?> consumerClass = Consumer.class;
            Class<?> locationClass = Class.forName("org.bukkit.Location");

            Class<?> globalClass = globalRegionScheduler.getClass();
            globalExecute = globalClass.getMethod("execute", pluginClass, runnableClass);
            globalRunDelayed = globalClass.getMethod("runDelayed", pluginClass, consumerClass, long.class);
            globalRunAtFixedRate = globalClass.getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class);
            globalCancelTasks = globalClass.getMethod("cancelTasks", pluginClass);

            Class<?> asyncClass = asyncScheduler.getClass();
            asyncRunNow = asyncClass.getMethod("runNow", pluginClass, consumerClass);
            asyncRunDelayed = asyncClass.getMethod("runDelayed", pluginClass, consumerClass, long.class, TimeUnit.class);
            asyncRunAtFixedRate = asyncClass.getMethod("runAtFixedRate", pluginClass, consumerClass, long.class, long.class, TimeUnit.class);
            asyncCancelTasks = asyncClass.getMethod("cancelTasks", pluginClass);

            Class<?> regionClass = regionScheduler.getClass();
            regionRunDelayed = regionClass.getMethod("runDelayed", pluginClass, locationClass, consumerClass, long.class);
            regionRunAtFixedRate = regionClass.getMethod("runAtFixedRate", pluginClass, locationClass, consumerClass, long.class, long.class);

            Class<?> scheduledTaskClass = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
            scheduledTaskCancel = scheduledTaskClass.getMethod("cancel");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "[MMOAddon-FoliaFix] Failed to initialize Folia scheduler helper: " + e.getMessage(), e);
        }
    }

    // ==================== Sync tasks ====================

    public static BukkitTask runTask(Plugin plugin, Runnable task) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTask(plugin, task);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            globalExecute.invoke(globalRegionScheduler, plugin, wrapped);
            BukkitTask bukkitTask = new DummyBukkitTask(plugin, nextId.getAndIncrement(), true);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTask", e);
            return null;
        }
    }

    public static BukkitTask runTaskLater(Plugin plugin, Runnable task, long delay) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = globalRunDelayed.invoke(globalRegionScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay));
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskLater", e);
            return null;
        }
    }

    public static BukkitTask runTaskTimer(Plugin plugin, Runnable task, long delay, long period) {
        // MMOItems has two global player-data tasks:
        // 1) Inventory update in MMOItems.onEnable.
        // 2) Player stats update in MMOItemsBukkit.<init>.
        // Both iterate over Bukkit.getOnlinePlayers() and access PlayerData from the
        // GlobalRegionScheduler, which throws "Player data is not loaded" on Folia.
        // Dispatch them to each player's own region thread.
        if (plugin != null && "MMOItems".equals(plugin.getName()) && task != null) {
            String taskClass = task.getClass().getName();
            if (taskClass.contains("MMOItemsBukkit")) {
                return runMMOItemsPlayerDataUpdateTask(plugin, delay, period);
            }
            if (taskClass.contains("MMOItems")) {
                return runMMOItemsInventoryUpdateTask(plugin, delay, period);
            }
        }
        // MythicLib hologram tasks (Hologram$1 flyOut, etc.) operate on TextDisplay entities that belong
        // to a specific region. Running them on the GlobalRegionScheduler causes
        // "Accessing entity state off owning region's thread". Dispatch them to the region that owns
        // the hologram's location instead.
        Location hologramLoc = getHologramLocation(task);
        if (hologramLoc != null && isFolia) {
            return runTaskTimerOnLocation(plugin, task, hologramLoc, delay, period);
        }
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = globalRunAtFixedRate.invoke(globalRegionScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay), Math.max(1L, period));
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskTimer", e);
            return null;
        }
    }

    // ==================== Sync consumer tasks ====================

    public static void runTask(Plugin plugin, Consumer<BukkitTask> task) {
        if (!isFolia) {
            Bukkit.getScheduler().runTask(plugin, task);
            return;
        }
        runTaskLater(plugin, task, 1L);
    }

    public static void runTaskLater(Plugin plugin, Consumer<BukkitTask> task, long delay) {
        if (!isFolia) {
            Bukkit.getScheduler().runTaskLater(plugin, task, delay);
            return;
        }
        try {
            int id = nextId.getAndIncrement();
            FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, null);
            Consumer<Object> foliaConsumer = scheduledTask -> {
                bukkitTask.setFoliaTask(scheduledTask);
                foliaTasks.put(id, scheduledTask);
                task.accept(bukkitTask);
            };
            Object foliaTask = globalRunDelayed.invoke(globalRegionScheduler, plugin, foliaConsumer, Math.max(1L, delay));
            bukkitTask.setFoliaTask(foliaTask);
            foliaTasks.put(id, foliaTask);
        } catch (Exception e) {
            logException(plugin, "runTaskLater", e);
        }
    }

    public static void runTaskTimer(Plugin plugin, Consumer<BukkitTask> task, long delay, long period) {
        if (!isFolia) {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period);
            return;
        }
        try {
            int id = nextId.getAndIncrement();
            FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, null);
            Consumer<Object> foliaConsumer = scheduledTask -> {
                bukkitTask.setFoliaTask(scheduledTask);
                foliaTasks.put(id, scheduledTask);
                task.accept(bukkitTask);
            };
            Object foliaTask = globalRunAtFixedRate.invoke(globalRegionScheduler, plugin, foliaConsumer, Math.max(1L, delay), Math.max(1L, period));
            bukkitTask.setFoliaTask(foliaTask);
            foliaTasks.put(id, foliaTask);
        } catch (Exception e) {
            logException(plugin, "runTaskTimer", e);
        }
    }

    // ==================== Async tasks ====================

    public static BukkitTask runTaskAsynchronously(Plugin plugin, Runnable task) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunNow.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run());
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskAsynchronously", e);
            return null;
        }
    }

    public static BukkitTask runTaskLaterAsynchronously(Plugin plugin, Runnable task, long delay) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunDelayed.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay) * 50L, TimeUnit.MILLISECONDS);
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskLaterAsynchronously", e);
            return null;
        }
    }

    public static BukkitTask runTaskTimerAsynchronously(Plugin plugin, Runnable task, long delay, long period) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
        }
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Object foliaTask = asyncRunAtFixedRate.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> wrapped.run(), Math.max(1L, delay) * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS);
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskTimerAsynchronously", e);
            return null;
        }
    }

    // ==================== Async consumer tasks ====================

    public static void runTaskAsynchronously(Plugin plugin, Consumer<BukkitTask> task) {
        if (!isFolia) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            return;
        }
        try {
            int id = nextId.getAndIncrement();
            FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, null);
            Consumer<Object> foliaConsumer = scheduledTask -> {
                bukkitTask.setFoliaTask(scheduledTask);
                foliaTasks.put(id, scheduledTask);
                task.accept(bukkitTask);
            };
            Object foliaTask = asyncRunNow.invoke(asyncScheduler, plugin, foliaConsumer);
            bukkitTask.setFoliaTask(foliaTask);
            foliaTasks.put(id, foliaTask);
        } catch (Exception e) {
            logException(plugin, "runTaskAsynchronously", e);
        }
    }

    public static void runTaskLaterAsynchronously(Plugin plugin, Consumer<BukkitTask> task, long delay) {
        if (!isFolia) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delay);
            return;
        }
        try {
            int id = nextId.getAndIncrement();
            FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, null);
            Consumer<Object> foliaConsumer = scheduledTask -> {
                bukkitTask.setFoliaTask(scheduledTask);
                foliaTasks.put(id, scheduledTask);
                task.accept(bukkitTask);
            };
            Object foliaTask = asyncRunDelayed.invoke(asyncScheduler, plugin, foliaConsumer, Math.max(1L, delay) * 50L, TimeUnit.MILLISECONDS);
            bukkitTask.setFoliaTask(foliaTask);
            foliaTasks.put(id, foliaTask);
        } catch (Exception e) {
            logException(plugin, "runTaskLaterAsynchronously", e);
        }
    }

    public static void runTaskTimerAsynchronously(Plugin plugin, Consumer<BukkitTask> task, long delay, long period) {
        if (!isFolia) {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delay, period);
            return;
        }
        try {
            int id = nextId.getAndIncrement();
            FoliaBukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, false, null);
            Consumer<Object> foliaConsumer = scheduledTask -> {
                bukkitTask.setFoliaTask(scheduledTask);
                foliaTasks.put(id, scheduledTask);
                task.accept(bukkitTask);
            };
            Object foliaTask = asyncRunAtFixedRate.invoke(asyncScheduler, plugin, foliaConsumer, Math.max(1L, delay) * 50L, Math.max(1L, period) * 50L, TimeUnit.MILLISECONDS);
            bukkitTask.setFoliaTask(foliaTask);
            foliaTasks.put(id, foliaTask);
        } catch (Exception e) {
            logException(plugin, "runTaskTimerAsynchronously", e);
        }
    }

    // ==================== MMOItems inventory update task (per-region on Folia) ====================

    /**
     * Replacement for MMOItems' global inventory-update task.
     *
     * On Folia, the original task iterates over Bukkit.getOnlinePlayers() and accesses
     * PlayerData from the GlobalRegionScheduler, which throws "Player data is not loaded"
     * because player data is region-owned. This helper schedules the outer timer on the
     * GlobalRegionScheduler, but dispatches each player's inventory check to that player's
     * own region thread.
     */
    public static BukkitTask runMMOItemsInventoryUpdateTask(Plugin plugin, long delay, long period) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updateMMOItemsPlayerInventory(p);
                }
            }, delay, period);
        }
        return runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                scheduleOnPlayerRegion(plugin, p, () -> updateMMOItemsPlayerInventory(p));
            }
        }, delay, period);
    }

    private static void updateMMOItemsPlayerInventory(Player p) {
        try {
            Class<?> playerDataClass = Class.forName("net.Indyuce.mmoitems.api.player.PlayerData");
            Object playerData = playerDataClass.getMethod("get", OfflinePlayer.class).invoke(null, p);
            Object inventory = playerDataClass.getMethod("getInventory").invoke(playerData);
            inventory.getClass().getMethod("updateCheck").invoke(inventory);
        } catch (Throwable ignored) {
            // MMOItems not present, method signatures changed, or data not loaded yet.
        }
    }

    /**
     * Replacement for MMOItemsBukkit's global player-stats update task.
     */
    public static BukkitTask runMMOItemsPlayerDataUpdateTask(Plugin plugin, long delay, long period) {
        if (!isFolia) {
            return Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    updateMMOItemsPlayerData(p);
                }
            }, delay, period);
        }
        return runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                scheduleOnPlayerRegion(plugin, p, () -> updateMMOItemsPlayerData(p));
            }
        }, delay, period);
    }

    private static void updateMMOItemsPlayerData(Player p) {
        try {
            Class<?> playerDataClass = Class.forName("net.Indyuce.mmoitems.api.player.PlayerData");
            Object playerData = playerDataClass.getMethod("get", OfflinePlayer.class).invoke(null, p);
            playerDataClass.getMethod("updateStats").invoke(playerData);
        } catch (Throwable ignored) {
            // MMOItems not present, method signatures changed, or data not loaded yet.
        }
    }

    /**
     * Dispatch a MMOPlayerData Consumer action to the player data's owning region thread.
     * Used by the patched MMOPlayerData.forEach / forEachPlaying / forEachOnline methods.
     */
    @SuppressWarnings("unchecked")
    public static void acceptOnPlayerRegion(java.util.function.Consumer<Object> action, Object data, Plugin plugin) {
        if (!isFolia) {
            action.accept(data);
            return;
        }
        try {
            Object player = data.getClass().getMethod("getPlayer").invoke(data);
            if (player instanceof Player) {
                scheduleOnPlayerRegion(plugin, (Player) player, () -> action.accept(data));
                return;
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[MMOAddon-FoliaFix] Could not dispatch player-data action: " + e.getMessage());
        }
        action.accept(data);
    }

    private static void scheduleOnPlayerRegion(Plugin plugin, Player p, Runnable task) {
        try {
            Object server = Bukkit.getServer();
            Class<?> serverClass = server.getClass();
            Object regionScheduler = serverClass.getMethod("getRegionScheduler").invoke(server);
            Class<?> pluginClass = Class.forName("org.bukkit.plugin.Plugin");
            Class<?> entityClass = Class.forName("org.bukkit.entity.Entity");
            regionScheduler.getClass().getMethod("execute", pluginClass, entityClass, Runnable.class)
                    .invoke(regionScheduler, plugin, p, task);
        } catch (Exception e) {
            // Fallback to running directly if RegionScheduler is unavailable.
            task.run();
        }
    }

    private static void scheduleOnLocationRegion(Plugin plugin, Location location, Runnable task) {
        try {
            if (regionScheduler == null || regionRunDelayed == null) {
                task.run();
                return;
            }
            regionRunDelayed.invoke(regionScheduler, plugin, location, (Consumer<Object>) t -> task.run(), 1L);
        } catch (Exception e) {
            // Fallback to running directly if RegionScheduler is unavailable.
            task.run();
        }
    }

    private static BukkitTask runTaskTimerOnLocation(Plugin plugin, Runnable task, Location location, long delay, long period) {
        try {
            BukkitTask[] taskHolder = new BukkitTask[1];
            Runnable wrapped = wrap(plugin, task, taskHolder);
            Consumer<Object> foliaConsumer = scheduledTask -> wrapped.run();
            Object foliaTask = regionRunAtFixedRate.invoke(regionScheduler, plugin, location, foliaConsumer, Math.max(1L, delay), Math.max(1L, period));
            int id = nextId.getAndIncrement();
            foliaTasks.put(id, foliaTask);
            BukkitTask bukkitTask = new FoliaBukkitTask(plugin, id, true, foliaTask);
            taskHolder[0] = bukkitTask;
            if (task instanceof org.bukkit.scheduler.BukkitRunnable) {
                injectBukkitRunnableTask((org.bukkit.scheduler.BukkitRunnable) task, bukkitTask);
            }
            return bukkitTask;
        } catch (Exception e) {
            logException(plugin, "runTaskTimerOnLocation", e);
            return null;
        }
    }

    private static Location getHologramLocation(Runnable task) {
        if (task == null) return null;
        String className = task.getClass().getName();
        if (!className.startsWith("io.lumine.mythic.lib.hologram.") && !className.contains("Hologram")) {
            return null;
        }
        try {
            java.lang.reflect.Field locField = task.getClass().getDeclaredField("loc");
            locField.setAccessible(true);
            Object value = locField.get(task);
            if (value instanceof Location) {
                return (Location) value;
            }
        } catch (NoSuchFieldException e) {
            // Some hologram runnables may not have a loc field.
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[MMOAddon-FoliaFix] Could not read hologram location: " + e.getMessage());
        }
        return null;
    }

    // ==================== BukkitRunnable convenience overloads ====================

    public static BukkitTask runTask(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin) {
        return runTask(plugin, runnable);
    }

    public static BukkitTask runTaskLater(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay) {
        return runTaskLater(plugin, runnable, delay);
    }

    public static BukkitTask runTaskTimer(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        return runTaskTimer(plugin, runnable, delay, period);
    }

    public static BukkitTask runTaskAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin) {
        return runTaskAsynchronously(plugin, runnable);
    }

    public static BukkitTask runTaskLaterAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay) {
        return runTaskLaterAsynchronously(plugin, runnable, delay);
    }

    public static BukkitTask runTaskTimerAsynchronously(org.bukkit.scheduler.BukkitRunnable runnable, Plugin plugin, long delay, long period) {
        return runTaskTimerAsynchronously(plugin, runnable, delay, period);
    }

    // ==================== Legacy schedule methods ====================

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
        BukkitTask t = runTaskLater(plugin, task, 1L);
        return t != null ? t.getTaskId() : -1;
    }

    public static int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
        BukkitTask t = runTaskLater(plugin, task, delay);
        return t != null ? t.getTaskId() : -1;
    }

    // ==================== Sound compatibility (Paper 1.20.5+ where Sound is an interface) ====================

    /**
     * Throws an exception so that callers' try/catch blocks fall back to string-based sound keys.
     * MythicLib 1.7 assumes org.bukkit.Sound is an enum; on Paper 1.20.5+ it is an interface and
     * Sound.valueOf does not exist. We replace those calls with this helper.
     */
    public static org.bukkit.Sound resolveSound(String input) {
        throw new IllegalArgumentException("Sound enum API is not available on this server version: " + input);
    }

    // ==================== BukkitRunnable cancellation ====================

    public static void cancelBukkitRunnable(org.bukkit.scheduler.BukkitRunnable runnable) {
        try {
            java.lang.reflect.Field taskField = org.bukkit.scheduler.BukkitRunnable.class.getDeclaredField("task");
            taskField.setAccessible(true);
            BukkitTask task = (BukkitTask) taskField.get(runnable);
            if (task != null) {
                task.cancel();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[MMOAddon-FoliaFix] Could not cancel BukkitRunnable: " + e.getMessage());
        }
    }

    // ==================== Cancellation ====================

    public static void cancelTasks(Plugin plugin) {
        if (!isFolia) {
            Bukkit.getScheduler().cancelTasks(plugin);
            return;
        }
        try {
            if (globalCancelTasks != null) globalCancelTasks.invoke(globalRegionScheduler, plugin);
            if (asyncCancelTasks != null) asyncCancelTasks.invoke(asyncScheduler, plugin);
        } catch (Exception e) {
            logException(plugin, "cancelTasks", e);
        }
    }

    public static void cancelTask(int taskId) {
        if (!isFolia) {
            Bukkit.getScheduler().cancelTask(taskId);
            return;
        }
        Object foliaTask = foliaTasks.remove(taskId);
        if (foliaTask != null && scheduledTaskCancel != null) {
            try {
                scheduledTaskCancel.invoke(foliaTask);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[MMOAddon-FoliaFix] Failed to cancel Folia task " + taskId + ": " + e.getMessage());
            }
        }
    }

    // ==================== Internals ====================

    private static Runnable wrap(Plugin plugin, Runnable task, BukkitTask[] taskHolder) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[MMOAddon-FoliaFix] Task for " + plugin.getName() + " threw an exception", t);
            }
        };
    }

    private static void injectBukkitRunnableTask(org.bukkit.scheduler.BukkitRunnable runnable, BukkitTask task) {
        try {
            java.lang.reflect.Field taskField = org.bukkit.scheduler.BukkitRunnable.class.getDeclaredField("task");
            taskField.setAccessible(true);
            taskField.set(runnable, task);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "[MMOAddon-FoliaFix] Could not inject BukkitRunnable task field: " + e.getMessage());
        }
    }

    private static void logException(Plugin plugin, String method, Throwable t) {
        LOGGER.log(Level.WARNING, "[MMOAddon-FoliaFix] " + method + " failed for " + (plugin != null ? plugin.getName() : "null") + ": " + t.getMessage(), t);
    }

    public static boolean isFolia() {
        return isFolia;
    }

    /**
     * Invocation handler for the BukkitScheduler proxy returned to patched plugins.
     */
    private static class SchedulerProxyHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            Class<?>[] paramTypes = method.getParameterTypes();

            // runTask(Plugin, Consumer<BukkitTask>)
            if ("runTask".equals(name) && paramTypes.length == 2 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTask((Plugin) args[0], (Consumer<BukkitTask>) args[1]);
                return null;
            }
            // runTask(Plugin, Runnable)
            if ("runTask".equals(name) && paramTypes.length == 2 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTask((Plugin) args[0], (Runnable) args[1]);
            }
            // runTaskLater(Plugin, Consumer<BukkitTask>, long)
            if ("runTaskLater".equals(name) && paramTypes.length == 3 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTaskLater((Plugin) args[0], (Consumer<BukkitTask>) args[1], ((Number) args[2]).longValue());
                return null;
            }
            // runTaskLater(Plugin, Runnable, long)
            if ("runTaskLater".equals(name) && paramTypes.length == 3 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTaskLater((Plugin) args[0], (Runnable) args[1], ((Number) args[2]).longValue());
            }
            // runTaskTimer(Plugin, Consumer<BukkitTask>, long, long)
            if ("runTaskTimer".equals(name) && paramTypes.length == 4 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTaskTimer((Plugin) args[0], (Consumer<BukkitTask>) args[1], ((Number) args[2]).longValue(), ((Number) args[3]).longValue());
                return null;
            }
            // runTaskTimer(Plugin, Runnable, long, long)
            if ("runTaskTimer".equals(name) && paramTypes.length == 4 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTaskTimer((Plugin) args[0], (Runnable) args[1], ((Number) args[2]).longValue(), ((Number) args[3]).longValue());
            }
            // runTaskAsynchronously(Plugin, Consumer<BukkitTask>)
            if ("runTaskAsynchronously".equals(name) && paramTypes.length == 2 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTaskAsynchronously((Plugin) args[0], (Consumer<BukkitTask>) args[1]);
                return null;
            }
            // runTaskAsynchronously(Plugin, Runnable)
            if ("runTaskAsynchronously".equals(name) && paramTypes.length == 2 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTaskAsynchronously((Plugin) args[0], (Runnable) args[1]);
            }
            // runTaskLaterAsynchronously(Plugin, Consumer<BukkitTask>, long)
            if ("runTaskLaterAsynchronously".equals(name) && paramTypes.length == 3 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTaskLaterAsynchronously((Plugin) args[0], (Consumer<BukkitTask>) args[1], ((Number) args[2]).longValue());
                return null;
            }
            // runTaskLaterAsynchronously(Plugin, Runnable, long)
            if ("runTaskLaterAsynchronously".equals(name) && paramTypes.length == 3 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTaskLaterAsynchronously((Plugin) args[0], (Runnable) args[1], ((Number) args[2]).longValue());
            }
            // runTaskTimerAsynchronously(Plugin, Consumer<BukkitTask>, long, long)
            if ("runTaskTimerAsynchronously".equals(name) && paramTypes.length == 4 && paramTypes[0] == Plugin.class && paramTypes[1] == Consumer.class) {
                runTaskTimerAsynchronously((Plugin) args[0], (Consumer<BukkitTask>) args[1], ((Number) args[2]).longValue(), ((Number) args[3]).longValue());
                return null;
            }
            // runTaskTimerAsynchronously(Plugin, Runnable, long, long)
            if ("runTaskTimerAsynchronously".equals(name) && paramTypes.length == 4 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                return runTaskTimerAsynchronously((Plugin) args[0], (Runnable) args[1], ((Number) args[2]).longValue(), ((Number) args[3]).longValue());
            }

            // Legacy scheduleSyncDelayedTask overloads
            if ("scheduleSyncDelayedTask".equals(name)) {
                if (paramTypes.length == 2 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                    return scheduleSyncDelayedTask((Plugin) args[0], (Runnable) args[1]);
                }
                if (paramTypes.length == 3 && paramTypes[0] == Plugin.class && paramTypes[1] == Runnable.class) {
                    return scheduleSyncDelayedTask((Plugin) args[0], (Runnable) args[1], ((Number) args[2]).longValue());
                }
            }

            // cancelTask(int)
            if ("cancelTask".equals(name) && paramTypes.length == 1 && paramTypes[0] == int.class) {
                cancelTask((Integer) args[0]);
                return null;
            }
            // cancelTasks(Plugin)
            if ("cancelTasks".equals(name) && paramTypes.length == 1 && paramTypes[0] == Plugin.class) {
                cancelTasks((Plugin) args[0]);
                return null;
            }

            // Fallback: delegate to the real Bukkit scheduler for methods we don't intercept.
            return method.invoke(Bukkit.getScheduler(), args);
        }
    }

    /**
     * Dummy BukkitTask returned for immediate execute-style calls.
     */
    private static class DummyBukkitTask implements BukkitTask {
        private final Plugin plugin;
        private final int taskId;
        private final boolean sync;

        public DummyBukkitTask(Plugin plugin, int taskId, boolean sync) {
            this.plugin = plugin;
            this.taskId = taskId;
            this.sync = sync;
        }

        @Override
        public int getTaskId() { return taskId; }

        @Override
        public Plugin getOwner() { return plugin; }

        @Override
        public boolean isSync() { return sync; }

        @Override
        public boolean isCancelled() { return false; }

        @Override
        public void cancel() {
            cancelTask(taskId);
        }
    }

    /**
     * BukkitTask wrapper around a real Folia ScheduledTask.
     */
    private static class FoliaBukkitTask implements BukkitTask {
        private final Plugin plugin;
        private final int taskId;
        private final boolean sync;
        private Object foliaTask;

        public FoliaBukkitTask(Plugin plugin, int taskId, boolean sync, Object foliaTask) {
            this.plugin = plugin;
            this.taskId = taskId;
            this.sync = sync;
            this.foliaTask = foliaTask;
        }

        public void setFoliaTask(Object foliaTask) {
            this.foliaTask = foliaTask;
        }

        @Override
        public int getTaskId() { return taskId; }

        @Override
        public Plugin getOwner() { return plugin; }

        @Override
        public boolean isSync() { return sync; }

        @Override
        public boolean isCancelled() {
            try {
                if (foliaTask != null && scheduledTaskCancel != null) {
                    // ScheduledTask.isCancelled() is available on Folia
                    Method isCancelled = foliaTask.getClass().getMethod("isCancelled");
                    return (Boolean) isCancelled.invoke(foliaTask);
                }
            } catch (Exception ignored) {
            }
            return false;
        }

        @Override
        public void cancel() {
            if (foliaTask != null && scheduledTaskCancel != null) {
                try {
                    scheduledTaskCancel.invoke(foliaTask);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "[MMOAddon-FoliaFix] Failed to cancel Folia task " + taskId + ": " + e.getMessage());
                }
            } else {
                cancelTask(taskId);
            }
        }
    }
}
