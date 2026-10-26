package dev.slate;

import android.graphics.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Public SFW Wallhaven search, with thumbnails kept separate from full downloads. */
final class Wallhaven {
 static final class Image{
  final String id,full,thumb,resolution;final int width,height;
  Image(String id,String full,String thumb,int width,int height){this.id=id;this.full=full;this.thumb=thumb;this.width=width;this.height=height;resolution=width+" × "+height;}
 }
 static final class Page{
  final List<Image> images;final boolean more;
  Page(List<Image> images,boolean more){this.images=images;this.more=more;}
 }
 static Page search(String query,int page)throws Exception{
  JSONObject root=new JSONObject(new String(bytes(WallpaperPolicy.searchUrl(query,page),2*1024*1024,false),"UTF-8"));
  JSONArray data=root.optJSONArray("data");List<Image> images=new ArrayList<>();Set<String> seen=new HashSet<>();
  if(data!=null)for(int i=0;i<data.length();i++){
   JSONObject item=data.optJSONObject(i);if(item==null||!"sfw".equals(item.optString("purity")))continue;
   String id=item.optString("id"),full=item.optString("path");JSONObject thumbs=item.optJSONObject("thumbs");String thumb=thumbs==null?"":thumbs.optString("large");
   int w=item.optInt("dimension_x"),h=item.optInt("dimension_y");
   if(WallpaperPolicy.validId(id)&&seen.add(id)&&WallpaperPolicy.imageUrl(full)&&WallpaperPolicy.imageUrl(thumb)&&WallpaperPolicy.validDimensions(w,h))images.add(new Image(id,full,thumb,w,h));
  }
  JSONObject meta=root.optJSONObject("meta");return new Page(images,meta!=null&&meta.optInt("current_page",page)<meta.optInt("last_page",page));
 }
 static byte[] bytes(String address,long max,boolean image)throws Exception{
  for(int hop=0;hop<5;hop++){
   if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
   if(image?!WallpaperPolicy.imageUrl(address):!address.startsWith("https://wallhaven.cc/api/v1/search?"))throw new IOException("Invalid wallpaper address.");
   HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
   connection.setConnectTimeout(10000);connection.setReadTimeout(20000);connection.setInstanceFollowRedirects(false);connection.setRequestProperty("User-Agent","Slate/"+BuildConfig.VERSION_NAME);
   try{
    int code=connection.getResponseCode();
    if(code>=300&&code<400){String next=connection.getHeaderField("Location");if(next==null)throw new IOException("Wallpaper link unavailable.");address=new URL(new URL(address),next).toString();continue;}
    if(code==429)throw new IOException("Wallhaven is busy. Give it a moment, then try again.");
    if(code==403)throw new IOException("Wallhaven isn’t allowing this request right now. Try again later.");
    if(code!=200)throw new IOException("Couldn’t reach Wallhaven. Try again.");
    int length=connection.getContentLength();if(length>max)throw new IOException("This image is too large for the TV.");
    if(image){String type=connection.getContentType();if(type==null||!type.startsWith("image/"))throw new IOException("This wallpaper isn’t available as an image.");}
    try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
     byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();if(output.size()+count>max)throw new IOException("This image is too large for the TV.");output.write(buffer,0,count);}if(length>=0&&output.size()!=length)throw new IOException("The download was interrupted. Try again.");return output.toByteArray();
    }
   }finally{connection.disconnect();}
  }
  throw new IOException("Wallpaper link unavailable.");
 }
 static Bitmap decode(byte[] bytes,int targetWidth)throws IOException{
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
  if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>20000||bounds.outHeight>20000||(long)bounds.outWidth*bounds.outHeight>100000000)throw new IOException("This image couldn’t be opened.");
  int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/(sample*2)>=targetWidth)sample*=2;
  BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.RGB_565;
  Bitmap image=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(image==null)throw new IOException("This image couldn’t be opened.");return image;
 }
}
