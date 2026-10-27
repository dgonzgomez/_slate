package dev.slate;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** Three-column, remote-friendly wallpaper browser. Thumbnails only until Add. */
final class WallpaperPicker extends FrameLayout {
 interface Added{void done(WallpaperLibrary.Saved saved);}
 private final Context context;
 private final Runnable back;
 private final Added added;
 private final Runnable removed;
 private final Handler ui=new Handler(Looper.getMainLooper());
 private final ExecutorService api=Executors.newSingleThreadExecutor(),images=Executors.newFixedThreadPool(2);
 private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024){protected int sizeOf(String key,Bitmap bitmap){return bitmap.getByteCount();}};
 private final Map<String,List<WeakReference<ImageView>>> pending=new HashMap<>();
 private final ArrayList<Wallhaven.Image> results=new ArrayList<>();
 private final Set<String> ids=new HashSet<>(),savedIds=new HashSet<>();
 private final EditText search;
 private final GridView grid;
 private final TextView status,retry;
 private final ProgressBar progress;
 private final Tiles adapter=new Tiles();
 private volatile boolean closed;
 private boolean loading,hasMore,adding;
 private WallpaperFramer framer;
 private final WallpaperActions wallpaperActions=new WallpaperActions();
 private int generation,page;
 private String query="";
 WallpaperPicker(Context context,Runnable back,Added added,Runnable removed){
  super(context);this.context=context;this.back=back;this.added=added;this.removed=removed;
  setBackgroundColor(0xf20b0e14);setClickable(true);setFocusable(false);
  for(WallpaperLibrary.Saved saved:WallpaperLibrary.list(context))savedIds.add(saved.id);
  LinearLayout column=new LinearLayout(context);column.setOrientation(1);column.setPadding(dp(48),dp(30),dp(48),0);addView(column,new LayoutParams(-1,-1));
  LinearLayout header=new LinearLayout(context);header.setGravity(Gravity.CENTER_VERTICAL);column.addView(header,new LinearLayout.LayoutParams(-1,dp(48)));
  IconButton previous=new IconButton();previous.setContentDescription("Back to wallpapers");previous.setOnClickListener(v->back.run());header.addView(previous,new LinearLayout.LayoutParams(dp(44),dp(44)));
  TextView title=label("Add Wallpaper",26,0xfff5f5f7);title.setTypeface(Typeface.create("sans-serif-medium",0));LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,-2,1);titleLp.leftMargin=dp(18);header.addView(title,titleLp);
  header.addView(label("Wallhaven",13,0x99ebebf5));
  search=new EditText(context);search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});search.setSingleLine(true);search.setTextSize(17);search.setHint("Search wallpapers");search.setHintTextColor(0x99ebebf5);search.setTextColor(0xfff5f5f7);search.setPadding(dp(20),0,dp(20),0);search.setBackground(fill(0x1effffff,12));search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
  LinearLayout.LayoutParams searchLp=new LinearLayout.LayoutParams(-1,dp(50));searchLp.topMargin=dp(20);column.addView(search,searchLp);
  search.setOnFocusChangeListener((v,focused)->{search.setBackground(fill(focused?0xfff5f5f7:0x1effffff,12));search.setTextColor(focused?0xff1c1c1e:0xfff5f5f7);search.setHintTextColor(focused?0x991c1c1e:0x99ebebf5);});
  search.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH||event!=null&&event.getKeyCode()==KeyEvent.KEYCODE_ENTER&&event.getAction()==KeyEvent.ACTION_DOWN){begin();return true;}return false;});
  LinearLayout info=new LinearLayout(context);info.setGravity(Gravity.CENTER_VERTICAL);column.addView(info,new LinearLayout.LayoutParams(-1,dp(42)));
  status=label("Popular wallpapers",13,0x99ebebf5);status.setSingleLine(false);info.addView(status,new LinearLayout.LayoutParams(0,-2,1));
  progress=new ProgressBar(context);progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xfff5f5f7));info.addView(progress,new LinearLayout.LayoutParams(dp(20),dp(20)));
  retry=label("Try again",13,0xfff5f5f7);retry.setGravity(Gravity.CENTER);retry.setPadding(dp(12),dp(6),dp(12),dp(6));retry.setBackground(fill(0x33ffffff,9));retry.setFocusable(true);retry.setClickable(true);retry.setVisibility(GONE);retry.setOnClickListener(v->load());info.addView(retry,new LinearLayout.LayoutParams(-2,-2));
  grid=new GridView(context);grid.setNumColumns(3);grid.setHorizontalSpacing(dp(18));grid.setVerticalSpacing(dp(18));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setPadding(dp(8),dp(8),dp(8),dp(32));grid.setClipToPadding(false);grid.setClipChildren(false);grid.setSelector(android.R.color.transparent);grid.setVerticalScrollBarEnabled(false);grid.setAdapter(adapter);column.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
  grid.setOnFocusChangeListener((v,f)->{if(!wallpaperActions.isShowing())adapter.notifyDataSetChanged();});
  grid.setOnItemClickListener((parent,view,position,id)->choose(results.get(position)));
  grid.setOnItemLongClickListener((parent,view,position,id)->{deleteTile(view,position);return true;});
  grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> p,View v,int n,long id){adapter.notifyDataSetChanged();}public void onNothingSelected(AdapterView<?> p){}});
  grid.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView view,int state){}public void onScroll(AbsListView view,int first,int visible,int total){if(total>0&&first+visible>=total-3&&hasMore&&!loading&&!adding)load();}});
  search.setOnKeyListener((v,key,event)->{if(key==KeyEvent.KEYCODE_DPAD_DOWN&&event.getAction()==KeyEvent.ACTION_DOWN&&!results.isEmpty()){hideKeyboard();grid.requestFocus();grid.setSelection(0);return true;}return false;});
  grid.setOnKeyListener((v,key,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;int n=Math.max(0,grid.getSelectedItemPosition());if(key==KeyEvent.KEYCODE_MENU){if(n<results.size()){View tile=grid.getChildAt(n-grid.getFirstVisiblePosition());if(tile!=null)deleteTile(tile,n);}return true;}if(key==KeyEvent.KEYCODE_DPAD_UP&&n<3){search.requestFocus();return true;}if(key==KeyEvent.KEYCODE_DPAD_LEFT&&n%3==0)return true;if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&(n%3==2||n==results.size()-1))return true;return false;});
  post(()->{search.requestFocus();begin();});
 }
 private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
 private TextView label(String value,int size,int color){TextView view=new TextView(context);view.setText(value);view.setTextSize(size);view.setTextColor(color);return view;}
 private GradientDrawable fill(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
 private void hideKeyboard(){((InputMethodManager)context.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);}
 private void begin(){if(adding)return;generation++;query=search.getText().toString().trim();page=0;loading=false;results.clear();ids.clear();hasMore=true;adapter.notifyDataSetChanged();hideKeyboard();load();}
 private void load(){
  if(loading||closed||adding)return;loading=true;final int current=generation,next=page+1;final String term=query;retry.setVisibility(GONE);progress.setVisibility(VISIBLE);status.setText(page==0?"Finding wallpapers…":"Loading more…");
  api.execute(()->{
   try{Wallhaven.Page found=Wallhaven.search(term,next);ui.post(()->{if(closed||current!=generation)return;page=next;hasMore=found.more;loading=false;for(Wallhaven.Image image:found.images)if(ids.add(image.id))results.add(image);adapter.notifyDataSetChanged();progress.setVisibility(GONE);status.setText(results.isEmpty()?"No wallpapers found. Try another search.":term.isEmpty()?"Popular wallpapers":"Results for “"+term+"”");if(results.isEmpty()&&hasMore)load();});}
   catch(Exception e){ui.post(()->{if(closed||current!=generation)return;loading=false;hasMore=false;progress.setVisibility(GONE);status.setText(e.getClass()==IOException.class?e.getMessage():"Couldn’t load wallpapers. Check your connection and try again.");retry.setVisibility(VISIBLE);});}
  });
 }
 private void choose(Wallhaven.Image image){
  if(adding||closed)return;
  adding=true;progress.setVisibility(VISIBLE);retry.setVisibility(GONE);status.setText("Opening wallpaper…");final String term=query;
  api.execute(()->{
   try{Bitmap original=Wallhaven.decode(Wallhaven.bytes(image.full,WallpaperPolicy.MAX_IMAGE_BYTES,true),1920);ui.post(()->{
    if(closed){original.recycle();return;}progress.setVisibility(GONE);status.setText(term.isEmpty()?"Popular wallpapers":"Results for “"+term+"”");
    framer=new WallpaperFramer(context,original,this::dismissFrame,framed->{
     WallpaperFramer editing=framer;
     api.execute(()->{try{WallpaperLibrary.Saved saved=WallpaperLibrary.add(context,image,term,framed);ui.post(()->{if(!closed&&framer==editing){savedIds.add(saved.id);dismissFrame();adapter.notifyDataSetChanged();added.done(saved);}});}
      catch(Exception error){ui.post(()->{if(!closed&&framer==editing){editing.failed();Toast.makeText(context,"Couldn’t save this wallpaper. Try again.",Toast.LENGTH_LONG).show();}});}finally{framed.recycle();}});
    });addView(framer,new LayoutParams(-1,-1));
   });}catch(Exception error){ui.post(()->{if(closed)return;adding=false;progress.setVisibility(GONE);status.setText(error.getClass()==IOException.class?error.getMessage():"Couldn’t open this wallpaper. Try again.");});}
  });
 }
 private void dismissFrame(){if(framer==null)return;WallpaperFramer previous=framer;framer=null;removeView(previous);previous.release();adding=false;grid.requestFocus();}
 private void deleteTile(View tile,int position){
  if(position<0||position>=results.size())return;
  String imageId=results.get(position).id;if(!savedIds.contains(imageId))return;
  wallpaperActions.show((MainActivity)context,tile,grid,()->{
   if(!WallpaperLibrary.remove(context,imageId)){Toast.makeText(context,"Couldn’t delete this wallpaper",Toast.LENGTH_SHORT).show();grid.post(()->grid.requestFocus());return;}
   savedIds.remove(imageId);adapter.notifyDataSetChanged();removed.run();grid.post(()->grid.requestFocus());Toast.makeText(context,"Wallpaper deleted",Toast.LENGTH_SHORT).show();
  });
 }
 boolean back(){if(framer==null)return false;dismissFrame();return true;}
 private void thumbnail(Wallhaven.Image image,ImageView view){
  view.setTag(image.id);Bitmap bitmap=cache.get(image.id);if(bitmap!=null){view.setImageBitmap(bitmap);return;}view.setImageDrawable(null);
  List<WeakReference<ImageView>> waiting=pending.get(image.id);if(waiting!=null){waiting.add(new WeakReference<>(view));return;}
  waiting=new ArrayList<>();waiting.add(new WeakReference<>(view));pending.put(image.id,waiting);
  images.execute(()->{
   if(closed)return;
   Bitmap result=null;
   try{
    File directory=new File(context.getCacheDir(),"wallpaper-thumbnails");directory.mkdirs();File file=new File(directory,image.id+".jpg");byte[] bytes;
    if(file.isFile()){try(FileInputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=input.read(b))!=-1){if(output.size()+n>2*1024*1024)throw new IOException();output.write(b,0,n);}bytes=output.toByteArray();}}
    else{bytes=Wallhaven.bytes(image.thumb,2*1024*1024,true);if(Thread.currentThread().isInterrupted())return;try(FileOutputStream output=new FileOutputStream(file)){output.write(bytes);}trimThumbnails(directory);}
    result=Wallhaven.decode(bytes,460);
   }catch(Exception ignored){}
   final Bitmap decoded=result;ui.post(()->{List<WeakReference<ImageView>> views=pending.remove(image.id);if(closed||decoded==null)return;cache.put(image.id,decoded);if(views!=null)for(WeakReference<ImageView> reference:views){ImageView target=reference.get();if(target!=null&&image.id.equals(target.getTag()))target.setImageBitmap(decoded);}});
  });
 }
 private static synchronized void trimThumbnails(File directory){
  File[] files=directory.listFiles();if(files==null)return;
  Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));long bytes=0;for(File file:files)bytes+=file.length();int remaining=files.length;
  for(File file:files){if(bytes<=32L*1024*1024&&remaining<=120)break;long length=file.length();if(file.delete()){bytes-=length;remaining--;}}
 }
 private final class Tiles extends BaseAdapter{
  public int getCount(){return results.size();}public Object getItem(int position){return results.get(position);}public long getItemId(int position){return results.get(position).id.hashCode();}public boolean hasStableIds(){return true;}
  public View getView(int position,View recycled,android.view.ViewGroup parent){
   Tile tile=recycled instanceof Tile?(Tile)recycled:new Tile();Wallhaven.Image image=results.get(position);int width=Math.max(dp(180),(getWidth()-dp(48*2+16+18*2))/3);tile.setLayoutParams(new AbsListView.LayoutParams(-1,(int)(width*9f/16)+dp(35)));tile.meta.setText(image.resolution);tile.saved.setText(savedIds.contains(image.id)?"✓":"+");tile.active(grid.hasFocus()&&grid.getSelectedItemPosition()==position);thumbnail(image,tile.image);tile.setContentDescription(image.resolution+" wallpaper"+(savedIds.contains(image.id)?", added":""));return tile;
  }
 }
 private final class Tile extends FrameLayout{
  final ImageView image;final TextView meta,saved;final Path clip=new Path();
  Tile(){super(context);setFocusable(false);setBackground(fill(0x1effffff,12));image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);LayoutParams photo=new LayoutParams(-1,-1);photo.bottomMargin=dp(35);addView(image,photo);meta=label("",12,0x99ebebf5);meta.setPadding(dp(12),0,0,0);meta.setGravity(Gravity.CENTER_VERTICAL);addView(meta,new LayoutParams(-1,dp(35),Gravity.BOTTOM));saved=label("+",23,0xfff5f5f7);saved.setGravity(Gravity.CENTER);LayoutParams plus=new LayoutParams(dp(35),dp(35),Gravity.BOTTOM|Gravity.RIGHT);addView(saved,plus);}
  void active(boolean on){setBackground(fill(on?0xfff5f5f7:0x1effffff,12));meta.setTextColor(on?0x991c1c1e:0x99ebebf5);saved.setTextColor(on?0xff1c1c1e:0xfff5f5f7);animate().scaleX(on?1.025f:1).scaleY(on?1.025f:1).setDuration(140).start();setElevation(on?dp(8):0);}
  protected void dispatchDraw(Canvas canvas){clip.reset();clip.addRoundRect(new RectF(0,0,getWidth(),getHeight()),dp(12),dp(12),Path.Direction.CW);int save=canvas.save();canvas.clipPath(clip);super.dispatchDraw(canvas);canvas.restoreToCount(save);}
 }
 private final class IconButton extends View{
  final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
  IconButton(){super(context);setFocusable(true);setClickable(true);setBackground(fill(0x33ffffff,22));setOnFocusChangeListener((v,f)->{setBackground(fill(f?0xfff5f5f7:0x33ffffff,22));invalidate();});}
  protected void onDraw(Canvas canvas){float cx=getWidth()/2f,cy=getHeight()/2f;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(isFocused()?0xff1c1c1e:0xfff5f5f7);Path path=new Path();path.moveTo(cx+dp(7),cy);path.lineTo(cx-dp(7),cy);path.lineTo(cx,cy-dp(7));path.moveTo(cx-dp(7),cy);path.lineTo(cx,cy+dp(7));canvas.drawPath(path,paint);}
 }
 void close(){wallpaperActions.dismiss();if(framer!=null){WallpaperFramer previous=framer;framer=null;removeView(previous);previous.release();}closed=true;generation++;api.shutdownNow();images.shutdownNow();ui.removeCallbacksAndMessages(null);pending.clear();hideKeyboard();}
 @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
}
