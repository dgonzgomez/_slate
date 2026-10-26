package dev.slate;

/** Normalized framing state, shared by the on-screen preview and saved TV crop. */
public final class WallpaperCrop {
 public double zoom=1,x=.5,y=.5;
 public double[] bounds(int sourceWidth,int sourceHeight,int width,int height){
  double scale=Math.max(width/(double)sourceWidth,height/(double)sourceHeight)*zoom;
  double w=sourceWidth*scale,h=sourceHeight*scale;
  return new double[]{-(w-width)*x,-(h-height)*y,w,h};
 }
 public void move(double dx,double dy,int sourceWidth,int sourceHeight,int width,int height){
  double[] b=bounds(sourceWidth,sourceHeight,width,height);
  if(b[2]>width+.001)x=clamp(x-dx/(b[2]-width));
  if(b[3]>height+.001)y=clamp(y-dy/(b[3]-height));
 }
 public void stepVertical(boolean up,double step,int sourceWidth,int sourceHeight,int width,int height){
  // Up navigates toward the top of the image, so the image itself moves down.
  move(0,up?step:-step,sourceWidth,sourceHeight,width,height);
 }
 public void magnify(double delta){zoom=Math.max(1,Math.min(3,Math.round((zoom+delta)*100)/100d));}
 private static double clamp(double value){return Math.max(0,Math.min(1,value));}
}
