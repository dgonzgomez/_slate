package dev.slate;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** GitHub release check, verified download and system-confirmed install. */
final class UpdateManager {
 static final int INSTALL_PERMISSION_REQUEST=701;
 private boolean awaitingInstallPermission;
 boolean isBusy(){return busy;}
 interface Listener{void changed(String message,boolean busy);}
 static final class Release{
  final String version,url,digest;final long size;
  Release(String version,String url,String digest,long size){this.version=version;this.url=url;this.digest=digest;this.size=size;}
 }
 private final Activity activity;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final ExecutorService worker=Executors.newSingleThreadExecutor();
 private volatile boolean closed,busy;
 private volatile HttpURLConnection connection;
 Release available;
 boolean downloaded;
 private Listener observer;
 private String status="Check for a newer version of Slate.";
 void observe(Listener listener){observer=listener;listener.changed(status,busy);}
 UpdateManager(Activity activity){this.activity=activity;}
 private void say(Listener listener,String message,boolean working){ui.post(()->{status=message;if(!closed&&!activity.isDestroyed()&&observer!=null)observer.changed(message,working);});}
 void check(Listener listener){
  if(busy||closed)return;busy=true;available=null;downloaded=false;
  say(listener,"Checking for updates…",true);
  worker.execute(()->{
   String message;
   try{
    JSONObject release=new JSONObject(readJson("https://api.github.com/repos/"+BuildConfig.UPDATE_REPOSITORY+"/releases/latest"));
    String tag=release.optString("tag_name");
    if(release.optBoolean("draft")||release.optBoolean("prerelease")||!tag.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+"))throw new IOException("The latest release has an invalid version.");
    if(!UpdatePolicy.newer(tag,BuildConfig.VERSION_NAME))message="You’re up to date. Slate "+BuildConfig.VERSION_NAME;
    else{
     JSONArray assets=release.optJSONArray("assets");Release found=null;
     if(assets!=null)for(int i=0;i<assets.length();i++){
      JSONObject a=assets.optJSONObject(i);if(a==null||!UpdatePolicy.ASSET.equals(a.optString("name")))continue;
      String url=a.optString("browser_download_url");long size=a.optLong("size",0);
      if(!UpdatePolicy.releaseUrl(url,BuildConfig.UPDATE_REPOSITORY)||size<1||size>UpdatePolicy.MAX_APK)throw new IOException("This release has an invalid Fire TV download.");
      found=new Release(tag.replaceFirst("^v",""),url,a.optString("digest"),size);break;
     }
     available=found;
     message=found==null?"Version "+tag+" is published, but its Fire TV download isn’t ready yet.":"Slate "+found.version+" is available.";
    }
   }catch(Exception e){message=message(e,"Couldn’t check for updates. Check your connection and try again.");}
   finally{busy=false;disconnect();}
   say(listener,message,false);
  });
 }
 void download(Listener listener){
  if(busy||closed||available==null)return;busy=true;downloaded=false;final Release release=available;
  say(listener,"Downloading Slate "+release.version+"…",true);
  worker.execute(()->{
   File apk=UpdateProvider.apk(activity),part=new File(apk.getParentFile(),"slate.pending.apk");
   String message;
   try{
    if(!apk.getParentFile().isDirectory()&&!apk.getParentFile().mkdirs())throw new IOException("Couldn’t create the update folder.");
    if(apk.getParentFile().getUsableSpace()<release.size*2)throw new IOException("Not enough free space for this update.");
    HttpURLConnection response=open(release.url);
    long length=response.getContentLength();
    if(length>0&&length!=release.size)throw new IOException("The download size changed. Check for updates again.");
    MessageDigest digest=MessageDigest.getInstance("SHA-256");long received=0;int lastPercent=-1;
    try(InputStream input=response.getInputStream();FileOutputStream output=new FileOutputStream(part)){
     byte[] buffer=new byte[32768];int count;
     while((count=input.read(buffer))!=-1){
      if(closed||Thread.currentThread().isInterrupted())throw new IOException("Download cancelled.");
      received+=count;if(received>release.size||received>UpdatePolicy.MAX_APK)throw new IOException("The update download is too large.");
      output.write(buffer,0,count);digest.update(buffer,0,count);
      int percent=(int)(received*100/release.size);
      if(percent!=lastPercent){lastPercent=percent;say(listener,"Downloading Slate "+release.version+"… "+percent+"%",true);}
     }
     output.getFD().sync();
    }
    if(received!=release.size)throw new IOException("The download was cut short. Try again.");
    if(!release.digest.isEmpty()&&!UpdatePolicy.digestMatches(release.digest,digest.digest()))throw new IOException("The update failed its integrity check. Try again.");
    validate(part,release.version);
    if(apk.exists()&&!apk.delete()||!part.renameTo(apk))throw new IOException("Couldn’t save the update. Try again.");
    downloaded=true;message="Slate "+release.version+" is ready. Select Install update to continue.";
   }catch(Exception e){message=message(e,"The update couldn’t be downloaded. Try again.");}
   finally{part.delete();busy=false;disconnect();}
   say(listener,message,false);
  });
 }
 /** Runs on the UI thread; Fire OS owns the final installation confirmation. */
 String install()throws Exception{return install(true);}
 private String install(boolean checkPermission)throws Exception{
  if(!downloaded||available==null)throw new IOException("Download the update first.");
  File apk=UpdateProvider.apk(activity);validate(apk,available.version);
  if(checkPermission&&UpdatePolicy.supportsInstallPermissionCheck(Build.VERSION.SDK_INT,activity.getApplicationInfo().targetSdkVersion)&&!activity.getPackageManager().canRequestPackageInstalls()){
   HomeService.bypassUntil=SystemClock.elapsedRealtime()+120000;
   awaitingInstallPermission=true;
   try{
    try{activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),INSTALL_PERMISSION_REQUEST);}
    catch(ActivityNotFoundException e){activity.startActivityForResult(new Intent(Settings.ACTION_SECURITY_SETTINGS),INSTALL_PERMISSION_REQUEST);}
   }catch(RuntimeException e){awaitingInstallPermission=false;throw e;}
   return "Allow Slate to install apps in Fire TV settings, then select Install update again.";
  }
  Uri uri=Uri.parse("content://"+activity.getPackageName()+".update/slate.apk");
  Intent install=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,UpdateProvider.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  install.setClipData(ClipData.newRawUri("Slate update",uri));
  HomeService.bypassUntil=SystemClock.elapsedRealtime()+120000;
  activity.startActivity(install);
  return "Confirm the update in the system installer. If prompted, allow installation from Slate.";
 }
 @SuppressWarnings("deprecation")
 private void validate(File file,String version)throws Exception{
  PackageManager pm=activity.getPackageManager();
  PackageInfo incoming=pm.getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNATURES);
  PackageInfo installed=pm.getPackageInfo(activity.getPackageName(),PackageManager.GET_SIGNATURES);
  if(incoming==null)throw new IOException("The downloaded APK is damaged. Try again.");
  if(!installed.packageName.equals(incoming.packageName))throw new IOException("The download isn’t a Slate update.");
  if(incoming.versionCode<=installed.versionCode)throw new IOException("This APK isn’t newer than your installed version.");
  if(!version.equals(incoming.versionName))throw new IOException("The APK version doesn’t match its release.");
  Set<Signature> expected=signatures(installed),actual=signatures(incoming);
  if(expected.isEmpty()||!expected.equals(actual))throw new IOException("This update uses a different signing key. Your data is safe; ask the maintainer for an update signed with the original Slate key.");
  if(Build.VERSION.SDK_INT>=24&&incoming.applicationInfo!=null&&incoming.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)throw new IOException("This update needs a newer Fire OS version.");
 }
 void permissionReturned(){
  if(closed||!awaitingInstallPermission)return;
  awaitingInstallPermission=false;
  // The system installer owns the permission decision after returning from settings.
  try{say(observer,install(false),false);}
  catch(Exception e){say(observer,message(e,"The installer couldn’t open. Select Install update to try again."),false);}
 }
 private static Set<Signature> signatures(PackageInfo info){return info.signatures==null?Collections.emptySet():new HashSet<>(Arrays.asList(info.signatures));}
 private HttpURLConnection open(String address)throws Exception{
  for(int hop=0;hop<7;hop++){
   if(closed)throw new IOException("Cancelled.");
   if(!UpdatePolicy.transportUrl(address))throw new IOException("The update redirected outside GitHub.");
   HttpURLConnection request=(HttpURLConnection)new URL(address).openConnection();connection=request;
   request.setConnectTimeout(10000);request.setReadTimeout(20000);request.setInstanceFollowRedirects(false);
   request.setRequestProperty("User-Agent","Slate/"+BuildConfig.VERSION_NAME);
   request.setRequestProperty("Accept","application/vnd.github+json");
   int code=request.getResponseCode();
   if(code>=300&&code<400){String location=request.getHeaderField("Location");if(location==null)throw new IOException("Missing download redirect.");String next=new URL(new URL(address),location).toString();request.disconnect();address=next;continue;}
   if(code==404)throw new IOException("No public release is available yet in "+BuildConfig.UPDATE_REPOSITORY+".");
   if(code==403||code==429)throw new IOException("GitHub is limiting update checks. Please try again later.");
   if(code!=200)throw new IOException("GitHub couldn’t serve the update ("+code+"). Try again later.");
   return request;
  }
  throw new IOException("Too many update redirects.");
 }
 private String readJson(String url)throws Exception{
  HttpURLConnection request=open(url);
  try(InputStream input=request.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){if(output.size()+count>1024*1024)throw new IOException("The release response is too large.");output.write(buffer,0,count);}return new String(output.toByteArray(),"UTF-8");
  }
 }
 private static String message(Exception error,String fallback){return error.getClass()==IOException.class&&error.getMessage()!=null?error.getMessage():fallback;}
 private void disconnect(){HttpURLConnection active=connection;if(active!=null)active.disconnect();connection=null;}
 void close(){closed=true;disconnect();worker.shutdownNow();ui.removeCallbacksAndMessages(null);}
}
