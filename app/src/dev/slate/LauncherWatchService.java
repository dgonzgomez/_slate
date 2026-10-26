package dev.slate;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Keeps _slate on the Home button and on power-on.
 * Home is an Amazon HomeActivity start. Power-on often only reveals that
 * activity again, so wake and boot also check what is in front.
 */
public class LauncherWatchService extends Service {
 public static volatile boolean sawOtherApp;
 private static long lastLaunch, launchRequestedAt;
 private static volatile ActivityManager.AppTask homeTask;
 private volatile boolean running;
 private volatile Process reader;
 private volatile long bootStarted, bootUntil;
 private volatile boolean dumpDenied;
 private BroadcastReceiver display;
 private PowerManager.WakeLock bootLock;
 private final Handler handler = new Handler(Looper.getMainLooper());
 private final ExecutorService checks = Executors.newSingleThreadExecutor(r -> {
  Thread thread = new Thread(r, "slate-power");
  thread.setDaemon(true);
  return thread;
 });

 /** Unconfigured devices can browse Slate without a privileged background watcher. */
 public static boolean start(Context context,Intent intent){
  if(context.getPackageManager().checkPermission("android.permission.READ_LOGS",context.getPackageName())!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
  try{if(android.os.Build.VERSION.SDK_INT>=26)context.startForegroundService(intent);else context.startService(intent);return true;}
  catch(Exception e){Log.w("SlateHome","Home handler could not start on this Fire OS build",e);return false;}
 }

 public void onCreate() {
  super.onCreate();
  Notification.Builder builder;
  if(android.os.Build.VERSION.SDK_INT>=26){
   android.app.NotificationManager manager=(android.app.NotificationManager)getSystemService(NOTIFICATION_SERVICE);
   manager.createNotificationChannel(new android.app.NotificationChannel("slate_home","Home button",android.app.NotificationManager.IMPORTANCE_LOW));
   builder=new Notification.Builder(this,"slate_home");
  }else builder=new Notification.Builder(this);
  Notification notification = builder.setSmallIcon(android.R.drawable.ic_menu_view)
   .setContentTitle("_slate Home handler").setContentText("Home and power-on open _slate")
   .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0))).build();
  startForeground(7, notification);
  PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
  bootLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "slate:boot");
  bootLock.setReferenceCounted(false);
  display = new BroadcastReceiver() {
   public void onReceive(Context context, Intent intent) {
    Log.i("SlateHome", "Display " + intent.getAction());
    scheduleCheck();
   }
  };
  IntentFilter filter = new IntentFilter();
  filter.addAction(Intent.ACTION_SCREEN_ON);
  filter.addAction(Intent.ACTION_DREAMING_STOPPED);
  filter.addAction(Intent.ACTION_USER_PRESENT);
  registerReceiver(display, filter);
  running = true;
  new Thread(this::watch, "slate-home").start();
 }

 public int onStartCommand(Intent intent, int flags, int id) {
  if (intent != null && intent.getBooleanExtra("boot", false)) armBoot();
  if (intent != null && intent.getBooleanExtra("wake", false)) scheduleCheck();
  return START_STICKY;
 }

 public void onTaskRemoved(Intent root) { start(this,new Intent(this, LauncherWatchService.class)); }

 private void armBoot() {
  bootStarted = SystemClock.elapsedRealtime();
  bootUntil = bootStarted + 75000;
  sawOtherApp = false;
  if (bootLock != null) bootLock.acquire(20000);
  handler.removeCallbacks(bootTick);
  handler.post(bootTick);
  Log.i("SlateHome", "Boot window armed");
 }

 private final Runnable bootTick = new Runnable() {
  public void run() {
   if (!running || SystemClock.elapsedRealtime() > bootUntil) return;
   requestForegroundCheck();
   handler.postDelayed(this, 3000);
  }
 };

 private int wakeGeneration;
 private void scheduleCheck() {
  int generation = ++wakeGeneration;
  long[] delays = {0, 700, 1800, 4000};
  for (long delay : delays) handler.postDelayed(() -> {
   if (generation == wakeGeneration) requestForegroundCheck();
  }, delay);
 }

 private void requestForegroundCheck() {
  checks.execute(() -> {
   String top = foreground();
   boolean slateVisible = slateInFront();
   handler.post(() -> applyForeground(top, slateVisible));
  });
 }

 private void applyForeground(String top, boolean slateVisible) {
  Log.i("SlateHome", "Foreground " + top + " slateVisible=" + slateVisible);
  if (isAmazonShell(top)) { open(this); return; }
  if (slateVisible || (top != null && top.contains("dev.slate"))) return;
  if (top != null) { sawOtherApp = true; return; }
  if (!sawOtherApp) open(this);
 }

 /** Cache the main task while home is idle, never on the button-press path. */
 public static void primeHomeTask(Context context){
  if(homeTask!=null)return;
  try{ActivityManager manager=(ActivityManager)context.getSystemService(ACTIVITY_SERVICE);
   for(ActivityManager.AppTask task:manager.getAppTasks()){android.content.ComponentName base=task.getTaskInfo().baseActivity;if(base!=null&&MainActivity.class.getName().equals(base.getClassName())){homeTask=task;return;}}
  }catch(Exception e){Log.w("SlateHome","Home task warmup skipped",e);}
 }

 /** Brings _slate forward. A second start is held until the first one resumes. */
 public static synchronized void open(Context context) {
  long now = SystemClock.elapsedRealtime();
  if (now < HomeService.bypassUntil || (now - lastLaunch < 80 && MainActivity.pausedAt <= lastLaunch)) return;
  if (MainActivity.interactive) return;
  if(homeTask!=null){try{homeTask.moveToFront();lastLaunch=SystemClock.elapsedRealtime();Log.i("SlateHome","Restored cached home task");return;}catch(Exception ignored){homeTask=null;}}
  try {
   ActivityManager manager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
   for (ActivityManager.AppTask task : manager.getAppTasks()) {
    android.content.ComponentName base = task.getTaskInfo().baseActivity;
    if (base != null && MainActivity.class.getName().equals(base.getClassName())) {
     task.moveToFront();
     homeTask=task;
     lastLaunch = SystemClock.elapsedRealtime();
     Log.i("SlateHome", "Brought existing task forward");
     return;
    }
   }
  } catch (Exception e) { Log.w("SlateHome", "Task restore failed", e); }
  if (launchRequestedAt > MainActivity.resumedAt && now - launchRequestedAt < 8000) {
   Log.i("SlateHome", "Launch already pending");
   return;
  }
  UserManager users = (UserManager) context.getSystemService(USER_SERVICE);
  if (android.os.Build.VERSION.SDK_INT>=24 && users != null && !users.isUserUnlocked()) return;
  launchRequestedAt = now;
  lastLaunch = now;
  Log.i("SlateHome", "Opening slate");
  try {
   context.startActivity(new Intent(context, MainActivity.class).addFlags(
    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION));
  } catch (Exception e) { Log.e("SlateHome", "Open failed", e); }
 }

 private void watch() {
  while (running) {
   try {
    reader = new ProcessBuilder("logcat", "-b", "system", "-b", "events", "-v", "brief", "-T", "1",
     "ActivityManager:I", "am_resume_activity:I", "wm_set_resumed_activity:I", "am_focused_activity:I", "*:S")
     .redirectErrorStream(true).start();
    Log.i("SlateHome", "Watcher started");
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(reader.getInputStream()))) {
     String line;
     while (running && (line = lines.readLine()) != null) onLine(line);
    }
    Log.w("SlateHome", "Log stream ended");
   } catch (Exception e) { Log.e("SlateHome", "Watcher error", e); }
   if (running) try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
  }
 }

 private void onLine(String line) {
  if (!line.contains("START ") && !line.contains("am_resume_activity") && !line.contains("wm_set_resumed_activity")
   && !line.contains("Displayed ") && !line.contains("am_focused_activity")) return;
  String component = componentOf(line);
  if (component == null || component.contains("dev.slate/") || isSystemNoise(component)) return;
  if (isAmazonShell(component)) {
   if (component.contains("screensaver") || component.contains("ftvambient")) {
    Log.i("SlateHome", "Amazon idle screen " + component);
    return;
   }
   Log.i("SlateHome", "Amazon Home " + component);
   open(this);
   return;
  }
  sawOtherApp = true;
  Log.i("SlateHome", "Left launcher for " + component);
 }

 private String foreground() {
  String dumped = dumpResumed();
  if (dumped != null) return dumped;
  return usageForeground();
 }

 private String dumpResumed() {
  if (dumpDenied) return null;
  Process process = null;
  try {
   process = new ProcessBuilder("dumpsys", "activity", "activities").redirectErrorStream(true).start();
   StringBuffer output = new StringBuffer();
   Process current = process;
   Thread pump = new Thread(() -> {
    try (BufferedReader lines = new BufferedReader(new InputStreamReader(current.getInputStream()))) {
     String line;
     while ((line = lines.readLine()) != null && output.length() < 300000) output.append(line).append('\n');
    } catch (Exception ignored) {}
   }, "slate-dump");
   pump.start();
   pump.join(2000);
   process.destroy();
   pump.join(300);
   String text = output.toString();
   if (text.contains("Permission Denial") || text.contains("permission denied")) { dumpDenied = true; return null; }
   String resumed = null, focused = null;
   for (String line : text.split("\n")) {
    if (!line.contains("ActivityRecord")) continue;
    String component = componentOf(line);
    if (component == null) continue;
    if (line.contains("mResumedActivity") || line.contains("topResumedActivity")) resumed = component;
    else if (focused == null && (line.contains("mFocusedActivity") || line.contains("mFocusedApp"))) focused = component;
   }
   return resumed != null ? resumed : focused;
  } catch (Exception e) {
   Log.w("SlateHome", "Foreground check failed", e);
   return null;
  } finally { if (process != null) process.destroy(); }
 }

 private String usageForeground() {
  try {
   UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
   if (manager == null) return null;
   long now = System.currentTimeMillis();
   UsageEvents events = manager.queryEvents(now - 15000, now);
   if (events == null) return null;
   UsageEvents.Event event = new UsageEvents.Event();
   String latest = null;
   long latestAt = 0;
   while (events.hasNextEvent()) {
    events.getNextEvent(event);
    if (event.getEventType() != UsageEvents.Event.MOVE_TO_FOREGROUND || event.getPackageName() == null) continue;
    if (event.getTimeStamp() < latestAt) continue;
    latestAt = event.getTimeStamp();
    String cls = event.getClassName();
    latest = cls == null || cls.isEmpty() ? event.getPackageName() : event.getPackageName() + "/" + cls;
   }
   if (latest == null || now - latestAt > 15000) return null;
   return latest;
  } catch (Exception e) {
   Log.w("SlateHome", "Usage check failed", e);
   return null;
  }
 }

 private boolean slateInFront() {
  try {
   ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
   List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
   if (processes == null) return false;
   for (ActivityManager.RunningAppProcessInfo info : processes) {
    if (info.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND || info.pkgList == null) continue;
    for (String pkg : info.pkgList) if (getPackageName().equals(pkg)) return true;
   }
  } catch (Exception e) { Log.w("SlateHome", "Process check failed", e); }
  return false;
 }

 private boolean isAmazonShell(String component) {
  if (component == null) return false;
  if (component.contains("com.amazon.ftv.screensaver") || component.contains("com.amazon.tv.ftvambient")) return true;
  return component.contains("com.amazon.tv.launcher/") && component.contains("HomeActivity");
 }

 private boolean isSystemNoise(String component) {
  String pkg = component.split("/")[0];
  return pkg.equals("com.android.systemui") || pkg.equals("android") || pkg.equals("com.android.providers.tv");
 }

 private String componentOf(String line) {
  int from = 0;
  while (from < line.length()) {
   int slash = line.indexOf('/', from);
   if (slash < 1) return null;
   int start = slash;
   while (start > 0 && isToken(line.charAt(start - 1))) start--;
   int end = slash + 1;
   while (end < line.length() && isToken(line.charAt(end))) end++;
   if (start < slash && end > slash + 1 && line.substring(start, slash).indexOf('.') >= 0) return line.substring(start, end);
   from = slash + 1;
  }
  return null;
 }

 private boolean isToken(char c) { return c == '.' || c == '_' || c == '$' || Character.isLetterOrDigit(c); }

 public IBinder onBind(Intent intent) { return null; }

 public void onDestroy() {
  running = false;
  handler.removeCallbacksAndMessages(null);
  if (display != null) unregisterReceiver(display);
  if (reader != null) reader.destroy();
  checks.shutdownNow();
  if (bootLock != null && bootLock.isHeld()) bootLock.release();
  super.onDestroy();
 }
}
