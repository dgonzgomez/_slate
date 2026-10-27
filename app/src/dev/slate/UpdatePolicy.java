package dev.slate;

import java.net.URI;
import java.security.MessageDigest;

/** Android-independent rules for release selection and integrity checks. */
public final class UpdatePolicy {
 public static final String ASSET="Slate-firetv.apk";
 public static final long MAX_APK=128L*1024*1024;
 private UpdatePolicy(){}
 /** canRequestPackageInstalls requires both device and target API 26+. */
 public static boolean supportsInstallPermissionCheck(int deviceSdk,int targetSdk){return deviceSdk>=26&&targetSdk>=26;}
 public static boolean newer(String candidate,String installed){
  if(candidate==null||!candidate.matches("v?[0-9]+\\.[0-9]+\\.[0-9]+"))return false;
  String[] a=candidate.replaceFirst("^v","").split("\\."),b=installed.replaceFirst("^v","").split("\\.");
  try{for(int i=0;i<3;i++){long x=Long.parseLong(a[i]),y=i<b.length?Long.parseLong(b[i]):0;if(x!=y)return x>y;}}catch(NumberFormatException e){return false;}
  return false;
 }
 public static boolean releaseUrl(String value,String repo){
  try{URI uri=new URI(value);return "https".equals(uri.getScheme())&&"github.com".equals(uri.getHost())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getPath().startsWith("/"+repo+"/releases/download/");}catch(Exception e){return false;}
 }
 public static boolean transportUrl(String value){
  try{URI uri=new URI(value);String host=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&host!=null&&(host.equals("github.com")||host.equals("api.github.com")||host.endsWith(".githubusercontent.com"));}catch(Exception e){return false;}
 }
 public static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}
 public static boolean digestMatches(String expected,byte[] actual){return expected!=null&&expected.matches("sha256:[a-fA-F0-9]{64}")&&MessageDigest.isEqual(expected.substring(7).toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.US_ASCII),hex(actual).getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
}
