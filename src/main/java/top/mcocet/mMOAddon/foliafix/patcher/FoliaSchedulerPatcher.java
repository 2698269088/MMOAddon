package top.mcocet.mMOAddon.foliafix.patcher;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.bukkit.plugin.java.JavaPlugin;
import top.mcocet.mMOAddon.foliafix.target.MMOItemsTarget;
import top.mcocet.mMOAddon.foliafix.target.MythicLibTarget;
import top.mcocet.mMOAddon.foliafix.target.PluginTarget;
import top.mcocet.mMOAddon.foliafix.transformer.FoliaSchedulerTransformer;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * ByteBuddy-based runtime patcher for Folia scheduler compatibility.
 *
 * Uses ByteBuddy to dynamically attach to the JVM and register an ASM transformer
 * that redirects Bukkit.getScheduler() calls to the Folia-compatible helper.
 *
 * Targets are detected dynamically: if a plugin is not present, its classes are
 * simply not retransformed.
 */
public class FoliaSchedulerPatcher {

    private static boolean patched = false;
    private static JavaPlugin plugin;
    private static Instrumentation instrumentation;
    private static FoliaSchedulerTransformer transformer;

    // Default list of plugins that can be patched. Add more here if needed.
    private static final List<PluginTarget> DEFAULT_TARGETS = Arrays.asList(
            new MythicLibTarget(),
            new MMOItemsTarget()
    );

    private static final List<PluginTarget> activeTargets = new ArrayList<>();

    /**
     * Initialize and apply the patch.
     * This should be called from MMOAddon's onEnable.
     */
    public static void init(JavaPlugin pluginInstance) {
        if (patched) {
            pluginInstance.getLogger().info("[MMOAddon-FoliaFix] Scheduler patch already applied, skipping");
            return;
        }

        plugin = pluginInstance;

        // Check if we're on Folia
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
        } catch (ClassNotFoundException e) {
            pluginInstance.getLogger().info("[MMOAddon-FoliaFix] Not running on Folia, skipping scheduler patch");
            return;
        }

        // Detect which target plugins are actually loaded.
        activeTargets.clear();
        for (PluginTarget target : DEFAULT_TARGETS) {
            if (target.isLoaded()) {
                activeTargets.add(target);
            }
        }

        if (activeTargets.isEmpty()) {
            pluginInstance.getLogger().info("[MMOAddon-FoliaFix] No supported MMO plugins found, skipping scheduler patch");
            return;
        }

        // Tell the transformer which packages to patch.
        FoliaSchedulerTransformer.setActiveTargets(activeTargets);

        try {
            applyPatch();
            patched = true;
            pluginInstance.getLogger().info("[MMOAddon-FoliaFix] Scheduler ByteBuddy patch applied successfully! Targets: " + getTargetNames());
        } catch (Exception e) {
            pluginInstance.getLogger().log(Level.SEVERE, "[MMOAddon-FoliaFix] Failed to apply scheduler ByteBuddy patch: " + e.getMessage(), e);
        }
    }

    private static String getTargetNames() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < activeTargets.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(activeTargets.get(i).getPluginName());
        }
        return sb.toString();
    }

    private static void applyPatch() throws Exception {
        instrumentation = ByteBuddyAgent.install();
        transformer = new FoliaSchedulerTransformer();
        instrumentation.addTransformer(transformer, true);

        // Collect classes already loaded from active target plugins.
        List<Class<?>> targetClasses = new ArrayList<>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (shouldRetransform(clazz, name)) {
                targetClasses.add(clazz);
            }
        }

        int total = targetClasses.size();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        if (total > 0) {
            plugin.getLogger().info("[MMOAddon-FoliaFix] Retransforming " + total + " classes (one by one)...");
        }

        for (Class<?> clazz : targetClasses) {
            try {
                instrumentation.retransformClasses(clazz);
                successCount.incrementAndGet();
            } catch (Throwable t) {
                failCount.incrementAndGet();
                if (!clazz.getName().contains("$$Lambda")) {
                    plugin.getLogger().log(Level.WARNING, "[MMOAddon-FoliaFix] Failed to retransform " + clazz.getName() + ": " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
                }
            }
        }

        if (total > 0) {
            plugin.getLogger().info("[MMOAddon-FoliaFix] Retransform complete: " + successCount.get() + " success, " + failCount.get() + " failed");
        }
        plugin.getLogger().info("[MMOAddon-FoliaFix] Transformer stats: " + FoliaSchedulerTransformer.getDebugStats());
    }

    /**
     * Retransform classes loaded after initial patch.
     * Call this when a target plugin is enabled.
     */
    public static void retransformLoadedClasses(JavaPlugin pluginInstance) {
        if (!patched || instrumentation == null) {
            pluginInstance.getLogger().warning("[MMOAddon-FoliaFix] Scheduler patch not initialized, cannot retransform");
            return;
        }

        List<Class<?>> targetClasses = new ArrayList<>();
        for (Class<?> clazz : instrumentation.getAllLoadedClasses()) {
            String name = clazz.getName();
            if (shouldRetransform(clazz, name)) {
                targetClasses.add(clazz);
            }
        }

        int total = targetClasses.size();

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger skipCount = new AtomicInteger(0);

        if (total > 0) {
            pluginInstance.getLogger().info("[MMOAddon-FoliaFix] Retransforming " + total + " classes (one by one)...");
        }

        for (Class<?> clazz : targetClasses) {
            try {
                instrumentation.retransformClasses(clazz);
                successCount.incrementAndGet();
            } catch (Throwable t) {
                skipCount.incrementAndGet();
                if (!clazz.getName().contains("$$Lambda")) {
                    pluginInstance.getLogger().log(Level.WARNING, "[MMOAddon-FoliaFix] Failed to retransform " + clazz.getName() + ": " + t.getClass().getSimpleName() + " " + t.getMessage(), t);
                }
            }
        }

        if (total > 0) {
            plugin.getLogger().info("[MMOAddon-FoliaFix] Retransform complete: " + successCount.get() + " success, " + skipCount.get() + " skipped");
        }
    }

    public static boolean isPatched() {
        return patched;
    }

    /**
     * Decide whether a loaded class should be submitted to retransform.
     * Skip interfaces, lambdas, and plugin-specific skipped classes.
     */
    private static boolean shouldRetransform(Class<?> clazz, String name) {
        boolean matchesTarget = false;
        for (PluginTarget target : activeTargets) {
            if (name.startsWith(target.getPackagePrefix())) {
                if (target.shouldSkipClass(name)) {
                    return false;
                }
                matchesTarget = true;
            }
        }
        if (!matchesTarget) {
            return false;
        }
        if (clazz.isInterface()) {
            return false;
        }
        if (name.contains("$$Lambda")) {
            return false;
        }
        return true;
    }
}
