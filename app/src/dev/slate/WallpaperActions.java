package dev.slate;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** The app rail's lifted-card toolbar, reduced to its single delete control. */
final class WallpaperActions {
 private PopupWindow popup;

 boolean isShowing(){return popup!=null;}
 void dismiss(){if(popup!=null)popup.dismiss();}

 void show(MainActivity home,View card,ViewGroup peers,Runnable remove){
  dismiss();
  float scaleX=card.getScaleX(),scaleY=card.getScaleY(),y=card.getTranslationY(),elevation=card.getElevation();
  for(int i=0;i<peers.getChildCount();i++){View peer=peers.getChildAt(i);peer.animate().alpha(peer==card?1f:.38f).setDuration(150).start();}
  card.animate().translationY(y-home.dp(13)).scaleX(Math.max(scaleX,1.09f)).scaleY(Math.max(scaleY,1.09f)).setDuration(260).setInterpolator(new android.view.animation.OvershootInterpolator(2f)).start();
  LinearLayout actions=new LinearLayout(home);actions.setGravity(Gravity.CENTER);actions.setPadding(home.dp(6),home.dp(6),home.dp(6),home.dp(6));actions.setBackground(home.surface(0xff24262b,28));
  MainActivity.IconControl control=home.new IconControl(1,"Delete wallpaper");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(home.dp(42),home.dp(42));lp.setMargins(home.dp(3),0,home.dp(3),0);actions.addView(control,lp);
  PopupWindow window=new PopupWindow(actions,home.dp(60),home.dp(54),true);popup=window;window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setOutsideTouchable(true);window.setElevation(home.dp(12));
  final boolean[] deleted={false};
  control.setOnClickListener(v->{deleted[0]=true;window.dismiss();remove.run();});
  control.setOnKeyListener((v,key,event)->{
   if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP)window.dismiss();return true;}
   return false;
  });
  window.setOnDismissListener(()->{
   for(int i=0;i<peers.getChildCount();i++)peers.getChildAt(i).animate().alpha(1f).setDuration(120).start();
   card.animate().translationY(y).scaleX(scaleX).scaleY(scaleY).setDuration(160).start();card.setElevation(elevation);
   if(!deleted[0]&&!card.requestFocus())peers.requestFocus();
   if(popup==window)popup=null;
  });
  int[] xy=new int[2];card.getLocationOnScreen(xy);int screen=home.getResources().getDisplayMetrics().widthPixels;int x=Math.max(home.dp(12),Math.min(xy[0]+card.getWidth()/2-home.dp(30),screen-home.dp(72)));window.showAtLocation(card.getRootView(),Gravity.TOP|Gravity.LEFT,x,Math.max(home.dp(70),xy[1]-home.dp(83)));
  control.requestFocus();
 }
}
