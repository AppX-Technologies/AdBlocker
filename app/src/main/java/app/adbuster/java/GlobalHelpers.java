package app.adbuster.java;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

public class GlobalHelpers {

    public List<String> getBrowsers(Context context) {
        List<String> browserNames = new ArrayList<>();

        Intent mainIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"));
        List<ResolveInfo> pkgAppsList = context.getPackageManager().queryIntentActivities(mainIntent, 0);
        for (ResolveInfo info : pkgAppsList) {
            browserNames.add(info.activityInfo.packageName);
        }

        return browserNames;
    }
}
