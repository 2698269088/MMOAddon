package top.mcocet.mMOAddon.foliafix.target;

/**
 * Target plugin: MythicLib.
 */
public class MythicLibTarget implements PluginTarget {

    @Override
    public String getPluginName() {
        return "MythicLib";
    }

    @Override
    public String getMainClassName() {
        return "io.lumine.mythic.lib.MythicLib";
    }

    @Override
    public String getPackagePrefix() {
        return "io.lumine.mythic.lib.";
    }

    @Override
    public boolean shouldSkipClass(String className) {
        // Skip internal libraries that do not call Bukkit.getScheduler() and are sensitive to retransformation.
        // Retransforming gson/exp4j internals can trigger LinkageError due to duplicate class definitions.
        if (className.startsWith("io.lumine.mythic.lib.gson.")
                || className.startsWith("io.lumine.mythic.lib.exp4j.")) {
            return true;
        }
        // Skip optional hologram integration classes whose bytecode references external plugin
        // classes (Holograms, CMI). Retransforming them produces invalid class files on the JVM
        // verifier even though they do not need scheduler patching.
        if ("io.lumine.mythic.lib.hologram.factory.HologramsHologramFactory$HologramImpl".equals(className)
                || "io.lumine.mythic.lib.hologram.factory.CMIHologramFactory$HologramImpl".equals(className)) {
            return true;
        }
        return false;
    }
}
