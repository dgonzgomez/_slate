package dev.slate;

import java.net.URI;
import java.net.URLEncoder;

/** Shared URL and image rules, independent of Android. */
public final class WallpaperPolicy {
 private WallpaperPolicy(){}
 public static final long MAX_IMAGE_BYTES=32L*1024*1024;
 public static boolean validId(String id){return id!=null&&id.matches("[a-z0-9]{6}");}
 public static boolean imageUrl(String value){
  try{URI uri=new URI(value);return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&("w.wallhaven.cc".equals(uri.getHost())||"th.wallhaven.cc".equals(uri.getHost()));}catch(Exception e){return false;}
 }
 public static boolean validDimensions(int width,int height){return width>=64&&height>=64&&width<=20000&&height<=20000&&(long)width*height<=100000000;}
 public static String searchUrl(String query,int page){
  try{return "https://wallhaven.cc/api/v1/search?categories=111&purity=100&sorting="+(query.trim().isEmpty()?"toplist&topRange=1M":"relevance")+(query.trim().isEmpty()?"&atleast=1920x1080&ratios=16x9%2C16x10%2C21x9":"")+"&page="+Math.max(1,page)+"&q="+URLEncoder.encode(query.trim(),"UTF-8");}catch(Exception e){throw new IllegalArgumentException(e);}
 }
}
