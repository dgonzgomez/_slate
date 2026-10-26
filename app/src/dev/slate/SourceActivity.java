package dev.slate;

import android.app.Activity;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen HDMI passthrough. Amazon's tuner activity is blocked for this app. */
public class SourceActivity extends Activity {
 private TvView screen;
 private TextView status, note;
 private String label = "HDMI";
 protected void onCreate(Bundle saved) {
  super.onCreate(saved);
  String inputId = getIntent().getStringExtra("inputId");
  if (getIntent().getStringExtra("label") != null) label = getIntent().getStringExtra("label");
  getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  FrameLayout root = new FrameLayout(this);
  root.setBackgroundColor(0xff000000);
  screen = new TvView(this);
  root.addView(screen, new FrameLayout.LayoutParams(-1, -1));
  status = label(label, 18, Gravity.TOP | Gravity.START);
  note = label("No signal", 22, Gravity.CENTER);
  note.setVisibility(android.view.View.GONE);
  root.addView(status); root.addView(note);
  setContentView(root);
  if (inputId == null) { unavailable(); return; }
  screen.setCallback(new TvView.TvInputCallback() {
   public void onConnectionFailed(String id) { unavailable(); }
   public void onVideoAvailable(String id) { note.setVisibility(android.view.View.GONE); status.setText(label); }
   public void onVideoUnavailable(String id, int reason) { note.setText("No signal"); note.setVisibility(android.view.View.VISIBLE); }
  });
  try { screen.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId)); }
  catch (Exception e) { android.util.Log.e("Slate", "Tune failed", e); unavailable(); }
 }
 private TextView label(String text, int size, int gravity) {
  TextView view = new TextView(this);
  view.setText(text); view.setTextSize(size); view.setTextColor(0xfff4f5f7);
  int pad = Math.round(36 * getResources().getDisplayMetrics().density);
  view.setPadding(pad, pad, pad, pad);
  view.setLayoutParams(new FrameLayout.LayoutParams(-2, -2, gravity));
  return view;
 }
 private void unavailable() { note.setText(label + " is unavailable"); note.setVisibility(android.view.View.VISIBLE); }
 protected void onStop() {
  if (screen != null) screen.reset();
  if (!isFinishing()) finish();
  super.onStop();
 }
 public boolean onKeyDown(int key, KeyEvent event) {
  if (key == KeyEvent.KEYCODE_BACK) { finish(); return true; }
  return super.onKeyDown(key, event);
 }
}
