package dev.slate;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
 private LinearLayout root, rail, appLayer, sourceLayer, inputRail;
 private boolean sourcesActive, depthInitialized;
 private BlurPreview appPreview, sourcePreview;
 private int sourceIndex;
 private Wallpaper wallpaper;
 private boolean introPlayed, returnAnimationPending, launchInFlight;
 private static final long PORTAL_DURATION_MS=160;
 private static final long RETURN_DURATION_MS=120;
 private final android.os.Handler motionHandler=new android.os.Handler();
 private final Object blurLock=new Object();
 private final java.util.concurrent.ExecutorService snapshotWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"slate-snapshot");t.setDaemon(true);return t;});
 private Runnable prepareRunnable;private int prepareGeneration;
 private PreparedLaunch preparedLaunch;
 private boolean activityResumed;
 private volatile boolean destroying;
 private final HashMap<String,Intent> launchIntents=new HashMap<>();
 private String returnPackage;
 static volatile long pausedAt;
 static volatile boolean interactive;
 private static class PreparedLaunch{
  final String pkg;final Bitmap background,tile;final RectF target;PreparedLaunch(String pkg,Bitmap background,Bitmap tile,RectF target){this.pkg=pkg;this.background=background;this.tile=tile;this.target=target;}
 }
 private LaunchSurface launchSurface;
 private ReturnSurface returnSurface;
 private Bitmap returnBackground,returnTile;
 private RectF returnTarget;
 private Bitmap wallpaperPhoto;
 private int wallpaperPhotoTheme=-1;
 private String wallpaperPhotoCustom="";
 private android.renderscript.RenderScript blurRuntime;
 private android.renderscript.ScriptIntrinsicBlur blurFilter;
 android.content.SharedPreferences prefs;
 final ArrayList<String> favorites=new ArrayList<>();
 private View settingsControl;
 private SettingsPanel panel;
 private String focusedPackage;
 private PopupWindow editPopup;
 private android.media.tv.TvInputManager inputs;
 private boolean inputCallbackRegistered;
 private final ArrayList<android.media.tv.TvInputInfo> hdmiInputs=new ArrayList<>();
 private final android.media.tv.TvInputManager.TvInputCallback inputCallback=new android.media.tv.TvInputManager.TvInputCallback(){
  public void onInputStateChanged(String inputId,int state){refreshInputs();}
  public void onInputAdded(String inputId){refreshInputs();}
  public void onInputRemoved(String inputId){refreshInputs();}
 };
 private final String[] defaults={"com.netflix.ninja","com.disney.disneyplus","com.hbo.hbonow","com.hulu.plus","com.crunchyroll.crunchyroid","com.amazon.avod","dev.dremio.flutter","com.stremio.one"};
 private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
 @Override public void onCreate(Bundle b){
  super.onCreate(b);getWindow().setWindowAnimations(0);LauncherWatchService.start(this,new Intent(this,LauncherWatchService.class));
  prefs=getSharedPreferences("slate_home",0);
  if(!prefs.contains("wallpaper_theme")&&prefs.getInt("wallpaper",0)<3)prefs.edit().putString("wallpaper_theme","night").apply();
  String saved=prefs.getString("favorites",null);
  for(String p:saved==null?defaults:saved.split(",")){if(p.equals("dev.dremio")&&intentFor(p)==null)p="dev.dremio.flutter";if(intentFor(p)!=null&&!favorites.contains(p))favorites.add(p);}
  if(saved==null&&favorites.isEmpty()){
   for(ResolveInfo app:getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),0)){
    String pkg=app.activityInfo.packageName;if(!pkg.equals(getPackageName())&&!favorites.contains(pkg))favorites.add(pkg);
   }
  }
  if(b!=null){returnPackage=b.getString("return_pkg");returnAnimationPending=returnPackage!=null;if(returnPackage!=null)focusedPackage=returnPackage;}
  render();
 }
 private void render(){
  cancelPreparation();preparedLaunch=null;launchIntents.clear();discoverInputs();
  getWindow().getDecorView().setSystemUiVisibility(5894);
  FrameLayout frame=new FrameLayout(this);wallpaper=new Wallpaper();frame.addView(wallpaper);frame.setClipChildren(false);frame.setClipToPadding(false);
  root=new LinearLayout(this);root.setOrientation(1);root.setClipChildren(false);root.setClipToPadding(false);root.setPadding(dp(40),dp(32),dp(40),dp(26));frame.addView(root);setContentView(frame);
  ViewGroup content=(ViewGroup)getWindow().getDecorView();content.setClipChildren(false);content.setClipToPadding(false);ViewGroup holder=(ViewGroup)findViewById(android.R.id.content);if(holder!=null){holder.setClipChildren(false);holder.setClipToPadding(false);}
  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
  header.addView(new View(this),new LinearLayout.LayoutParams(0,dp(42),1));
  TextClock clock=new TextClock(this);clock.setFormat12Hour("h:mm");clock.setFormat24Hour("HH:mm");clock.setTextSize(19);clock.setTextColor(0xf2f5f5f7);clock.setTypeface(Typeface.create("sans-serif-medium",0));clock.setShadowLayer(dp(6),0,dp(1),0x66000000);header.addView(clock);
  GearButton settings=new GearButton();settings.setOnClickListener(v->settings());settingsControl=settings;settings.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){if(sourcesActive&&inputRail.getChildCount()>0)inputRail.getChildAt(Math.min(sourceIndex,inputRail.getChildCount()-1)).requestFocus();else if(!favorites.isEmpty())rail.getChildAt(Math.max(0,favorites.indexOf(focusedPackage))).requestFocus();}return true;}return false;});LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(dp(40),dp(40));slp.setMargins(dp(20),0,0,0);header.addView(settings,slp);
  FrameLayout stage=new FrameLayout(this);stage.setClipChildren(false);stage.setClipToPadding(false);root.addView(stage,new LinearLayout.LayoutParams(-1,0,1f));
  appLayer=new LinearLayout(this);appLayer.setOrientation(1);appLayer.setClipChildren(false);appLayer.setClipToPadding(false);FrameLayout.LayoutParams appsLp=new FrameLayout.LayoutParams(-1,dp(160),Gravity.BOTTOM);appsLp.bottomMargin=dp(26);appsLp.leftMargin=-dp(40);appsLp.rightMargin=-dp(40);stage.addView(appLayer,appsLp);
  HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setPadding(dp(40),dp(26),0,dp(18));scroll.setClipChildren(false);scroll.setClipToPadding(false);scroll.setHorizontalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
  rail=new LinearLayout(this);rail.setPadding(0,0,0,0);rail.setClipChildren(false);rail.setClipToPadding(false);scroll.addView(rail);appLayer.addView(scroll,new LinearLayout.LayoutParams(-1,dp(160)));

  for(String p:favorites)addApp(p);
  HorizontalScrollView inputScroll=new HorizontalScrollView(this);inputScroll.setHorizontalScrollBarEnabled(false);inputScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);inputScroll.setClipToPadding(false);inputScroll.setClipChildren(false);inputScroll.setPadding(dp(18),dp(10),dp(18),dp(10));
  sourceLayer=new LinearLayout(this);sourceLayer.setOrientation(1);sourceLayer.setClipChildren(false);sourceLayer.setClipToPadding(false);FrameLayout.LayoutParams sourcesLp=new FrameLayout.LayoutParams(-1,dp(88),Gravity.BOTTOM);sourcesLp.bottomMargin=-dp(62);stage.addView(sourceLayer,sourcesLp);
  LinearLayout inputs=new LinearLayout(this);inputRail=inputs;inputs.setGravity(Gravity.CENTER_VERTICAL);inputs.setClipChildren(false);inputs.setClipToPadding(false);inputScroll.addView(inputs);LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(-1,dp(88));sourceLayer.addView(inputScroll,ilp);
  for(int i=1;i<=hdmiInputs.size();i++){final int port=i;LinearLayout input=new LinearLayout(this);input.setGravity(Gravity.CENTER_VERTICAL);input.setPadding(dp(16),0,dp(14),0);input.setBackground(glass(false));input.setFocusable(true);input.setClickable(true);input.setContentDescription(inputLabel(i));
   HdmiIcon icon=new HdmiIcon();input.addView(icon,new LinearLayout.LayoutParams(dp(26),dp(26)));
   LinearLayout words=new LinearLayout(this);words.setOrientation(1);LinearLayout.LayoutParams wordsLp=new LinearLayout.LayoutParams(-2,-2);wordsLp.leftMargin=dp(12);input.addView(words,wordsLp);
   TextView label=text(inputLabel(i),15,0xfff2f2f7);label.setTypeface(Typeface.create("sans-serif-medium",0));words.addView(label);TextView status=text("No signal",11,0x99ebebf5);words.addView(status);
   LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(152),dp(62));lp.rightMargin=dp(16);inputs.addView(input,lp);input.setOnClickListener(v->hdmi(port));input.setOnFocusChangeListener((v,f)->{if(f)sourceIndex=port-1;paintInput(input,f);v.animate().scaleX(f?1.06f:1).scaleY(f?1.06f:1).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();v.setElevation(dp(f?12:0));});
   input.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_UP){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)settingsControl.requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0){setDepth(false,true);if(!favorites.isEmpty())rail.getChildAt(Math.max(0,favorites.indexOf(focusedPackage))).requestFocus();else settings.requestFocus();}return true;}return false;});
  }
  refreshInputs();
  appPreview=new BlurPreview();FrameLayout.LayoutParams ap=new FrameLayout.LayoutParams(-1,dp(160),Gravity.BOTTOM);ap.bottomMargin=dp(26);ap.leftMargin=-dp(40);ap.rightMargin=-dp(40);stage.addView(appPreview,ap);
  sourcePreview=new BlurPreview();FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(88),Gravity.BOTTOM);sp.bottomMargin=-dp(62);stage.addView(sourcePreview,sp);
  depthInitialized=false;setDepth(false,false);stage.post(()->{depthInitialized=false;setDepth(false,false);if(!returnAnimationPending)playIntro();warmFocusedLaunch();maybeReturn();});
  if(favorites.isEmpty())settings.requestFocus();
  if(!favorites.isEmpty()){int idx=Math.max(0,favorites.indexOf(focusedPackage));rail.getChildAt(idx).requestFocus();}
 }
 /** Only property transforms on cards already in the visible viewport. */
 private void playIntro(){
  if(introPlayed||rail==null)return;introPlayed=true;
  int visible=0;Rect bounds=new Rect();
  for(int i=0;i<rail.getChildCount();i++){
   View card=rail.getChildAt(i);if(!card.getGlobalVisibleRect(bounds)||bounds.width()<dp(20))continue;
   float scale=card.hasFocus()?1.08f:1f,y=card.hasFocus()?-dp(3):0;
   card.animate().cancel();card.setAlpha(0);card.setScaleX(scale*.94f);card.setScaleY(scale*.94f);card.setTranslationY(y+dp(8));
   card.animate().alpha(1).scaleX(scale).scaleY(scale).translationY(y).setStartDelay(Math.min(visible++,5)*16).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(()->card.animate().setStartDelay(0)).start();
  }
 }
 private void settleCards(){
  if(rail==null)return;
  for(int i=0;i<rail.getChildCount();i++){View card=rail.getChildAt(i);card.animate().cancel();card.animate().setStartDelay(0);card.setAlpha(1);card.setScaleX(card.hasFocus()?1.08f:1);card.setScaleY(card.hasFocus()?1.08f:1);card.setTranslationY(card.hasFocus()?-dp(3):0);}
 }
 private void cancelPreparation(){prepareGeneration++;if(prepareRunnable!=null)motionHandler.removeCallbacks(prepareRunnable);prepareRunnable=null;}
 private void warmFocusedLaunch(){
  cancelPreparation();if(rail==null||sourcesActive||panel!=null||launchInFlight||returnSurface!=null||!activityResumed)return;
  final int generation=prepareGeneration;final String pkg=focusedPackage;
  if(pkg==null||preparedLaunch!=null&&pkg.equals(preparedLaunch.pkg))return;
  prepareRunnable=()->{
   if(generation!=prepareGeneration||!activityResumed||launchInFlight||returnSurface!=null||sourcesActive||panel!=null)return;
   View card=rail.findFocus();if(card==null)return;FrameLayout frame=(FrameLayout)wallpaper.getParent();if(frame.getWidth()==0)return;
   Bitmap image=Bitmap.createBitmap(Math.max(1,frame.getWidth()/6),Math.max(1,frame.getHeight()/6),Bitmap.Config.ARGB_8888);
   Canvas canvas=new Canvas(image);canvas.scale(image.getWidth()/(float)frame.getWidth(),image.getHeight()/(float)frame.getHeight());float alpha=card.getAlpha();
   try{card.setAlpha(0);frame.draw(canvas);}finally{card.setAlpha(alpha);}
   int width=Math.max(1,Math.round(card.getWidth()*1.08f)),height=Math.max(1,Math.round(card.getHeight()*1.08f));Bitmap tile=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas sharp=new Canvas(tile);sharp.scale(width/(float)card.getWidth(),height/(float)card.getHeight());card.draw(sharp);
   int[] xy=new int[2],origin=new int[2];card.getLocationOnScreen(xy);frame.getLocationOnScreen(origin);RectF target=new RectF(xy[0]-origin[0],xy[1]-origin[1],xy[0]-origin[0]+width,xy[1]-origin[1]+height);
   snapshotWorker.execute(()->{blurImage(image,1.7f);motionHandler.post(()->{if(generation==prepareGeneration&&!isDestroyed())preparedLaunch=new PreparedLaunch(pkg,image,tile,target);});});
  };
  motionHandler.postDelayed(prepareRunnable,260);
 }
 private void blurImage(Bitmap image,float radius){
  synchronized(blurLock){if(destroying)return;android.renderscript.Allocation input=null,output=null;
   try{if(blurRuntime==null){blurRuntime=android.renderscript.RenderScript.create(MainActivity.this);blurFilter=android.renderscript.ScriptIntrinsicBlur.create(blurRuntime,android.renderscript.Element.U8_4(blurRuntime));}input=android.renderscript.Allocation.createFromBitmap(blurRuntime,image);output=android.renderscript.Allocation.createTyped(blurRuntime,input.getType());blurFilter.setRadius(radius);blurFilter.setInput(input);blurFilter.forEach(output);output.copyTo(image);}
   catch(Exception e){android.util.Log.w("Slate","Blur fallback",e);}finally{if(input!=null)input.destroy();if(output!=null)output.destroy();}
  }
 }
 private void launchApp(String pkg,View card){
  long pressed=android.os.SystemClock.elapsedRealtime();if(launchInFlight)return;Intent intent=launchIntents.get(pkg);if(intent==null)intent=intentFor(pkg);if(intent==null)return;final Intent launchIntent=intent;
  cancelPreparation();settleCards();launchInFlight=true;
  FrameLayout frame=(FrameLayout)wallpaper.getParent();
  ActivityOptions transition=null;
  try{
   PreparedLaunch prepared=preparedLaunch!=null&&pkg.equals(preparedLaunch.pkg)?preparedLaunch:null;
   Bitmap tile,background;RectF position;
   if(prepared!=null){tile=prepared.tile;background=prepared.background;position=new RectF(prepared.target);}
   else{
    int width=Math.max(1,Math.round(card.getWidth()*card.getScaleX())),height=Math.max(1,Math.round(card.getHeight()*card.getScaleY()));tile=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);Canvas sharp=new Canvas(tile);sharp.scale(width/(float)card.getWidth(),height/(float)card.getHeight());card.draw(sharp);
    int[] xy=new int[2],origin=new int[2];card.getLocationOnScreen(xy);frame.getLocationOnScreen(origin);position=new RectF(xy[0]-origin[0],xy[1]-origin[1],xy[0]-origin[0]+width,xy[1]-origin[1]+height);background=null;
   }
   returnBackground=background;returnTile=tile;returnTarget=new RectF(position);returnPackage=pkg;
   launchSurface=new LaunchSurface(background,tile,position);frame.addView(launchSurface,new FrameLayout.LayoutParams(-1,-1));
   int none=getResources().getIdentifier("slate_no_transition","anim",getPackageName()),fade=getResources().getIdentifier("slate_app_enter","anim",getPackageName());transition=ActivityOptions.makeCustomAnimation(this,fade,none);
  }catch(Exception e){clearLaunchSurface();launchInFlight=true;android.util.Log.w("SlateMotion","Using standard app transition",e);}
  final ActivityOptions options=transition;
  Runnable start=()->{
   if(isFinishing()||!launchInFlight)return;
   try{returnAnimationPending=true;if(options!=null)startActivity(launchIntent,options.toBundle());else startActivity(launchIntent);android.util.Log.i("SlateMotion","App launch requested with portal transition");}
   catch(Exception e){returnAnimationPending=false;clearLaunchSurface();settleCards();android.util.Log.e("Slate","Launch failed",e);Toast.makeText(this,"This shortcut is unavailable on your TV",Toast.LENGTH_SHORT).show();}
  };
  if(launchSurface==null){start.run();return;}
  // A single animation clock drives both blur and zoom; no second OS zoom.
  LaunchSurface surface=launchSurface;
  surface.run=android.animation.ValueAnimator.ofFloat(0,1);surface.run.setDuration(PORTAL_DURATION_MS);surface.run.setInterpolator(new android.view.animation.DecelerateInterpolator());
  surface.run.addUpdateListener(animation->{surface.progress=(float)animation.getAnimatedValue();surface.invalidate();});
  surface.run.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator animation){start.run();}});
  surface.run.start();android.util.Log.i("SlateMotion","Portal ready "+(android.os.SystemClock.elapsedRealtime()-pressed)+"ms cached="+(returnBackground!=null));
 }
 private void clearLaunchSurface(){
  launchInFlight=false;
  if(launchSurface!=null){if(launchSurface.run!=null){launchSurface.run.removeAllListeners();launchSurface.run.cancel();}launchSurface.animate().cancel();ViewGroup parent=(ViewGroup)launchSurface.getParent();if(parent!=null)parent.removeView(launchSurface);launchSurface=null;}
 }
 private class LaunchSurface extends View{
  private final Bitmap background,tile;private final RectF selected,rect=new RectF();private final Paint paint=new Paint(3);private final Path corners=new Path();float progress;android.animation.ValueAnimator run;
  LaunchSurface(Bitmap background,Bitmap tile,RectF selected){super(MainActivity.this);this.background=background;this.tile=tile;this.selected=selected;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(true);}
  protected void onDraw(Canvas canvas){
   float p=progress;paint.setAlpha(Math.round(255*p));
   if(background!=null)canvas.drawBitmap(background,null,new Rect(0,0,getWidth(),getHeight()),paint);
   canvas.drawColor(Color.argb(Math.round(38*p),0,0,0));paint.setAlpha(255);
   rect.set(selected.left*(1-p),selected.top*(1-p),selected.right+(getWidth()-selected.right)*p,selected.bottom+(getHeight()-selected.bottom)*p);
   corners.reset();corners.addRoundRect(rect,dp(16)*(1-p),dp(16)*(1-p),Path.Direction.CW);int saved=canvas.save();canvas.clipPath(corners);canvas.drawColor(Color.BLACK);paint.setAlpha(Math.round(255*Math.max(0,1-p/.22f)));canvas.drawBitmap(tile,null,selected,paint);paint.setAlpha(255);canvas.restoreToCount(saved);
  }
 }
 private void playReturn(){
  clearReturnSurface();settleCards();
  if(returnTile==null||returnTarget==null){root.setAlpha(.88f);root.animate().alpha(1).setDuration(120).start();return;}
  FrameLayout frame=(FrameLayout)wallpaper.getParent();
  ReturnSurface surface=new ReturnSurface(returnBackground,returnTile,new RectF(returnTarget));returnSurface=surface;
  frame.addView(surface,new FrameLayout.LayoutParams(-1,-1));
  surface.run=android.animation.ValueAnimator.ofFloat(0,1);surface.run.setDuration(RETURN_DURATION_MS);surface.run.setInterpolator(new android.view.animation.DecelerateInterpolator());
  surface.run.addUpdateListener(a->{surface.progress=(float)a.getAnimatedValue();surface.invalidate();});
  surface.run.addListener(new android.animation.AnimatorListenerAdapter(){public void onAnimationEnd(android.animation.Animator animation){if(returnSurface==surface){clearReturnSurface();returnBackground=null;returnTile=null;returnTarget=null;returnPackage=null;android.util.Log.i("SlateMotion","Reverse portal completed");warmFocusedLaunch();}}});
  surface.startWhenDrawn=true;surface.invalidate();
 }
 private void clearReturnSurface(){
  ReturnSurface surface=returnSurface;returnSurface=null;if(surface==null)return;
  if(surface.run!=null){surface.run.removeAllListeners();surface.run.cancel();}ViewGroup parent=(ViewGroup)surface.getParent();if(parent!=null)parent.removeView(surface);
 }
 private class ReturnSurface extends View{
  final Bitmap background,tile;final RectF target,rect=new RectF();final Paint paint=new Paint(3);final Path corners=new Path();float progress;boolean startWhenDrawn;android.animation.ValueAnimator run;
  ReturnSurface(Bitmap background,Bitmap tile,RectF target){super(MainActivity.this);this.background=background;this.tile=tile;this.target=target;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setFocusable(false);setClickable(false);}
  protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(run==null)return;if(visibility==View.VISIBLE){if(run.isPaused())run.resume();else if(!run.isStarted()){startWhenDrawn=true;invalidate();}}else if(run.isStarted())run.pause();}
  protected void onDraw(Canvas canvas){
   if(startWhenDrawn&&returnSurface==this&&activityResumed&&getWindowVisibility()==View.VISIBLE){startWhenDrawn=false;run.start();android.util.Log.i("SlateMotion","Reverse portal first visible frame");}
   float p=progress;
   paint.setAlpha(Math.round(255*(1-p)));if(background!=null)canvas.drawBitmap(background,null,new Rect(0,0,getWidth(),getHeight()),paint);canvas.drawColor(Color.argb(Math.round(38*(1-p)),0,0,0));
   rect.set(target.left*p,target.top*p,getWidth()+(target.right-getWidth())*p,getHeight()+(target.bottom-getHeight())*p);
   float radius=dp(16)*p;corners.reset();corners.addRoundRect(rect,radius,radius,Path.Direction.CW);int saved=canvas.save();canvas.clipPath(corners);
   canvas.drawColor(Color.BLACK);paint.setAlpha(Math.round(255*Math.max(0,(p-.9f)/.1f)));canvas.drawBitmap(tile,null,target,paint);canvas.restoreToCount(saved);paint.setAlpha(255);
  }
 }
 private void setDepth(boolean sources,boolean animate){
  if(appLayer==null||sourceLayer==null||appPreview==null||sourcePreview==null)return;
  if(sources&&inputRail.getChildCount()==0)return;
  if(depthInitialized&&sourcesActive==sources)return;
  depthInitialized=true;
  if(sources)appPreview.capture(appLayer);else sourcePreview.capture(sourceLayer);
  sourcesActive=sources;cancelPreparation();if(!sources)motionHandler.postDelayed(()->warmFocusedLaunch(),300);
  sourceLayer.setDescendantFocusability(sources?android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS:android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS);
  appLayer.setDescendantFocusability(sources?android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS:android.view.ViewGroup.FOCUS_AFTER_DESCENDANTS);
  long duration=animate?280:0;
  shift(appLayer,sources?dp(142):0,1,sources?0:1,duration);
  shift(appPreview,sources?dp(142):0,1,sources?0.48f:0,duration);
  shift(sourceLayer,sources?-dp(130):0,1,sources?1:0,duration);
  shift(sourcePreview,sources?-dp(130):0,sources?1:0.96f,sources?0:0.48f,duration);
 }
 private void shift(View view,float y,float scale,float alpha,long duration){view.setPivotX(0);view.setPivotY(0);view.animate().translationY(y).scaleX(scale).scaleY(scale).rotationX(0).alpha(alpha).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}
 private class BlurPreview extends View{
  Bitmap image;Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
  BlurPreview(){super(MainActivity.this);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setFocusable(false);setClickable(false);setAlpha(0);}
  void capture(View original){
   if(original.getWidth()==0||original.getHeight()==0)return;
   Bitmap small=Bitmap.createBitmap(Math.max(1,original.getWidth()/4),Math.max(1,original.getHeight()/4),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(small);canvas.scale(small.getWidth()/(float)original.getWidth(),small.getHeight()/(float)original.getHeight());original.draw(canvas);
   blurImage(small,2.5f);
   image=small;invalidate();
  }
  protected void onDraw(Canvas canvas){if(image!=null)canvas.drawBitmap(image,null,new Rect(0,0,getWidth(),getHeight()),paint);}
  protected void onDetachedFromWindow(){if(image!=null){image.recycle();image=null;}super.onDetachedFromWindow();}
 }
 private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);t.setFontFeatureSettings("kern");t.setSingleLine(true);t.setEllipsize(android.text.TextUtils.TruncateAt.END);return t;}
 private GradientDrawable surface(int color,float radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
 static void drawGear(Canvas c,float cx,float cy,float r,Paint paint){
  Path gear=new Path();int teeth=8;
  for(int i=0;i<teeth*2;i++){
   double a0=Math.PI*2*i/(teeth*2)-Math.PI/(teeth*4),a1=a0+Math.PI/(teeth*2);float rr=i%2==0?r:r*.78f;
   if(i==0)gear.moveTo(cx+(float)Math.cos(a0)*rr,cy+(float)Math.sin(a0)*rr);else gear.lineTo(cx+(float)Math.cos(a0)*rr,cy+(float)Math.sin(a0)*rr);
   gear.lineTo(cx+(float)Math.cos(a1)*rr,cy+(float)Math.sin(a1)*rr);
  }
  gear.close();gear.addCircle(cx,cy,r*.36f,Path.Direction.CCW);gear.setFillType(Path.FillType.EVEN_ODD);
  paint.setStyle(Paint.Style.FILL);c.drawPath(gear,paint);
 }
 /** Round glass button holding the settings gear. */
 private class GearButton extends View{
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  GearButton(){super(MainActivity.this);setFocusable(true);setClickable(true);setContentDescription("Settings");setOnFocusChangeListener((v,f)->{invalidate();animate().scaleX(f?1.12f:1).scaleY(f?1.12f:1).setDuration(150).start();setElevation(dp(f?10:0));});
   setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline o){o.setOval(0,0,v.getWidth(),v.getHeight());}});}
  protected void onDraw(Canvas c){
   float s=getWidth();paint.setStyle(Paint.Style.FILL);paint.setColor(isFocused()?0xfff5f5f7:0x33ffffff);c.drawCircle(s/2,s/2,s/2,paint);
   paint.setColor(isFocused()?0xff1c1c1e:0xf2f5f5f7);drawGear(c,s/2,s/2,s*.27f,paint);
  }
 }
 /** Soft top highlight plus a single light sweep when a card gains focus. */
 private class Sheen extends View{
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float sweep=-1;boolean on;android.animation.ValueAnimator run;
  Sheen(){super(MainActivity.this);setFocusable(false);setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);}
  void focus(boolean f){
   on=f;if(run!=null)run.cancel();
   if(f){run=android.animation.ValueAnimator.ofFloat(0,1);run.setDuration(260);run.setStartDelay(0);run.setInterpolator(new android.view.animation.DecelerateInterpolator());run.addUpdateListener(a->{sweep=(float)a.getAnimatedValue();invalidate();});run.start();}
   else sweep=-1;
   invalidate();
  }
  protected void onDetachedFromWindow(){if(run!=null)run.cancel();super.onDetachedFromWindow();}
  protected void onDraw(Canvas c){
   if(!on)return;int w=getWidth(),h=getHeight();
   paint.setShader(new LinearGradient(0,0,0,h*.6f,0x30ffffff,0x00ffffff,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h*.6f,paint);
   if(sweep>=0&&sweep<1){float x=-w*.6f+sweep*w*2.2f;paint.setShader(new LinearGradient(x,0,x+w*.45f,h,new int[]{0x00ffffff,0x3dffffff,0x00ffffff},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);}
   paint.setShader(null);
  }
 }
 /** Software blur captures do not honor clipToOutline; keep their mask identical. */
 private class RoundedCard extends FrameLayout{
  private final Path corners=new Path();
  RoundedCard(){super(MainActivity.this);setClipToOutline(true);setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View view,Outline outline){outline.setRoundRect(0,0,view.getWidth(),view.getHeight(),dp(16));}});}
  protected void onSizeChanged(int w,int h,int oldW,int oldH){super.onSizeChanged(w,h,oldW,oldH);corners.reset();corners.addRoundRect(new RectF(0,0,w,h),dp(16),dp(16),Path.Direction.CW);invalidateOutline();}
  public void draw(Canvas canvas){if(canvas.isHardwareAccelerated()){super.draw(canvas);return;}int saved=canvas.save();canvas.clipPath(corners);super.draw(canvas);canvas.restoreToCount(saved);}
 }
 private void addApp(String pkg){
  launchIntents.put(pkg,intentFor(pkg));
  FrameLayout card=new RoundedCard();card.setFocusable(true);card.setClickable(true);card.setClipToOutline(true);card.setBackground(surface(brand(pkg),16));card.setContentDescription(name(pkg));
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(176),dp(99));lp.setMargins(0,dp(4),dp(18),dp(4));rail.addView(card,lp);
  Drawable banner=null;try{banner=getPackageManager().getApplicationBanner(pkg);}catch(Exception ignored){}
  if(banner!=null){ImageView image=new ImageView(this);image.setImageDrawable(banner);image.setScaleType(ImageView.ScaleType.FIT_CENTER);card.addView(image,new FrameLayout.LayoutParams(-1,-1));}
  else{TextView title=text(name(pkg),20,Color.WHITE);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setGravity(Gravity.CENTER);title.setPadding(dp(8),0,dp(8),0);card.addView(title,new FrameLayout.LayoutParams(-1,-1));}
  Sheen sheen=new Sheen();card.addView(sheen,new FrameLayout.LayoutParams(-1,-1));
  card.setOnFocusChangeListener((v,f)->{
   v.animate().setStartDelay(0).alpha(1).scaleX(f?1.08f:1).scaleY(f?1.08f:1).translationY(f?-dp(3):0).setDuration(170).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();v.setElevation(dp(f?18:0));
   sheen.focus(f);
   if(f){focusedPackage=pkg;warmFocusedLaunch();}
  });
  card.setOnClickListener(v->launchApp(pkg,card));card.setOnLongClickListener(v->{appActions(pkg,card);return true;});
  final android.os.Handler holdHandler=new android.os.Handler();final boolean[] held={false};final Runnable hold=()->{held[0]=true;if(editPopup==null)appActions(pkg,card);};
  card.setOnKeyListener((v,key,e)->{
   if(key==KeyEvent.KEYCODE_DPAD_UP){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0)settingsControl.requestFocus();return true;}
   if(key==KeyEvent.KEYCODE_DPAD_DOWN){if(e.getAction()==KeyEvent.ACTION_DOWN&&e.getRepeatCount()==0){if(inputRail.getChildCount()>0){setDepth(true,true);inputRail.getChildAt(Math.min(sourceIndex,inputRail.getChildCount()-1)).requestFocus();}}return true;}
   if(key==KeyEvent.KEYCODE_MENU&&e.getAction()==KeyEvent.ACTION_UP){appActions(pkg,card);return true;}
   if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
    if(e.getAction()==KeyEvent.ACTION_DOWN){if(e.getRepeatCount()==0){held[0]=false;card.animate().setStartDelay(0).scaleX(1.03f).scaleY(1.03f).setDuration(55).start();holdHandler.postDelayed(hold,450);}else if(!held[0]){holdHandler.removeCallbacks(hold);hold.run();}return true;}
    if(e.getAction()==KeyEvent.ACTION_UP){holdHandler.removeCallbacks(hold);if(!held[0]&&!e.isCanceled())launchApp(pkg,card);return true;}
   }return false;
  });
 }
 private int brand(String p){if(p.contains("netflix"))return 0xff151515;if(p.contains("disney"))return 0xff132957;if(p.contains("hulu"))return 0xff123d31;if(p.contains("avod"))return 0xff153954;return 0xff39344f;}
 String name(String p){if(p.equals("com.amazon.avod"))return "Prime Video";try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p,0)).toString();}catch(Exception e){return p;}}
 Intent intentFor(String p){Intent i=getPackageManager().getLeanbackLaunchIntentForPackage(p);return i!=null?i:getPackageManager().getLaunchIntentForPackage(p);}
 void save(){prefs.edit().putString("favorites",android.text.TextUtils.join(",",favorites)).apply();}
 private void appActions(String p,View card){
  if(editPopup!=null)editPopup.dismiss();
  for(int i=0;i<rail.getChildCount();i++)rail.getChildAt(i).animate().alpha(rail.getChildAt(i)==card?1f:0.38f).setDuration(150).start();
  card.animate().translationY(-dp(13)).scaleX(1.09f).scaleY(1.09f).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
  LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER);actions.setPadding(dp(6),dp(6),dp(6),dp(6));actions.setBackground(surface(0xff24262b,28));
  String[] labels={"Move left","Remove from home","Move right"};
  for(int n=0;n<3;n++){final int action=n;IconControl control=new IconControl(n,labels[n]);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(42),dp(42));lp.setMargins(dp(3),0,dp(3),0);actions.addView(control,lp);int idx=favorites.indexOf(p);boolean available=n==1||(n==0?idx>0:idx<favorites.size()-1);control.setEnabled(available);control.setFocusable(available);control.setAlpha(available?1f:0.25f);control.setOnClickListener(v->{
   int i=favorites.indexOf(p);editPopup.dismiss();if(i<0)return;
   if(action==1){favorites.remove(p);focusedPackage=favorites.isEmpty()?null:favorites.get(Math.min(i,favorites.size()-1));save();render();}
   else{int to=i+(action==0?-1:1);if(to<0||to>=favorites.size())return;Collections.swap(favorites,i,to);save();render();View moved=rail.getChildAt(to);moved.requestFocus();moved.post(()->appActions(p,moved));}
  });}
  PopupWindow popup=new PopupWindow(actions,dp(156),dp(54),true);editPopup=popup;popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));popup.setOutsideTouchable(true);popup.setElevation(dp(12));
  popup.setOnDismissListener(()->{for(int i=0;i<rail.getChildCount();i++)rail.getChildAt(i).animate().alpha(1f).setDuration(120).start();card.animate().translationY(-dp(3)).scaleX(1.08f).scaleY(1.08f).setDuration(160).start();card.requestFocus();if(editPopup==popup)editPopup=null;});
  int[] xy=new int[2];card.getLocationOnScreen(xy);int screen=getResources().getDisplayMetrics().widthPixels;int x=Math.max(dp(12),Math.min(xy[0]+card.getWidth()/2-dp(78),screen-dp(168)));popup.showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,Math.max(dp(70),xy[1]-dp(83)));
  actions.getChildAt(favorites.size()==1?1:(favorites.indexOf(p)>0?0:2)).requestFocus();
 }
 private class HdmiIcon extends View{
  boolean selected;Paint pen=new Paint(3);HdmiIcon(){super(MainActivity.this);}
  protected void onDraw(Canvas c){float u=getWidth()/28f;c.save();c.scale(u,u);pen.setColor(selected?0xff1c1c1e:0xe6f2f2f7);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.5f);pen.setStrokeJoin(Paint.Join.ROUND);pen.setStrokeCap(Paint.Cap.ROUND);c.drawRoundRect(2,3,26,19,3,3,pen);c.drawLine(10,24,18,24,pen);c.drawLine(14,19,14,24,pen);Path port=new Path();port.moveTo(8,9);port.lineTo(20,9);port.lineTo(18,14);port.lineTo(10,14);port.close();c.drawPath(port,pen);c.restore();}
 }
 private class IconControl extends View{
  private final int kind;private final Paint paint=new Paint(3);
  IconControl(int kind,String label){super(MainActivity.this);this.kind=kind;setFocusable(true);setClickable(true);setContentDescription(label);setOnFocusChangeListener((v,f)->invalidate());}
  protected void onDraw(Canvas c){float d=getWidth();paint.setStyle(Paint.Style.FILL);paint.setColor(isFocused()?0xfff0f1f3:0xff33353b);c.drawCircle(d/2,d/2,d*.48f,paint);paint.setColor(isFocused()?0xff15161a:0xfff0f1f3);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.6f));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
   if(kind==1){c.drawLine(d*.33f,d*.34f,d*.67f,d*.34f,paint);c.drawLine(d*.43f,d*.27f,d*.57f,d*.27f,paint);c.drawRoundRect(d*.37f,d*.36f,d*.63f,d*.71f,dp(2),dp(2),paint);c.drawLine(d*.46f,d*.43f,d*.46f,d*.62f,paint);c.drawLine(d*.54f,d*.43f,d*.54f,d*.62f,paint);}
   else{float a=kind==0?d*.35f:d*.65f,b=kind==0?d*.65f:d*.35f;c.drawLine(a,d*.5f,b,d*.5f,paint);float t=kind==0?d*.48f:d*.52f;c.drawLine(a,d*.5f,t,d*.36f,paint);c.drawLine(a,d*.5f,t,d*.64f,paint);}paint.setStyle(Paint.Style.FILL);
  }
 }
 void invalidateMotionCache(){preparedLaunch=null;warmFocusedLaunch();}
 View wallpaperView(){return wallpaper;}
 void refreshWallpaper(){wallpaperPhoto=null;wallpaperPhotoTheme=-1;wallpaperPhotoCustom="";wallpaper.invalidate();}
 private void settings(){
  if(panel!=null)return;
  cancelPreparation();preparedLaunch=null;panel=new SettingsPanel(this);((FrameLayout)wallpaper.getParent()).addView(panel,new FrameLayout.LayoutParams(-1,-1));panel.show();
 }
 private void closeSettings(){
  SettingsPanel closing=panel;panel=null;
  if(closing.appsChanged()){((ViewGroup)closing.getParent()).removeView(closing);render();settingsControl.requestFocus();return;}
  closing.animate().alpha(0).setDuration(160).withEndAction(()->((ViewGroup)closing.getParent()).removeView(closing)).start();
  settingsControl.requestFocus();
 }
 private void discoverInputs(){
  hdmiInputs.clear();
  try{if(inputs==null)inputs=(android.media.tv.TvInputManager)getSystemService(TV_INPUT_SERVICE);
   if(inputs!=null)for(android.media.tv.TvInputInfo input:inputs.getTvInputList())if(input.getType()==android.media.tv.TvInputInfo.TYPE_HDMI)hdmiInputs.add(input);
   Collections.sort(hdmiInputs,(a,b)->String.valueOf(a.loadLabel(this)).compareToIgnoreCase(String.valueOf(b.loadLabel(this))));
  }catch(Exception e){android.util.Log.w("Slate","TV inputs unavailable",e);}
 }
 private String inputId(int port){return port>0&&port<=hdmiInputs.size()?hdmiInputs.get(port-1).getId():null;}
 private String inputLabel(int port){CharSequence label=hdmiInputs.get(port-1).loadLabel(this);return label==null?"HDMI "+port:label.toString();}
 private boolean inputConnected(int port){try{if(inputs==null)inputs=(android.media.tv.TvInputManager)getSystemService(TV_INPUT_SERVICE);if(inputs==null)return false;int state=inputs.getInputState(inputId(port));return state==android.media.tv.TvInputManager.INPUT_STATE_CONNECTED||state==android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY;}catch(Exception e){return false;}}
 private GradientDrawable glass(boolean focused){GradientDrawable d=surface(focused?0xfff5f5f7:0x33ffffff,16);if(!focused)d.setStroke(Math.max(1,dp(0.5f)),0x2effffff);return d;}
 private void paintInput(LinearLayout input,boolean focused){
  boolean connected=Boolean.TRUE.equals(input.getTag());HdmiIcon icon=(HdmiIcon)input.getChildAt(0);LinearLayout words=(LinearLayout)input.getChildAt(1);
  input.setAlpha(connected||focused?1f:0.55f);input.setBackground(glass(focused));
  ((TextView)words.getChildAt(0)).setTextColor(focused?0xff1c1c1e:0xfff2f2f7);
  TextView status=(TextView)words.getChildAt(1);status.setText(connected?"Connected":"No signal");status.setTextColor(focused?0x991c1c1e:0x99ebebf5);
  icon.selected=focused;icon.invalidate();
 }
 private void refreshInputs(){if(inputRail==null)return;boolean changed=false;for(int i=0;i<inputRail.getChildCount();i++){LinearLayout input=(LinearLayout)inputRail.getChildAt(i);boolean connected=inputConnected(i+1);if(!Boolean.valueOf(connected).equals(input.getTag())){changed=true;input.setTag(connected);paintInput(input,input.isFocused());}}if((changed||sourcePreview!=null&&sourcePreview.image==null)&&!sourcesActive&&sourcePreview!=null&&sourceLayer!=null&&sourceLayer.getWidth()>0){float alpha=sourceLayer.getAlpha();sourceLayer.setAlpha(1f);sourcePreview.capture(sourceLayer);sourceLayer.setAlpha(alpha);}}
 private void hdmi(int port){startActivity(new Intent(this,SourceActivity.class).putExtra("inputId",inputId(port)).putExtra("label",inputLabel(port)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
 void open(Intent i){try{if(i!=null)startActivity(i);}catch(Exception e){android.util.Log.e("Slate","Launch failed",e);Toast.makeText(this,"This shortcut is unavailable on your TV",Toast.LENGTH_SHORT).show();}}
 @Override public void onBackPressed(){if(panel!=null){if(!panel.back())closeSettings();return;}if(sourcesActive){setDepth(false,true);if(!favorites.isEmpty())rail.getChildAt(Math.max(0,favorites.indexOf(focusedPackage))).requestFocus();}}
 static volatile long resumedAt;
 private void maybeReturn(){
  if(!returnAnimationPending||returnSurface!=null||!activityResumed||root==null||root.getWidth()==0)return;
  returnAnimationPending=false;introPlayed=true;overridePendingTransition(0,0);
  if(returnTile==null&&returnPackage!=null){int idx=favorites.indexOf(returnPackage);if(idx>=0){View card=rail.getChildAt(idx);int[] xy=new int[2],origin=new int[2];card.getLocationOnScreen(xy);((View)wallpaper.getParent()).getLocationOnScreen(origin);returnTile=Bitmap.createBitmap(Math.max(1,card.getWidth()),Math.max(1,card.getHeight()),Bitmap.Config.ARGB_8888);card.draw(new Canvas(returnTile));returnTarget=new RectF(xy[0]-origin[0],xy[1]-origin[1],xy[0]-origin[0]+card.getWidth(),xy[1]-origin[1]+card.getHeight());}}
  playReturn();
 }
 @Override protected void onResume(){
  super.onResume();activityResumed=true;interactive=hasWindowFocus();if(returnAnimationPending)overridePendingTransition(0,0);clearLaunchSurface();resumedAt=android.os.SystemClock.elapsedRealtime();LauncherWatchService.sawOtherApp=false;
  // Present the return surface before any TV-service IPC or housekeeping.
  maybeReturn();if(root!=null)root.postInvalidateOnAnimation();
  motionHandler.postDelayed(()->{
   if(!activityResumed||destroying)return;
   if(inputs==null)inputs=(android.media.tv.TvInputManager)getSystemService(TV_INPUT_SERVICE);
   if(inputs!=null&&!inputCallbackRegistered){inputs.registerCallback(inputCallback,motionHandler);inputCallbackRegistered=true;}
   refreshInputs();warmFocusedLaunch();
   snapshotWorker.execute(()->LauncherWatchService.primeHomeTask(MainActivity.this));
  },300);
  android.util.Log.i("SlateHome","Slate resumed");
 }
 @Override public void onWindowFocusChanged(boolean focus){super.onWindowFocusChanged(focus);interactive=focus&&activityResumed;if(focus){maybeReturn();warmFocusedLaunch();}else cancelPreparation();}
 @Override protected void onPause(){activityResumed=false;interactive=false;pausedAt=android.os.SystemClock.elapsedRealtime();cancelPreparation();if(returnSurface!=null)returnAnimationPending=true;clearReturnSurface();if(root!=null){root.animate().cancel();root.setAlpha(1);}settleCards();if(inputs!=null&&inputCallbackRegistered){inputs.unregisterCallback(inputCallback);inputCallbackRegistered=false;}super.onPause();}
 @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(returnAnimationPending||returnSurface!=null)out.putString("return_pkg",returnPackage);}
 @Override protected void onStop(){clearLaunchSurface();super.onStop();}
 @Override protected void onDestroy(){destroying=true;cancelPreparation();snapshotWorker.shutdownNow();motionHandler.removeCallbacksAndMessages(null);synchronized(blurLock){if(blurFilter!=null)blurFilter.destroy();if(blurRuntime!=null)blurRuntime.destroy();}super.onDestroy();}
 private class Wallpaper extends View {
  /** Photo wallpapers, from theme 3 on; only the selected one is kept in memory. */
  final String[] photos={"batman.webp","batman_moon.jpg","batman_blast.webp","batman_snow.jpg","batman_dark_knight.jpg"};
  Paint paint=new Paint(3);
  Wallpaper(){super(MainActivity.this);}
  Bitmap photoFor(int theme){
   String custom=prefs.getString(WallpaperLibrary.SELECTED,"");
   if(!custom.isEmpty()){
    if(custom.equals(wallpaperPhotoCustom))return wallpaperPhoto;
    wallpaperPhoto=WallpaperLibrary.selected(MainActivity.this,custom);wallpaperPhotoCustom=custom;wallpaperPhotoTheme=-1;return wallpaperPhoto;
   }
   if(!wallpaperPhotoCustom.isEmpty()){wallpaperPhotoCustom="";wallpaperPhoto=null;wallpaperPhotoTheme=-1;}
   if(prefs.contains("wallpaper_theme")||theme<3||theme-3>=photos.length)return null;
   if(theme==wallpaperPhotoTheme)return wallpaperPhoto;
   wallpaperPhoto=null;wallpaperPhotoTheme=theme;
   try{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(java.io.InputStream input=getAssets().open(photos[theme-3])){BitmapFactory.decodeStream(input,null,options);}
    int sample=1;while(options.outWidth/(sample*2)>=1920)sample*=2;options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
    try(java.io.InputStream input=getAssets().open(photos[theme-3])){wallpaperPhoto=BitmapFactory.decodeStream(input,null,options);}}
   catch(Exception e){android.util.Log.e("Slate","Wallpaper unavailable",e);}
   return wallpaperPhoto;
  }
  protected void onDraw(Canvas c){int w=getWidth(),h=getHeight(),theme=prefs.getInt("wallpaper",0);boolean day="day".equals(prefs.getString("wallpaper_theme","night"));int[] colors=day?new int[]{0xffe4e9ec,0xffc9d3d9,0xffa8b8c2}:new int[]{0xff343c43,0xff20272d,0xff10151a};paint.setShader(new LinearGradient(0,0,w,h,colors,null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,paint);paint.setShader(null);
   Path p=new Path();p.moveTo(w*.2f,0);p.cubicTo(w*.7f,h*.14f,w*.19f,h*.38f,w*.8f,h*.64f);p.cubicTo(w*1.05f,h*.76f,w*1.1f,h*.7f,w*1.1f,0);p.close();paint.setColor(day?0x1f5f8399:0x163f8eab);c.drawPath(p,paint);
   paint.setColor(Color.WHITE);
   Bitmap photo=photoFor(theme);
   if(photo!=null){float scale=Math.max(w/(float)photo.getWidth(),h/(float)photo.getHeight());float bw=photo.getWidth()*scale,bh=photo.getHeight()*scale;c.drawBitmap(photo,null,new RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),paint);}
   paint.setShader(new LinearGradient(0,0,0,h*.28f,new int[]{day?0x4410141e:0x9910141e,0x0010141e},null,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h*.28f,paint);paint.setShader(null);
   paint.setShader(new LinearGradient(0,h*.22f,0,h,day?new int[]{0x0010141e,0x1810141e,0x66080a10}:new int[]{0x0010141e,0xa610141e,0xf5080a10},new float[]{0,0.5f,1},Shader.TileMode.CLAMP));c.drawRect(0,h*.22f,w,h,paint);paint.setShader(null);
  }
 }
}
