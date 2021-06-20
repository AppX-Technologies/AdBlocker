package app.adbuster.java;

import android.content.Context;
import android.view.View;
import android.view.Window;

import androidx.appcompat.app.AlertDialog;

public class UIHelper {

    public static AlertDialog createAlertDialog(Context context, View layout) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(layout);
        AlertDialog dialog = builder.create();
        return dialog;
    }
}
