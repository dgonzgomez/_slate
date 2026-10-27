package dev.slate;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;

/** Lifted wallpaper controls, with optional library reordering. */
final class WallpaperActions {
 private PopupWindow popup;

 boolean isShowing(){return popup!=null;}
 void dismiss(){if(popup!=null)popup.dismiss();}

 void show(MainActivity home,View card,ViewGroup peers,Runnable remove){
  show(home,card,peers,remove,null,null);
 }

 void show(MainActivity home,View card,ViewGroup peers,Runnable remove,Runnable moveUp,Runnable moveDown){
  dismiss();
  float scaleX=card.getScaleX(),scaleY=card.getScaleY(),y=card.getTranslationY(),elevation=card.getElevation();
  for(int i=0;i<peers.getChildCount();i++){View peer=peers.getChildAt(i);peer.animate().alpha(peer==card?1f:.38f).setDuration(150).start();}
  card.animate().translationY(y-home.dp(13)).scaleX(Math.max(scaleX,1.09f)).scaleY(Math.max(scaleY,1.09f)).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
  LinearLayout actions=new LinearLayout(home);actions.setGravity(Gravity.CENTER);actions.setPadding(home.dp(6),home.dp(6),home.dp(6),home.dp(6));actions.setBackground(home.surface(0xff24262b,28));
  boolean reorder=moveUp!=null||moveDown!=null;
  MainActivity.IconControl up=home.new IconControl(0,"Move wallpaper up");up.setRotation(90);
  MainActivity.IconControl down=home.new IconControl(2,"Move wallpaper down");down.setRotation(90);
  LinearLayout.LayoutParams arrowLp=new LinearLayout.LayoutParams(home.dp(42),home.dp(42));arrowLp.setMargins(home.dp(3),0,home.dp(3),0);
  if(reorder)actions.addView(up,arrowLp);
  MainActivity.IconControl control=home.new IconControl(1,"Delete wallpaper");LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(home.dp(42),home.dp(42));lp.setMargins(home.dp(3),0,home.dp(3),0);actions.addView(control,lp);
  if(reorder)actions.addView(down,arrowLp);
  int toolbarWidth=home.dp(reorder?156:60);
  PopupWindow window=new PopupWindow(actions,toolbarWidth,home.dp(54),true);popup=window;window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));window.setOutsideTouchable(true);window.setElevation(home.dp(12));
  final boolean[] deleted={false};
  control.setOnClickListener(v->{deleted[0]=true;window.dismiss();remove.run();});
  if(reorder){
   up.setEnabled(moveUp!=null);up.setFocusable(moveUp!=null);up.setAlpha(moveUp==null?.3f:1f);
   down.setEnabled(moveDown!=null);down.setFocusable(moveDown!=null);down.setAlpha(moveDown==null?.3f:1f);
   up.setOnClickListener(v->{deleted[0]=true;window.dismiss();if(moveUp!=null)moveUp.run();});
   down.setOnClickListener(v->{deleted[0]=true;window.dismiss();if(moveDown!=null)moveDown.run();});
  }
  View.OnKeyListener keys=(v,key,event)->{
   if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_MENU){if(event.getAction()==KeyEvent.ACTION_UP)window.dismiss();return true;}
   return false;
  };
  control.setOnKeyListener(keys);up.setOnKeyListener(keys);down.setOnKeyListener(keys);
  window.setOnDismissListener(()->{
   for(int i=0;i<peers.getChildCount();i++)peers.getChildAt(i).animate().alpha(1f).setDuration(120).start();
   card.animate().translationY(y).scaleX(scaleX).scaleY(scaleY).setDuration(160).start();card.setElevation(elevation);
   if(!deleted[0]&&!card.requestFocus())peers.requestFocus();
   if(popup==window)popup=null;
  });
  int[] xy=new int[2];card.getLocationOnScreen(xy);int screen=home.getResources().getDisplayMetrics().widthPixels;int x=Math.max(home.dp(12),Math.min(xy[0]+card.getWidth()/2-toolbarWidth/2,screen-toolbarWidth-home.dp(12)));window.showAtLocation(card.getRootView(),Gravity.TOP|Gravity.LEFT,x,Math.max(home.dp(70),xy[1]-home.dp(83)));
  control.requestFocus();
 }
}
