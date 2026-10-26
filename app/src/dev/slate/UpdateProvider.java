package dev.slate;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Shares exactly one verified update APK through temporary read permission. */
public class UpdateProvider extends ContentProvider {
 public static final String MIME="application/vnd.android.package-archive";
 static File apk(Context context){return new File(context.getCacheDir(),"updates/slate.apk");}
 private void validate(Uri uri){if(!"/slate.apk".equals(uri.getPath())||!"content".equals(uri.getScheme())||!uri.getAuthority().equals(getContext().getPackageName()+".update"))throw new IllegalArgumentException("Unknown update file");}
 public boolean onCreate(){return true;}
 public String getType(Uri uri){validate(uri);return MIME;}
 public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{validate(uri);if(!"r".equals(mode))throw new FileNotFoundException("Read only");return ParcelFileDescriptor.open(apk(getContext()),ParcelFileDescriptor.MODE_READ_ONLY);}
 public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){validate(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=UpdatePolicy.ASSET;else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=apk(getContext()).length();}cursor.addRow(values);return cursor;}
 public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read only");}
 public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
 public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read only");}
}
