package me.weishu.kernelsu.magica;

import static me.weishu.kernelsu.magica.AppZygotePreload.TAG;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.util.Log;

import me.weishu.kernelsu.BuildConfig;
import me.weishu.kernelsu.ui.util.KsuCliKt;

public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String ACTION_MAGICA_LAUNCH = BuildConfig.APPLICATION_ID + ".magica.LAUNCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        var action = intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !ACTION_MAGICA_LAUNCH.equals(action)) {
            return;
        }
        if (KsuCliKt.rootAvailable()) return;
        try {
            var userContext = context;
            var userManager = context.getSystemService(UserManager.class);
            if (userManager != null && !userManager.isUserUnlocked()) {
                userContext = context.createDeviceProtectedStorageContext();
            }
            userContext.startService(new Intent(userContext, MagicaService.class));
            Log.i(TAG, "MagicaService started from boot action: " + action);
        } catch (Throwable e) {

            Log.e(TAG, "Failed to start MagicaService from boot action: " + action, e);
        }
    }
}
