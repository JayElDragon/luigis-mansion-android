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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
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
    private static final int REQ_SAVES = 44;
    private static final int REQ_DLC = 45;

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
        Diagnostics.installJavaCrashHandler(this);
        prefs = getSharedPreferences("lm", MODE_PRIVATE);
        Lang.load(this);
        GameSettings.applyTierDefaults(this, prefs);
        final String cleaned = GameSettings.cleanCachesIfNeeded(this, prefs);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        menu = new MenuView(this);
        menu.setListener(this);
        setContentView(menu);
        menu.requestFocus();
        if (cleaned != null) Lang.toast(this, "Old shader cache cleared (" + cleaned + "). The first minutes may load a little slower.", Toast.LENGTH_LONG).show();
        String auto = getIntent() != null ? getIntent().getStringExtra("autoplay") : null;
        if (auto != null) { menu.post(() -> play("dlc".equals(auto))); }
        else menu.post(this::startupChecks);
        new Thread(() -> SaveBackup.backupIfChanged(this, prefs), "SaveBackup").start();
    }

    // ---- crash prompt, Safe Mode and phone checks ----

    private void startupChecks() {
        if (isFinishing() || isDestroyed()) return;
        if (Diagnostics.consumeCrash(this)) {
            dialog().setTitle("The game closed unexpectedly")
                    .setMessage("Sorry about that. You can start in Safe Mode (lighter settings, no texture pack, fresh shader cache), "
                            + "and save a log: it says what went wrong and can be shared so it gets fixed.")
                    .setPositiveButton("Safe Mode", (d, w) -> {
                        GameSettings.enterSafeMode(this, prefs);
                        Lang.toast(this, "Safe Mode on. Turn it off in Options > Help.", Toast.LENGTH_LONG).show();
                    })
                    .setNeutralButton("Save log", (d, w) -> Diagnostics.share(this, prefs))
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        if (migrateLinkedGame()) return;
        if (!SaveBackup.hasSaves(this) && !prefs.getBoolean("restore_hint_shown", false)) {
            prefs.edit().putBoolean("restore_hint_shown", true).apply();
            dialog().setTitle("Welcome")
                    .setMessage("Your saves are backed up automatically to Download/Luigi's Mansion Android/ after you play, "
                            + "so they survive reinstalling the app.\n\nHad the game before? Restore your saves now.")
                    .setPositiveButton("Restore saves", (d, w) -> pickSaveBackup())
                    .setNegativeButton("Not now", null).show();
            return;
        }
        String key = "checked_" + Diagnostics.appVersion(this);
        if (prefs.getBoolean(key, false)) return;
        prefs.edit().putBoolean(key, true).apply();
        StringBuilder warn = new StringBuilder();
        if (DeviceTier.vulkanVersion(this) < 0x401000)
            warn.append("• This phone's graphics chip does not report Vulkan 1.1, which the game's graphics engine uses. "
                    + "It may not start, or may show a black screen.\n\n");
        long ram = DeviceTier.totalRamBytes(this);
        if (ram > 0 && ram < 3500L * 1024 * 1024)
            warn.append("• This phone has less than 4 GB of memory. Use the Low-end preset and close other apps.\n\n");
        try {
            long free = new android.os.StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes();
            if (free < 1024L * 1024 * 1024)
                warn.append("• Less than 1 GB of free storage. The game needs room for its shader cache and saves.\n\n");
        } catch (Throwable ignored) { }
        if (warn.length() > 0)
            dialog().setTitle("Before you play")
                    .setMessage(warn.toString().trim() + "\n\nIf the game has trouble, Options > Help > Save Log saves a report.")
                    .setPositiveButton("OK", null).show();
    }

    // ---- controllers work on every launcher screen, even when the screen has not got focus ----

    private View current;

    @Override public void setContentView(View v) {
        super.setContentView(v);
        current = v;
    }

    private static boolean fromPad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (source & InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        View v = current;
        if (v != null && fromPad(e.getSource()) && (v instanceof MenuView || v instanceof OptionsView || v instanceof FilesView)) {
            if (!v.hasFocus()) v.requestFocus();
            if (e.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B && !(v instanceof MenuView)) {
                if (e.getAction() == KeyEvent.ACTION_UP) onBackPressed();
                return true;
            }
            boolean handled = e.getAction() == KeyEvent.ACTION_DOWN ? v.onKeyDown(e.getKeyCode(), e)
                    : e.getAction() == KeyEvent.ACTION_UP && v.onKeyUp(e.getKeyCode(), e);
            if (handled) return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        View v = current;
        if (v != null && fromPad(e.getSource()) && (v instanceof MenuView || v instanceof OptionsView || v instanceof FilesView)
                && v.onGenericMotionEvent(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    static File findDiscStatic(android.content.Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) return null;
        File[] fs = d.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (isDisc(f)) return f;
        return null;
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
        return Lang.dialog(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    @Override public void onItem(int index) {
        switch (index) {
            case 0:
                menu.openPlayChoice();  // Play / Play DLC (Premium Deluxe) right under it
                break;
            case MenuView.PLAY_ORIGINAL: play(false); break;
            case MenuView.PLAY_DLC: play(true); break;
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

    /** True when a game file is set up: linked in place (preferred) or copied into the app. */
    private boolean hasGame() { return DiscLink.has(prefs) || findDisc() != null; }

    private void play() { play(false); }

    private void play(boolean dlc) {
        if (dlc && !Dlc.installed(this, prefs)) {
            dialog().setTitle(Dlc.NAME)
                    .setMessage("Install the DLC in Game Files first")
                    .setPositiveButton("Game Files", (d, w) -> showFiles())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (!hasGame()) {
            dialog().setTitle("No game file yet")
                    .setMessage("Open Game Files and choose your Luigi's Mansion disc image (.iso or .rvz) first.")
                    .setPositiveButton("Game Files", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { showFiles(); }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        String problem;
        if (DiscLink.has(prefs)) problem = DiscLink.check(this, prefs);
        else {
            File disc = findDisc();
            problem = DiscInstaller.check(disc, disc.length());
        }
        if (problem != null) {
            dialog().setTitle("This game file can't be played")
                    .setMessage(problem + "\n\nOpen Game Files to choose another copy of your disc.")
                    .setPositiveButton("Game Files", (d, w) -> showFiles())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        if (prefs.getBoolean("safe_mode", false))
            Lang.toast(this, "Safe Mode is on (lighter settings). Turn it off in Options > Help.", Toast.LENGTH_LONG).show();
        GameSettings.write(this, prefs);
        musicDone = true;
        stopMusic();
        String engine = dlc ? Dlc.ENGINE : "main";
        if (MyGame.loadedEngine != null && !MyGame.loadedEngine.equals(engine)) {
            // The other game engine is still loaded in this app process: restart the app, then start the game.
            restartAndPlay(dlc);
            return;
        }
        startActivity(new Intent(this, MyGame.class).putExtra(MyGame.EXTRA_DLC, dlc));
        finish();
    }

    /** Android can't unload a game engine: switching between the normal game and the DLC restarts the app process. */
    private void restartAndPlay(boolean dlc) {
        startActivity(new Intent(this, RestartActivity.class).putExtra("autoplay", dlc ? "dlc" : "main")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        overridePendingTransition(0, 0);
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
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
        String current;
        if (DiscLink.has(prefs)) {
            long sz = DiscLink.size(this, prefs);
            current = DiscLink.name(prefs) + (sz > 0 ? " (" + (sz / (1024 * 1024)) + " MB, played from your phone's storage)" : " (can't be opened: choose it again)");
        } else current = disc == null ? "No game installed" : disc.getName() + " (" + (disc.length() / (1024 * 1024)) + " MB, copy inside the app)";
        filesVisible = true;
        final boolean ready = hasGame();
        FilesView view = new FilesView(this, current, ready,
                Dlc.installed(this, prefs) ? "DLC installed: " + Dlc.NAME : null, new FilesView.Listener() {
            @Override public void onAction(int action) {
                if (action == 0) {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    startActivityForResult(intent, REQ_PICK);
                } else if (action == 1) {
                    if (!ready) {
                        Lang.toast(LauncherActivity.this, "Set up your USA game first (Install game)", Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog().setTitle("Install DLC: Premium Deluxe")
                            .setMessage("Choose the Premium Deluxe 2.2.1 patch: the .xdelta file, or the .zip from GameBanana.\n\n"
                                    + "The app makes the DLC from your own game file (about 220 MB, a few minutes). "
                                    + "Your game file is not changed. The DLC has its own saves.")
                            .setPositiveButton("Choose patch", (d, w) -> {
                                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                                intent.addCategory(Intent.CATEGORY_OPENABLE);
                                intent.setType("*/*");
                                startActivityForResult(intent, REQ_DLC);
                            })
                            .setNegativeButton("Cancel", null).show();
                } else closeFiles();
            }
        });
        setContentView(view);
        view.requestFocus();
    }
    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null)
            chooseImport(data.getData());
        if (req == REQ_DLC && res == RESULT_OK && data != null && data.getData() != null)
            installDlc(data.getData());
        if (req == REQ_SAVES && res == RESULT_OK && data != null && data.getData() != null)
            restoreSaves(data.getData());
        if (req == REQ_TEXPACK && res == RESULT_OK && data != null && data.getData() != null)
            importTexturePack(data.getData());
    }

    /** Game Files > choose: play the file where it is (default; no copy, survives reinstalling) or copy it in. */
    private void chooseImport(final Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0) name = c.getString(ni);
            }
        } catch (Exception ignored) { }
        final String shown = name == null ? "game file" : name;
        String low = shown.toLowerCase(Locale.ROOT);
        if (!low.endsWith(".iso") && !low.endsWith(".rvz")) {
            Lang.toast(this, "Please pick an .iso or .rvz file", Toast.LENGTH_LONG).show();
            return;
        }
        // Always copied into the port's own storage, so the original can be deleted afterwards.
        importFile(uri);
    }

    /** A game linked by an older version (played from where it was): copy it into the port once. */
    private boolean migrateLinkedGame() {
        if (!DiscLink.has(prefs) || findDisc() != null || installing) return false;
        String u = prefs.getString(DiscLink.KEY_URI, null);
        if (u == null || DiscLink.check(this, prefs) != null) return false;
        Lang.toast(this, "Copying your game into the port so the original file can be deleted", Toast.LENGTH_LONG).show();
        importFile(Uri.parse(u));
        return true;
    }

    private void linkFile(Uri uri, String name) {
        try {
            DiscLink.link(this, prefs, uri, name);
        } catch (Throwable t) {
            Lang.toast(this, "This file can't be kept linked; copying it into the app instead", Toast.LENGTH_LONG).show();
            importFile(uri);
            return;
        }
        String problem = DiscLink.check(this, prefs);
        if (problem != null) {
            DiscLink.unlink(this, prefs);
            dialog().setTitle("This game file can't be played").setMessage(problem).setPositiveButton("OK", null).show();
            return;
        }
        File f = findDisc();  // an older copy inside the app is no longer needed
        while (f != null && f.delete()) f = findDisc();
        showFiles();
        dialog().setTitle("Game ready")
                .setMessage("The game will be read from \"" + name + "\" where it is. Don't move or rename that file.")
                .setPositiveButton("Play", (d, w) -> play()).setNegativeButton("Done", null).show();
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
            Lang.toast(this, "Please pick an .iso or .rvz file", Toast.LENGTH_LONG).show();
            return;
        }
        final File dir = gameDir();
        if (dir == null) {
            Lang.toast(this, "No storage available", Toast.LENGTH_LONG).show();
            return;
        }
        if (size > 0 && dir.getUsableSpace() < size + 64L * 1024 * 1024) {
            Lang.toast(this, "Not enough free storage for this file", Toast.LENGTH_LONG).show();
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
                if(result==null) DiscLink.unlink(this,prefs);
                if(result==null) dialog().setTitle("Installation complete")
                    .setMessage("The game is installed in the port's storage and ready to play. "
                            + "You can delete the original file from your device now.")
                    .setPositiveButton("Play",(d,w)->play()).setNegativeButton("Done",null).show();
                else dialog().setTitle("Installation failed").setMessage(result)
                    .setPositiveButton("OK",null).show();
            });
        },"DiscInstaller").start();
    }

    // ---- DLC (Game Files > Install DLC) ----

    private void installDlc(final Uri patch) {
        if (installing) return;
        installing = true;
        final FilesView view = new FilesView(this, "Premium Deluxe", action -> { });
        view.setProgressText("Installing Premium Deluxe…", "Building the DLC from your own game file");
        view.setInstallProgress(0, 221_642_752L);
        setContentView(view);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            String error = null;
            android.os.ParcelFileDescriptor pfd = null;
            try {
                String src;
                if (DiscLink.has(prefs)) {
                    pfd = DiscLink.open(this, prefs);
                    if (pfd == null) throw new java.io.IOException("Your game file can't be opened. Choose it again in Game Files.");
                    src = DiscLink.path(pfd);
                } else {
                    File d = findDisc();
                    if (d == null) throw new java.io.IOException("No game file found.");
                    src = d.getAbsolutePath();
                }
                Dlc.install(this, prefs, src, patch, (done, total) -> runOnUiThread(() -> {
                    if (!isDestroyed()) view.setInstallProgress(done, total);
                }));
            } catch (Exception e) {
                error = e.getMessage() == null ? "The DLC could not be installed." : e.getMessage();
            } finally {
                if (pfd != null) try { pfd.close(); } catch (Exception ignored) { }
            }
            final String err = error;
            runOnUiThread(() -> {
                installing = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (isDestroyed()) return;
                showFiles();
                if (err == null) dialog().setTitle("DLC installed!")
                        .setMessage(Dlc.NAME + " is ready. On the main menu press Play, then Premium Deluxe.")
                        .setPositiveButton("Play DLC", (d, w) -> play(true)).setNegativeButton("Done", null).show();
                else dialog().setTitle("DLC install failed").setMessage(err).setPositiveButton("OK", null).show();
            });
        }, "DlcInstaller").start();
    }

    // ---- save backups (Options > Help) ----

    void pickSaveBackup() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, REQ_SAVES);
    }

    private void restoreSaves(Uri uri) {
        dialog().setTitle("Restore this save backup?")
                .setMessage("Your current save is kept aside inside the app first, then the backup replaces it.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Restore", (d, w) -> {
                    try {
                        int n = SaveBackup.restore(this, uri);
                        dialog().setTitle("Saves restored").setMessage(n + " save file(s) restored.").setPositiveButton("OK", null).show();
                    } catch (Exception e) {
                        dialog().setTitle("Restore failed").setMessage(e.getMessage() == null ? "Could not read this backup." : e.getMessage())
                                .setPositiveButton("OK", null).show();
                    }
                }).show();
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
            Lang.toast(this, "Please pick a .zip texture pack", Toast.LENGTH_LONG).show();
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
                    if (wait.isShowing()) wait.setMessage(Lang.t("Unpacking… " + files + " files"));
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
        heading.setText(Lang.t("TOUCH CONTROLS PREVIEW\nTap here or press Back to return"));
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
        else if (menu != null && menu.isChoiceOpen()) menu.closePlayChoice();
        else if (options != null) options.back();
        else super.onBackPressed();
    }
}

