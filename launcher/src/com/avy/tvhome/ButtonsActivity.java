package com.avy.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Target of "Configuration" in Accessibility settings: opens the Remote buttons screen on the home screen. */
public class ButtonsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        startActivity(new Intent(this, HomeActivity.class)
                .putExtra("open", "buttons")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
