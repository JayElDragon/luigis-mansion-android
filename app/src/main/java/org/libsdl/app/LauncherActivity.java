package org.libsdl.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/** Start screen shown at launch: Play, Options, Game Files (import your own disc image), Quit. */
public class LauncherActivity extends Activity implements MenuView.Listener {
    private static final int REQ_PICK = 42;
    private static final int REQ_TEXPACK = 43;

    private MenuView menu;
    private SharedPreferences prefs;
    private AlertDialog progress;
    private MediaPlayer music;
    private boolean musicDone;

    private void startMusic() {
        if (musicDone || !prefs.getBoolean("menu_music", true)) {
            if (!prefs.getBoolean("menu_music", true)) stopMusic();
            return;
        }
        if (music != null) {
            try { if (!music.isPlaying()) music.start(); } catch (Exception ignored) { }
            return;
        }
        try (AssetFileDescriptor afd = getAssets().openFd("menu_music.ogg")) {
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            m.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            m.setLooping(true);
            m.setVolume(0.85f, 0.85f);
            m.prepare();
            m.start();
            music = m;
        } catch (Exception ignored) {
            music = null;
        }
    }

    private void pauseMusic() {
        if (music != null) {
            try { if (music.isPlaying()) music.pause(); } catch (Exception ignored) { }
        }
    }

    private void stopMusic() {
        if (music != null) {
            try { music.stop(); } catch (Exception ignored) { }
            music.release();
            music = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startMusic();
    }

    @Override protected void onPause() {
        pauseMusic();
        try { if (prefs != null) GameSettings.write(this, prefs); } catch (Throwable ignored) { }
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopMusic();
        super.onDestroy();
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("lm", MODE_PRIVATE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        menu = new MenuView(this);
        menu.setListener(this);
        setContentView(menu);
        menu.requestFocus();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Window w = getWindow();
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    @Override public void onItem(int index) {
        switch (index) {
            case 0: play(); break;
            case 1: showOptions(); break;
            case 2: showFiles(); break;
            default: finishAndRemoveTask();
        }
    }

    // ---- game files ----

    private File gameDir() { return getExternalFilesDir(null); }

    private static boolean isDisc(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return f.isFile() && (n.endsWith(".iso") || n.endsWith(".rvz"));
    }

    private File findDisc() {
        File d = gameDir();
        if (d == null) return null;
        File[] fs = d.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (isDisc(f)) return f;
        return null;
    }

    private void play() {
        if (findDisc() == null) {
            dialog().setTitle("No game file yet")
                    .setMessage("Open Game Files and choose your Luigi's Mansion disc image (.iso or .rvz) first.")
                    .setPositiveButton("Game Files", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { showFiles(); }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        GameSettings.write(this, prefs);
        musicDone = true;
        stopMusic();
        startActivity(new Intent(this, MyGame.class));
        finish();
    }

    private boolean filesVisible;
    private boolean installing;

    private void closeFiles() {
        filesVisible = false;
        setContentView(menu);
        menu.requestFocus();
    }

    private void showFiles() {
        File disc = findDisc();
        String current = disc == null ? "No game installed" : disc.getName() + " (" + (disc.length() / (1024 * 1024)) + " MB)";
        filesVisible = true;
        FilesView view = new FilesView(this, current, new FilesView.Listener() {
            @Override public void onAction(int action) {
                if (action == 0) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQ_PICK);
                } else if (action == 1) {
                    dialog().setTitle("Remove current game file?")
                        .setMessage("The disc imported into this app will be removed.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Remove", (d, w) -> {
                            File f = findDisc();
                            while (f != null && f.delete()) f = findDisc();
                            showFiles();
                        }).show();
                } else closeFiles();
            }
        });
        setContentView(view);
        view.requestFocus();
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null)
            importFile(data.getData());
        if (req == REQ_TEXPACK && res == RESULT_OK && data != null && data.getData() != null)
            importTexturePack(data.getData());
    }

    private void importFile(final Uri uri) {
        String name = null;
        long size = -1;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = "game.iso";
        String low = name.toLowerCase(Locale.ROOT);
        if (!low.endsWith(".iso") && !low.endsWith(".rvz")) {
            Toast.makeText(this, "Please pick an .iso or .rvz file", Toast.LENGTH_LONG).show();
            return;
        }
        final File dir = gameDir();
        if (dir == null) {
            Toast.makeText(this, "No storage available", Toast.LENGTH_LONG).show();
            return;
        }
        if (size > 0 && dir.getUsableSpace() < size + 64L * 1024 * 1024) {
            Toast.makeText(this, "Not enough free storage for this file", Toast.LENGTH_LONG).show();
            return;
        }
        if(installing) return;
        installing=true;
        final String selectedName=name;
        final long total=size;
        final FilesView installView=new FilesView(this,selectedName,action -> {});
        installView.setInstallProgress(0,total);
        setContentView(installView);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String error=null;
            try(InputStream in=getContentResolver().openInputStream(uri)) {
                DiscInstaller.install(in,dir,selectedName,total,(done,length)->runOnUiThread(()-> {
                    if(!isDestroyed()) installView.setInstallProgress(done,length);
                }));
            } catch(Exception e) { error=e.getMessage()==null?"Unable to install the selected disc.":e.getMessage(); }
            final String result=error;
            runOnUiThread(()-> {
                installing=false;
                if(isDestroyed()) return;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                showFiles();
                if(result==null) dialog().setTitle("Installation complete")
                    .setMessage("The game is installed in the port's storage and ready to play.")
                    .setPositiveButton("Play",(d,w)->play()).setNegativeButton("Done",null).show();
                else dialog().setTitle("Installation failed").setMessage(result)
                    .setPositiveButton("OK",null).show();
            });
        },"DiscInstaller").start();
    }

