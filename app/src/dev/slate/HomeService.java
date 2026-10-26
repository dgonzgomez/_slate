package dev.slate;
import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.content.Intent;
public class HomeService extends AccessibilityService {
 public static long bypassUntil=0;
 private long lastLaunch=0;
 public void onAccessibilityEvent(AccessibilityEvent e){
  long now=android.os.SystemClock.elapsedRealtime();
  if(now<bypassUntil||now-lastLaunch<700||e.getClassName()==null)return;
  String name=e.getClassName().toString();
  if(name.contains("HomeActivity")){
   lastLaunch=now;
   startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
  }
 }
 public void onInterrupt(){}
}
