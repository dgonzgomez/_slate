package dev.slate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the home handler after boot, update, or screensaver exit. */
public class BootReceiver extends BroadcastReceiver {
 public void onReceive(Context context, Intent intent) {
  String action = intent == null ? null : intent.getAction();
  Log.i("SlateHome", "Boot event " + action);
  if (action == null) return;
  boolean locked = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
  boolean boot = locked || Intent.ACTION_BOOT_COMPLETED.equals(action)
   || "android.intent.action.QUICKBOOT_POWERON".equals(action)
   || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action);
  boolean wake = Intent.ACTION_USER_PRESENT.equals(action) || Intent.ACTION_DREAMING_STOPPED.equals(action);
  if (!boot && !wake && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
  Intent service = new Intent(context, LauncherWatchService.class);
  if (boot) service.putExtra("boot", true);
  if (wake) service.putExtra("wake", true);
  boolean started=LauncherWatchService.start(context,service);
  if (started && boot && !locked) LauncherWatchService.open(context);
 }
}
