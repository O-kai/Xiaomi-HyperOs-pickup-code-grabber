package io.github.okaidev.pickupcode;

import android.content.Context;

/** Explicit user action; changes only this package's stock rear-screen switch. */
final class RearScreenSettings {
    private static final String URI =
            "content://com.xiaomi.subscreencenter.settings.SubScreenAppProvider/packages";

    private RearScreenSettings() { }

    static boolean enable(Context context) {
        if (!RearScreen.available(context)) return false;
        String pkg = context.getPackageName();
        if (!pkg.matches("[A-Za-z0-9_.]+")) return false;
        // Android assigns 100,000 UID slots to each user/profile.
        int user = android.os.Process.myUid() / 100_000;
        String query = "content query --uri " + URI + " --projection pkgName:userId:enable";
        RootShell.Result before = RootShell.execResult(query, 10_000);
        if (before == null || !before.ok()) return false;
        String values = " --uri " + URI + " --bind pkgName:s:" + pkg
                + " --bind userId:i:" + user + " --bind enable:i:1";
        // This provider matches updates using pkgName + userId in ContentValues.
        // The CLI's --where argument is ignored by it; never use delete here.
        if (!hasRow(before.output, pkg, user, false)) {
            RootShell.Result insert = RootShell.execResult("content insert" + values, 10_000);
            if (insert == null || !insert.ok()) return false;
        }
        RootShell.Result update = RootShell.execResult("content update" + values, 10_000);
        if (update == null || !update.ok()) return false;
        RootShell.Result after = RootShell.execResult(query, 10_000);
        return after != null && after.ok() && hasRow(after.output, pkg, user, true);
    }

    private static boolean hasRow(String output, String pkg, int user, boolean enabled) {
        for (String row : output.split("\\R")) {
            if (row.contains("pkgName=" + pkg + ",")
                    && row.matches(".*userId=" + user + "(?:,|\\s*$).*")
                    && (!enabled || row.matches(".*enable=1\\s*$"))) return true;
        }
        return false;
    }
}
