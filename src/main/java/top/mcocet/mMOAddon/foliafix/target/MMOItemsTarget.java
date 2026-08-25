package top.mcocet.mMOAddon.foliafix.target;

/**
 * Target plugin: MMOItems.
 */
public class MMOItemsTarget implements PluginTarget {

    @Override
    public String getPluginName() {
        return "MMOItems";
    }

    @Override
    public String getMainClassName() {
        return "net.Indyuce.mmoitems.MMOItems";
    }

    @Override
    public String getPackagePrefix() {
        return "net.Indyuce.mmoitems.";
    }

    @Override
    public boolean shouldSkipClass(String className) {
        // Skip optional RPG plugin hooks (AureliumSkills, AuraSkills, etc.).
        // Their bytecode references external plugin classes that are not loaded,
        // causing "class redefinition failed: invalid class" during retransform.
        if (className.startsWith("net.Indyuce.mmoitems.comp.rpg.")) {
            return true;
        }
        return false;
    }
}
