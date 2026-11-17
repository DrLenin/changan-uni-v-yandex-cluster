package com.test.clusterbridge;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

// Калибровочная сетка: клетки 80 px с подписями «буква столбца + номер строки».
// По видимым на приборке подписям находим, какую часть кадра дисплея 1 она показывает.
public class GridActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(new GridView(this));
    }

    static class GridView extends View {
        final Paint fill = new Paint();
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridView(Context c) {
            super(c);
            text.setTextSize(34);
            text.setFakeBoldText(true);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            final int cell = 80;
            for (int y = 0, r = 1; y < getHeight(); y += cell, r++) {
                for (int x = 0, col = 0; x < getWidth(); x += cell, col++) {
                    boolean dark = (r + col) % 2 == 0;
                    fill.setColor(dark ? Color.rgb(25, 55, 150) : Color.rgb(255, 225, 120));
                    c.drawRect(x, y, x + cell, y + cell, fill);
                    text.setColor(dark ? Color.WHITE : Color.BLACK);
                    c.drawText("" + (char) ('A' + col) + r, x + cell / 2f, y + cell / 2f + 12, text);
                }
            }
        }
    }
}
