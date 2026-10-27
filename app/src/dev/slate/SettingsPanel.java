package dev.slate;

import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Full-screen settings over a blurred copy of the wallpaper: page title on the left, a focusable list on the right. */
class SettingsPanel extends FrameLayout {
 static final String[] WALLPAPERS={"Day","Night"};
 private static final String[] LEGACY={"Midnight","Dusk","Graphite","Batman","Moonlit Ride","Explosion","Snowfall","Dark Knight"};
 private static final String PICTURE=android.provider.Settings.ACTION_DISPLAY_SETTINGS;
 private final MainActivity home;
 private final ImageView backdrop;
 private final TextView title;
 private final Glyph glyph;
 private final LinearLayout list;
 private final ScrollView scroll;
 private boolean onMain=true, appsChanged;
 private final UpdateManager updater;
 private int pageGeneration;
 private WallpaperPicker picker;
 private final WallpaperActions wallpaperActions=new WallpaperActions();


 SettingsPanel(MainActivity home){
  super(home);this.home=home;updater=new UpdateManager(home);
  setFocusable(false);setClickable(true);setAlpha(0);
  backdrop=new ImageView(home);backdrop.setScaleType(ImageView.ScaleType.FIT_XY);addView(backdrop,new LayoutParams(-1,-1));
  View shade=new View(home);shade.setBackgroundColor(0x8c05070a);addView(shade,new LayoutParams(-1,-1));
  LinearLayout columns=new LinearLayout(home);columns.setPadding(dp(56),0,dp(64),0);addView(columns,new LayoutParams(-1,-1));
  LinearLayout left=new LinearLayout(home);left.setOrientation(LinearLayout.VERTICAL);left.setGravity(Gravity.CENTER);columns.addView(left,new LinearLayout.LayoutParams(0,-1,0.42f));
  glyph=new Glyph();left.addView(glyph,new LinearLayout.LayoutParams(dp(112),dp(112)));
  title=new TextView(home);title.setTextSize(28);title.setTextColor(0xfff5f5f7);title.setTypeface(Typeface.create("sans-serif-medium",0));title.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams tlp=new LinearLayout.LayoutParams(-2,-2);tlp.topMargin=dp(22);left.addView(title,tlp);
  scroll=new ScrollView(home);scroll.setVerticalScrollBarEnabled(false);scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);scroll.setClipToPadding(false);scroll.setPadding(0,dp(96),0,dp(60));
  list=new LinearLayout(home);list.setOrientation(LinearLayout.VERTICAL);list.setClipChildren(false);list.setClipToPadding(false);list.setPadding(dp(14),dp(4),dp(14),0);scroll.addView(list);
  columns.addView(scroll,new LinearLayout.LayoutParams(0,-1,0.58f));
  refreshBackdrop();
  main();
 }

 private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

 void show(){post(()->{if(findFocus()==null&&list.getChildCount()>0)list.getChildAt(0).requestFocus();});animate().alpha(1).setDuration(180).start();list.setTranslationX(dp(28));list.animate().translationX(0).setDuration(240).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();}

 /** Back steps out of a sub-page first; returns false once the panel should close. */
 boolean back(){if(picker!=null){if(!picker.back())closePicker();return true;}if(!onMain){main();return true;}return false;}

 boolean appsChanged(){return appsChanged;}

 private void refreshBackdrop(){
  View source=home.wallpaperView();
  int w=Math.max(1,source.getWidth()/8),h=Math.max(1,source.getHeight()/8);
  if(source.getWidth()==0)return;
  Bitmap small=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(small);canvas.scale(w/(float)source.getWidth(),h/(float)source.getHeight());source.draw(canvas);
  android.renderscript.RenderScript rs=null;android.renderscript.Allocation in=null,out=null;android.renderscript.ScriptIntrinsicBlur blur=null;
  try{rs=android.renderscript.RenderScript.create(home);in=android.renderscript.Allocation.createFromBitmap(rs,small);out=android.renderscript.Allocation.createTyped(rs,in.getType());blur=android.renderscript.ScriptIntrinsicBlur.create(rs,android.renderscript.Element.U8_4(rs));blur.setRadius(10f);blur.setInput(in);blur.forEach(out);out.copyTo(small);}
  catch(Exception e){android.util.Log.w("Slate","Settings blur fallback",e);}
  finally{if(blur!=null)blur.destroy();if(in!=null)in.destroy();if(out!=null)out.destroy();if(rs!=null)rs.destroy();}
  backdrop.setImageBitmap(small);
 }

 private void page(String name,int kind){pageGeneration++;title.setText(name);glyph.kind=kind;glyph.invalidate();list.removeAllViews();scroll.scrollTo(0,0);}

 private void main(){
  boolean returning=!onMain;String from=returning?title.getText().toString():null;
  onMain=true;page("Settings",0);
  int wallpaper=legacyWallpaper();
  String wallpaperName=wallpaper>=0?LEGACY[wallpaper]:("day".equals(home.prefs.getString("wallpaper_theme","night"))?"Day":"Night");
  for(WallpaperLibrary.Saved saved:WallpaperLibrary.list(home))if(saved.id.equals(home.prefs.getString(WallpaperLibrary.SELECTED,"")))wallpaperName=saved.name;
  View wall=row("Wallpaper",wallpaperName,true,false,this::wallpapers);
  View apps=row("Home Screen Apps",home.favorites.size()==1?"1 app":home.favorites.size()+" apps",true,false,this::apps);
  row("Updates",BuildConfig.VERSION_NAME,true,false,this::updates);
  row("Home Button Setup",home.getPackageManager().checkPermission("android.permission.READ_LOGS",home.getPackageName())==PackageManager.PERMISSION_GRANTED?"Enabled":"Setup needed",true,false,this::homeSetup);
  Intent picture=pictureIntent();
  if(picture!=null)row("Picture & Sound","",true,false,()->home.open(picture));
  row("TV Settings","",true,false,()->home.open(new Intent().setClassName("com.amazon.tv.launcher","com.amazon.tv.launcher.ui.MainSettingsActivity")));
  row("Amazon Home","",true,false,()->{HomeService.bypassUntil=android.os.SystemClock.elapsedRealtime()+30000;home.open(new Intent().setClassName("com.amazon.tv.launcher","com.amazon.tv.launcher.ui.HomeActivity_vNext"));});
  (("Home Screen Apps".equals(from))?apps:wall).requestFocus();
 }

 private void wallpapers(){
  onMain=false;page("Wallpaper",1);
  View add=row("Add Wallpaper","",false,false,this::addWallpaper);((Mark)((ViewGroup)add).getChildAt(2)).kind=3;
  int legacy=legacyWallpaper();String theme=home.prefs.getString("wallpaper_theme","night");String custom=home.prefs.getString(WallpaperLibrary.SELECTED,"");View selected=null;
  List<WallpaperLibrary.Saved> saved=WallpaperLibrary.list(home);
  for(WallpaperLibrary.Saved wallpaper:saved){
   boolean active=wallpaper.id.equals(custom);View option=row(wallpaper.name,"",false,active,()->selectWallpaper(wallpaper.id));
   option.setOnLongClickListener(v->{showDelete(option,wallpaper.id);return true;});
   option.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP)showDelete(option,wallpaper.id);return true;}return false;});
   if(active)selected=option;
  }
  if(legacy>=0){boolean active=custom.isEmpty();View r=row(LEGACY[legacy],"",false,active,()->{home.prefs.edit().remove(WallpaperLibrary.SELECTED).remove("wallpaper_theme").apply();refreshWallpaper();});if(active)selected=r;}
  for(int i=0;i<WALLPAPERS.length;i++){final String preset=i==0?"day":"night";boolean active=custom.isEmpty()&&legacy<0&&preset.equals(theme);View r=row(WALLPAPERS[i],"",false,active,()->{home.prefs.edit().remove(WallpaperLibrary.SELECTED).putString("wallpaper_theme",preset).apply();refreshWallpaper();});if(active)selected=r;}
  if(selected!=null)selected.requestFocus();else add.requestFocus();
 }
 private int legacyWallpaper(){int old=home.prefs.getInt("wallpaper",0);return !home.prefs.contains("wallpaper_theme")&&old>=3&&old<LEGACY.length?old:-1;}
 private void refreshWallpaper(){home.refreshWallpaper();post(()->{refreshBackdrop();wallpapers();});}
 private void selectWallpaper(String id){home.prefs.edit().remove("wallpaper_theme").putString(WallpaperLibrary.SELECTED,id).apply();refreshWallpaper();}
 private void showDelete(View option,String id){int index=list.indexOfChild(option);wallpaperActions.show(home,option,list,()->deleteWallpaper(id,index));}
 private void deleteWallpaper(String id,int index){
  if(!WallpaperLibrary.remove(home,id)){Toast.makeText(home,"Couldn’t delete this wallpaper",Toast.LENGTH_SHORT).show();if(index>=0&&index<list.getChildCount())list.getChildAt(index).requestFocus();return;}
  home.refreshWallpaper();refreshBackdrop();wallpapers();
  list.post(()->{if(list.getChildCount()>0)list.getChildAt(Math.max(0,Math.min(index,list.getChildCount()-1))).requestFocus();});
  Toast.makeText(home,"Wallpaper deleted",Toast.LENGTH_SHORT).show();
 }
 private void addWallpaper(){
  if(picker!=null)return;
  picker=new WallpaperPicker(home,this::closePicker,saved->{Toast.makeText(home,"Wallpaper saved",Toast.LENGTH_SHORT).show();},()->{home.refreshWallpaper();refreshBackdrop();});
  addView(picker,new LayoutParams(-1,-1));
 }
 private void closePicker(){if(picker==null)return;WallpaperPicker closing=picker;picker=null;closing.close();removeView(closing);wallpapers();if(list.getChildCount()>0)list.getChildAt(0).requestFocus();}

 private void apps(){
  onMain=false;page("Home Screen Apps",2);
  Intent query=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
  PackageManager pm=home.getPackageManager();
  ArrayList<String> packages=new ArrayList<>();
  for(ResolveInfo r:pm.queryIntentActivities(query,0)){String p=r.activityInfo.packageName;if(!p.equals(home.getPackageName())&&!packages.contains(p))packages.add(p);}
  Collections.sort(packages,(a,b)->home.name(a).compareToIgnoreCase(home.name(b)));
  View first=null;
  for(String p:packages){
   final View[] self=new View[1];
   self[0]=row(home.name(p),"",false,home.favorites.contains(p),()->{
    if(home.favorites.contains(p))home.favorites.remove(p);else home.favorites.add(p);
    home.save();appsChanged=true;setCheck(self[0],home.favorites.contains(p));
   });
   if(first==null)first=self[0];
  }
  if(first!=null)first.requestFocus();
 }

 private void note(String message){
  TextView text=new TextView(home);text.setText(message);text.setTextSize(15);text.setTextColor(0xccf2f2f7);text.setLineSpacing(dp(5),1f);text.setPadding(dp(22),dp(18),dp(22),dp(12));list.addView(text,new LinearLayout.LayoutParams(-1,-2));
 }

 private void homeSetup(){
  onMain=false;page("Home Button Setup",0);
  note("Slate opens normally on Fire OS. Replacing Amazon Home also needs one-time ADB permissions from a computer. Use the repository’s install script with your TV’s address. Some Fire OS updates restrict custom Home handlers.");
  row("Accessibility settings","",true,false,()->home.open(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))).requestFocus();
  note("Accessibility is an optional alternative when your TV allows third-party services. Amazon Home stays installed and enabled.");
 }

 private void updates(){
  onMain=false;page("Updates",0);final int generation=pageGeneration;
  View action=row(updater.downloaded?"Install update":updater.available!=null?"Download update":"Check for updates","",false,false,()->{});
  note("Slate "+BuildConfig.VERSION_NAME);
  TextView status=new TextView(home);status.setTextSize(15);status.setTextColor(0xccf2f2f7);status.setLineSpacing(dp(5),1f);status.setPadding(dp(22),dp(12),dp(22),dp(18));list.addView(status,new LinearLayout.LayoutParams(-1,-2));
  UpdateManager.Listener changed=(message,busy)->{
   if(generation!=pageGeneration)return;
   // Keep the remote focused on the next step while work finishes.
   status.setText(message);action.setAlpha(busy?.6f:1f);
   ((TextView)((ViewGroup)action).getChildAt(0)).setText(updater.downloaded?"Install update":updater.available!=null?"Download update":"Check for updates");
  };
  updater.observe(changed);
  action.setOnClickListener(v->{
   if(updater.isBusy())return;
   if(updater.downloaded){try{status.setText(updater.install());}catch(Exception e){status.setText(e.getMessage()==null?"The installer couldn’t open. Try again.":e.getMessage());}}
   else if(updater.available!=null)updater.download(changed);
   else updater.check(changed);
  });
  row("Check again","",false,false,()->updater.check(changed));
  action.requestFocus();
 }

 void updatePermissionReturned(){updater.permissionReturned();}

 @Override protected void onDetachedFromWindow(){wallpaperActions.dismiss();updater.close();super.onDetachedFromWindow();}

 @Override public View focusSearch(View focused,int direction){
  View next=super.focusSearch(focused,direction);
  for(View v=next;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null)if(v==this)return next;
  return focused;
 }

 private Intent pictureIntent(){
  PackageManager pm=home.getPackageManager();
  Intent i=new Intent(PICTURE);
  return pm.resolveActivity(i,0)!=null?i:null;
 }

 private View row(String label,String value,boolean chevron,boolean checked,Runnable action){
  LinearLayout r=new LinearLayout(home);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(dp(22),0,dp(18),0);r.setFocusable(true);r.setClickable(true);r.setContentDescription(label);
  r.setBackground(fill(0x00000000));
  TextView name=new TextView(home);name.setText(label);name.setTextSize(17);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);r.addView(name,new LinearLayout.LayoutParams(0,-2,1));
  TextView detail=new TextView(home);detail.setText(value);detail.setTextSize(15);detail.setSingleLine(true);LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-2,-2);dlp.leftMargin=dp(14);r.addView(detail,dlp);
  Mark mark=new Mark(chevron?1:checked?2:0);LinearLayout.LayoutParams mlp=new LinearLayout.LayoutParams(dp(18),dp(18));mlp.leftMargin=dp(12);r.addView(mark,mlp);
  LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(54));lp.bottomMargin=dp(6);list.addView(r,lp);
  r.setOnClickListener(v->action.run());
  r.setOnFocusChangeListener((v,f)->{paint(r,f);v.animate().scaleX(f?1.035f:1).scaleY(f?1.035f:1).setDuration(140).start();v.setElevation(f?dp(10):0);});
  paint(r,false);
  return r;
 }

 private void setCheck(View row,boolean on){Mark m=(Mark)((ViewGroup)row).getChildAt(2);m.kind=on?2:0;m.invalidate();}

 private void paint(LinearLayout r,boolean focused){
  r.setBackground(fill(focused?0xfff5f5f7:0x1effffff));
  ((TextView)r.getChildAt(0)).setTextColor(focused?0xff1c1c1e:0xfff2f2f7);
  ((TextView)r.getChildAt(1)).setTextColor(focused?0x991c1c1e:0x99ebebf5);
  Mark m=(Mark)r.getChildAt(2);m.dark=focused;m.invalidate();
 }

 private GradientDrawable fill(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(12));return d;}

 /** Chevron (1) or checkmark (2) at the end of a row. */
 private class Mark extends View{
  int kind;boolean dark;final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
  Mark(int kind){super(home);this.kind=kind;}
  protected void onDraw(Canvas c){
   if(kind==0)return;float s=getWidth();pen.setStyle(Paint.Style.STROKE);pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);pen.setStrokeWidth(dp(2));
   pen.setColor(kind==2?(dark?0xff1c1c1e:0xfff5f5f7):(dark?0x991c1c1e:0x80ebebf5));
   Path p=new Path();
   if(kind==3){p.moveTo(s*.2f,s*.5f);p.lineTo(s*.8f,s*.5f);p.moveTo(s*.5f,s*.2f);p.lineTo(s*.5f,s*.8f);}
   else if(kind==1){p.moveTo(s*.38f,s*.2f);p.lineTo(s*.66f,s*.5f);p.lineTo(s*.38f,s*.8f);}
   else{p.moveTo(s*.16f,s*.52f);p.lineTo(s*.4f,s*.76f);p.lineTo(s*.86f,s*.26f);}
   c.drawPath(p,pen);
  }
 }

 /** Large page icon: gear (0), wallpaper (1) or app grid (2). */
 private class Glyph extends View{
  int kind;final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
  Glyph(){super(home);}
  protected void onDraw(Canvas c){
   float s=getWidth();pen.setColor(0xe6f5f5f7);
   if(kind==0){MainActivity.drawGear(c,s/2,s/2,s*.46f,pen);return;}
   pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(s*.045f);pen.setStrokeJoin(Paint.Join.ROUND);pen.setStrokeCap(Paint.Cap.ROUND);
   if(kind==1){
    c.drawRoundRect(s*.08f,s*.18f,s*.92f,s*.82f,s*.1f,s*.1f,pen);
    Path m=new Path();m.moveTo(s*.16f,s*.72f);m.lineTo(s*.4f,s*.44f);m.lineTo(s*.56f,s*.62f);m.lineTo(s*.66f,s*.52f);m.lineTo(s*.84f,s*.72f);c.drawPath(m,pen);
    pen.setStyle(Paint.Style.FILL);c.drawCircle(s*.7f,s*.34f,s*.06f,pen);
   }else{
    pen.setStyle(Paint.Style.FILL);float cell=s*.36f,gap=s*.1f,o=(s-cell*2-gap)/2;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++)c.drawRoundRect(o+x*(cell+gap),o+y*(cell+gap),o+x*(cell+gap)+cell,o+y*(cell+gap)+cell,s*.07f,s*.07f,pen);
   }
  }
 }
}
