package logbook.internal.updatecheck;

/** The legacy single-JAR updater must not split an atomically selected bundle. */
public final class ManagedBundleMode {
    public static final String PROPERTY = "logbook.runtime.managedBundle";

    private ManagedBundleMode() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    public static void requireLegacyUpdater() {
        if (enabled())
            throw new IllegalStateException(
                    "分割版は専用管理ツールで一式を更新してください。単体JAR更新は使用できません。");
    }
}
