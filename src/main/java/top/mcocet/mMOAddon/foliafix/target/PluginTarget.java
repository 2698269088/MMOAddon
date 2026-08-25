package top.mcocet.mMOAddon.foliafix.target;

/**
 * Describes a third-party plugin whose classes may need Folia scheduler compatibility patching.
 */
public interface PluginTarget {

    /**
     * Returns the plugin name as shown in plugin.yml (e.g. "MythicLib").
     */
    String getPluginName();

    /**
     * Returns the fully qualified main class name used to detect whether the plugin is present.
     */
    String getMainClassName();

    /**
     * Returns the package prefix (with trailing dot) for classes belonging to this plugin,
     * e.g. "io.lumine.mythic.lib.".
     */
    String getPackagePrefix();

    /**
     * Returns the internal slash-form package prefix used by ASM, e.g. "io/lumine/mythic/lib/".
     */
    default String getSlashPackagePrefix() {
        return getPackagePrefix().replace('.', '/');
    }

    /**
     * Checks whether this plugin's main class is loaded in the current JVM.
     */
    default boolean isLoaded() {
        try {
            Class.forName(getMainClassName());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Returns true if the given class should be skipped by the transformer/retransformer.
     *
     * @param className the fully qualified class name (dot-separated)
     */
    boolean shouldSkipClass(String className);
}
