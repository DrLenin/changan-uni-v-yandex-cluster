package com.test.clusterbridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

// Прозрачный запуск моста: иконкой и после загрузки ГУ.
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent s = new Intent(this, BridgeService.class);
        if (getIntent().hasExtra("show")) s.putExtra("show", getIntent().getIntExtra("show", 1));
        startForegroundService(s);
        finish();
    }
}
