package top.mcocet.mMOAddon.foliafix.common;

import top.mcocet.mMOAddon.foliafix.target.MMOItemsTarget;
import top.mcocet.mMOAddon.foliafix.target.MythicLibTarget;
import top.mcocet.mMOAddon.foliafix.transformer.FoliaSchedulerTransformer;

import java.lang.instrument.Instrumentation;
import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Java Agent for Folia compatibility patching.
 *
 * This agent is loaded at JVM startup via -javaagent flag and registers
 * the ASM transformer before any plugin classes are loaded.
 *
 * Usage: Add to server startup flags:
 *   -javaagent:plugins/MMOAddon-1.0.jar
 */
public class FoliaFixAgent {

    private static final Logger LOGGER = Logger.getLogger("MMOAddon-FoliaFix");

    /**
     * Called when the agent is loaded at JVM startup via -javaagent
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LOGGER.info("[MMOAddon-FoliaFix] Agent loaded via premain");
        init(inst);
    }

    /**
     * Called when the agent is loaded dynamically after JVM startup
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LOGGER.info("[MMOAddon-FoliaFix] Agent loaded via agentmain");
        init(inst);
    }

    private static void init(Instrumentation inst) {
        // Register the ASM transformer for all supported target plugins.
        // At agent time we cannot detect which plugins will be loaded, so we register both;
        // classes from absent plugins will simply never be seen by the transformer.
        FoliaSchedulerTransformer.setActiveTargets(Arrays.asList(new MythicLibTarget(), new MMOItemsTarget()));
        FoliaSchedulerTransformer transformer = new FoliaSchedulerTransformer();
        inst.addTransformer(transformer, true);

        LOGGER.info("[MMOAddon-FoliaFix] ASM transformer registered. MythicLib/MMOItems classes will be transformed on load.");
    }
}
