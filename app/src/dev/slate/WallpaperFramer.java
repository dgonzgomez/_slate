package dev.slate;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

/** Drag to position, Volume or Left/Right to zoom, then Set. */
final class WallpaperFramer extends FrameLayout {
 interface Set{void image(Bitmap framed);}
 private final Context context;
 private Bitmap source;
 private final WallpaperCrop crop=new WallpaperCrop();
 private final Picture picture;
 private final TextView zoomLabel,set;
 private final Set commit;
 private boolean saving;
 WallpaperFramer(Context context,Bitmap source,Runnable back,Set commit){
  super(context);this.context=context;this.source=source;this.commit=commit;setBackgroundColor(Color.BLACK);setClickable(true);
  picture=new Picture();addView(picture,new LayoutParams(-1,-1));
  View top=new View(context);top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xb3000000,0x00000000}));LayoutParams topLp=new LayoutParams(-1,dp(140),Gravity.TOP);addView(top,topLp);
  View bottom=new View(context);bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0x00000000,0xd9000000}));addView(bottom,new LayoutParams(-1,dp(140),Gravity.BOTTOM));
  LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(48),dp(28),dp(48),0);addView(header,new LayoutParams(-1,dp(78),Gravity.TOP));
  Back previous=new Back();previous.setOnClickListener(v->{if(!saving)back.run();});header.addView(previous,new LinearLayout.LayoutParams(dp(44),dp(44)));
  TextView title=text("Adjust Wallpaper",24,0xfff5f5f7);title.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(-2,-2);titleLp.leftMargin=dp(18);header.addView(title,titleLp);
  LinearLayout controls=new LinearLayout(context);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(48),0,dp(48),dp(28));addView(controls,new LayoutParams(-1,dp(92),Gravity.BOTTOM));
  LinearLayout help=new LinearLayout(context);help.setOrientation(1);TextView hint=text("Drag to position · Volume to zoom",15,0xfff5f5f7);help.addView(hint);TextView remote=text("Remote: ↑↓ move · ←→ zoom · Select to set",12,0xa6ebebf5);LinearLayout.LayoutParams remoteLp=new LinearLayout.LayoutParams(-2,-2);remoteLp.topMargin=dp(6);help.addView(remote,remoteLp);controls.addView(help,new LinearLayout.LayoutParams(0,-2,1));
  TextView minus=button("−",44);minus.setContentDescription("Zoom out");minus.setOnClickListener(v->zoom(-.1));controls.addView(minus,new LinearLayout.LayoutParams(dp(44),dp(44)));
  zoomLabel=text("100%",14,0xfff5f5f7);zoomLabel.setGravity(Gravity.CENTER);controls.addView(zoomLabel,new LinearLayout.LayoutParams(dp(64),dp(44)));
  TextView plus=button("+",44);plus.setContentDescription("Zoom in");plus.setOnClickListener(v->zoom(.1));controls.addView(plus,new LinearLayout.LayoutParams(dp(44),dp(44)));
  set=button("Set",44);set.setBackground(fill(0xfff5f5f7,22));set.setTextColor(0xff1c1c1e);set.setOnFocusChangeListener((v,f)->set.setAlpha(f?1:.92f));set.setOnClickListener(v->save());LinearLayout.LayoutParams setLp=new LinearLayout.LayoutParams(dp(90),dp(44));setLp.leftMargin=dp(20);controls.addView(set,setLp);
  post(()->picture.requestFocus());
 }
 private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private TextView text(String value,int size,int color){TextView text=new TextView(context);text.setText(value);text.setTextSize(size);text.setTextColor(color);return text;}
 private GradientDrawable fill(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
 private TextView button(String label,int height){TextView button=text(label,"Set".equals(label)?15:25,0xfff5f5f7);button.setGravity(Gravity.CENTER);button.setFocusable(true);button.setClickable(true);button.setBackground(fill(0x33ffffff,height/2));button.setOnFocusChangeListener((v,f)->{button.setBackground(fill(f?0xfff5f5f7:0x33ffffff,height/2));button.setTextColor(f?0xff1c1c1e:0xfff5f5f7);});return button;}
 private void zoom(double amount){if(saving)return;crop.magnify(amount);zoomLabel.setText(Math.round(crop.zoom*100)+"%");picture.invalidate();}
 private void save(){if(saving)return;saving=true;set.setText("Saving…");set.setEnabled(false);Bitmap framed=Bitmap.createBitmap(1920,1080,Bitmap.Config.RGB_565);draw(new Canvas(framed),1920,1080);commit.image(framed);}
 void failed(){saving=false;set.setText("Set");set.setEnabled(true);}
 private void draw(Canvas canvas,int width,int height){if(source==null)return;double[] b=crop.bounds(source.getWidth(),source.getHeight(),width,height);canvas.drawBitmap(source,null,new RectF((float)b[0],(float)b[1],(float)(b[0]+b[2]),(float)(b[1]+b[3])),new Paint(Paint.FILTER_BITMAP_FLAG));}
 @Override public boolean dispatchKeyEvent(KeyEvent event){
  int key=event.getKeyCode();
  if(key==KeyEvent.KEYCODE_VOLUME_UP||key==KeyEvent.KEYCODE_VOLUME_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN)zoom(key==KeyEvent.KEYCODE_VOLUME_UP?.05:-.05);return true;}
  if(event.getAction()==KeyEvent.ACTION_DOWN&&picture.hasFocus()){
   if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT){zoom(key==KeyEvent.KEYCODE_DPAD_RIGHT?.05:-.05);return true;}
   if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(!saving){crop.stepVertical(key==KeyEvent.KEYCODE_DPAD_UP,dp(24),source.getWidth(),source.getHeight(),picture.getWidth(),picture.getHeight());picture.invalidate();}return true;}
   if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){save();return true;}
  }
  return super.dispatchKeyEvent(event);
 }
 private final class Picture extends View{
  float lastX,lastY;
  Picture(){super(context);setFocusable(true);setFocusableInTouchMode(true);}
  protected void onDraw(Canvas canvas){WallpaperFramer.this.draw(canvas,getWidth(),getHeight());}
  public boolean onTouchEvent(MotionEvent event){if(saving)return true;switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();return true;case MotionEvent.ACTION_MOVE:crop.move(event.getX()-lastX,event.getY()-lastY,source.getWidth(),source.getHeight(),getWidth(),getHeight());lastX=event.getX();lastY=event.getY();invalidate();return true;case MotionEvent.ACTION_UP:performClick();return true;}return true;}
  public boolean performClick(){super.performClick();return true;}
 }
 private final class Back extends View{
  final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
  Back(){super(context);setFocusable(true);setClickable(true);setContentDescription("Back to wallpaper results");setBackground(fill(0x55000000,22));setOnFocusChangeListener((v,f)->{setBackground(fill(f?0xfff5f5f7:0x55000000,22));invalidate();});}
  protected void onDraw(Canvas canvas){float x=getWidth()/2f,y=getHeight()/2f;pen.setColor(isFocused()?0xff1c1c1e:0xfff5f5f7);pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(dp(2));pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);Path p=new Path();p.moveTo(x+dp(7),y);p.lineTo(x-dp(7),y);p.lineTo(x,y-dp(7));p.moveTo(x-dp(7),y);p.lineTo(x,y+dp(7));canvas.drawPath(p,pen);}
 }
 void release(){source=null;}
}
