package dev.slate;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.*;
import java.io.*;
import java.util.*;
import org.json.*;

final class WallpaperLibrary {
 static final String SELECTED="custom_wallpaper";
 static final class Saved{
  final String id,name;Saved(String id,String name){this.id=id;this.name=name;}
 }
 static File file(Context context,String id){if(!WallpaperPolicy.validId(id))throw new IllegalArgumentException("Invalid wallpaper");return new File(context.getFilesDir(),"wallpapers/"+id+".jpg");}
 static List<Saved> list(Context context){
  List<Saved> out=new ArrayList<>();
  try{JSONArray entries=new JSONArray(prefs(context).getString("wallpaper_library","[]"));for(int i=0;i<entries.length();i++){JSONObject entry=entries.optJSONObject(i);if(entry==null)continue;String id=entry.optString("id");if(WallpaperPolicy.validId(id)&&file(context,id).isFile())out.add(new Saved(id,entry.optString("name","Wallhaven")));}}catch(Exception ignored){}
  return out;
 }
 static SharedPreferences prefs(Context context){return context.getSharedPreferences("slate_home",0);}
 static boolean remove(Context context,String id){
  if(!WallpaperPolicy.validId(id))return false;
  try{
   JSONArray entries=new JSONArray();for(Saved saved:list(context))if(!saved.id.equals(id)){JSONObject entry=new JSONObject();entry.put("id",saved.id);entry.put("name",saved.name);entries.put(entry);}
   SharedPreferences preferences=prefs(context);SharedPreferences.Editor edit=preferences.edit().putString("wallpaper_library",entries.toString());
   if(id.equals(preferences.getString(SELECTED,"")))edit.remove(SELECTED).putString("wallpaper_theme","night");
   if(!edit.commit())return false;
   file(context,id).delete();return true;
  }catch(Exception error){return false;}
 }
 static Saved add(Context context,Wallhaven.Image image,String query,Bitmap framed)throws Exception{
  File target=file(context,image.id),pending=new File(target.getParentFile(),image.id+".part");
  try{
   if(!target.getParentFile().isDirectory()&&!target.getParentFile().mkdirs())throw new IOException("Couldn’t save the wallpaper.");
   try(FileOutputStream output=new FileOutputStream(pending)){if(!framed.compress(Bitmap.CompressFormat.JPEG,90,output))throw new IOException("Couldn’t save the wallpaper.");output.getFD().sync();}
   if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
   if(!pending.renameTo(target))throw new IOException("Couldn’t save the wallpaper.");
  }finally{pending.delete();}
  List<Saved> existing=list(context);for(Saved saved:existing)if(saved.id.equals(image.id))return saved;
  String name=(query.trim().isEmpty()?"Wallhaven":query.trim())+" · "+image.resolution;
  JSONArray entries=new JSONArray();JSONObject entry=new JSONObject();entry.put("id",image.id);entry.put("name",name);entries.put(entry);
  for(Saved saved:existing){JSONObject other=new JSONObject();other.put("id",saved.id);other.put("name",saved.name);entries.put(other);}
  if(!prefs(context).edit().putString("wallpaper_library",entries.toString()).commit())throw new IOException("Couldn’t save the wallpaper.");
  return new Saved(image.id,name);
 }
 static Bitmap selected(Context context,String id){
  if(!WallpaperPolicy.validId(id))return null;
  BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.RGB_565;
  return BitmapFactory.decodeFile(file(context,id).getAbsolutePath(),options);
 }
}