    // ---- HD texture packs (Options > Graphics > Install Texture Pack) ----

    void installTexturePack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, REQ_TEXPACK);
    }

    private boolean packInstalling;

    private void importTexturePack(final Uri uri) {
        if (packInstalling) return;
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0) name = c.getString(ni);
            }
        } catch (Exception ignored) { }
        if (name == null) name = "pack.zip";
        if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            Toast.makeText(this, "Please pick a .zip texture pack", Toast.LENGTH_LONG).show();
            return;
        }
        packInstalling = true;
        final String zipName = name;
        final AlertDialog wait = dialog().setTitle("Installing texture pack")
                .setMessage("Unpacking… keep this screen open.").setCancelable(false).show();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            int found = 0; String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                found = TexturePacks.install(this, in, zipName, files -> runOnUiThread(() -> {
                    if (wait.isShowing()) wait.setMessage("Unpacking… " + files + " files");
                }));
            } catch (Exception e) {
                error = e.getMessage() == null ? "Unable to install this texture pack." : e.getMessage();
            }
            final int count = found; final String err = error;
            runOnUiThread(() -> {
                packInstalling = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (wait.isShowing()) wait.dismiss();
                if (isDestroyed()) return;
                if (err == null) {
                    prefs.edit().putString("tp_pack", TexturePacks.safeName(zipName)).putBoolean("tp_on", true).commit();
                    dialog().setTitle("Texture pack installed")
                            .setMessage(count + " textures found. It is selected and Custom Textures is turned on.")
                            .setPositiveButton("OK", null).show();
                } else {
                    dialog().setTitle("Install failed").setMessage(err).setPositiveButton("OK", null).show();
                }
                if (options != null) options.invalidate();
            });
        }, "TexturePackInstaller").start();
    }

    // ---- options ----

    private OptionsView options;
    private boolean controlsPreview;

    void previewControls() {
        controlsPreview = true;
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xFF101A20);
        android.widget.TextView heading = new android.widget.TextView(this);
        heading.setText("TOUCH CONTROLS PREVIEW\nTap here or press Back to return");
        heading.setTextColor(0xFFD5E4DF);
        heading.setTextSize(18);
        heading.setGravity(android.view.Gravity.CENTER);
        frame.addView(new TouchOverlay(this, true));
        android.widget.FrameLayout.LayoutParams caption = new android.widget.FrameLayout.LayoutParams(
                getResources().getDisplayMetrics().widthPixels / 2, 150, android.view.Gravity.CENTER);
        frame.addView(heading, caption);
        heading.setOnClickListener(v -> closeControlsPreview());
        frame.setFocusableInTouchMode(true);
        frame.setDefaultFocusHighlightEnabled(false);
        frame.setOnKeyListener((v, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) closeControlsPreview();
                return true;
            }
            return false;
        });
        setContentView(frame);
        frame.requestFocus();
    }

    private TouchOverlay editOverlay;

    /** Options > Controls > Edit Layout from the start menu. */
    void editLayout() {
        controlsPreview = true;
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xFF101A20);
        final TouchOverlay ov = new TouchOverlay(this, true);
        editOverlay = ov;
        frame.addView(ov);
        frame.setFocusableInTouchMode(true);
        frame.setDefaultFocusHighlightEnabled(false);
        frame.setOnKeyListener((v, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) ov.cancelEdit();
                return true;
            }
            return false;
        });
        setContentView(frame);
        frame.requestFocus();
        ov.post(() -> ov.startEdit(this::closeControlsPreview));
    }

    private void closeControlsPreview() {
        editOverlay = null;
        controlsPreview = false;
        setContentView(options);
        options.requestFocus();
    }

    private void showOptions() {
        options = new OptionsView(this, prefs, new OptionsView.Listener() {
            @Override public void onClose() { closeOptions(); }
        });
        setContentView(options);
        options.requestFocus();
    }

    private void closeOptions() {
        if (options != null) {
            options.release();
            options = null;
        }
        setContentView(menu);
        menu.requestFocus();
        startMusic();
    }

    @Override public void onBackPressed() {
        if(installing) return;
        if (controlsPreview) {
            if (editOverlay != null && editOverlay.isEditing()) editOverlay.cancelEdit();
            else closeControlsPreview();
        }
        else if (filesVisible) closeFiles();
        else if (options != null) options.back();
        else super.onBackPressed();
    }
}

