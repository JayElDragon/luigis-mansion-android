package org.libsdl.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Runs in its own little process (":restart"). The main app process has just closed itself so a different
 * game engine can be loaded; this waits a moment, opens the start menu again (which then starts the game), and ends.
 */
public class RestartActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        final Intent next = new Intent(this, LauncherActivity.class)
                .putExtra("autoplay", getIntent().getStringExtra("autoplay"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            startActivity(next);
            finish();
            overridePendingTransition(0, 0);
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                    () -> android.os.Process.killProcess(android.os.Process.myPid()), 500);
        }, 350);
    }
}
