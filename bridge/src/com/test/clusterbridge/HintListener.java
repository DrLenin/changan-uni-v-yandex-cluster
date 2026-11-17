package com.test.clusterbridge;

import android.app.Notification;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

// Подсказка из уведомления Яндекса (заполнено, только пока Яндекс не на экране): title — расстояние («2,84 км»),
// text — направление или улица («налево», «улица Ленина») либо событие («Камера контроля скорости»).
// Стрелку берём картинкой из разметки уведомления (RemoteViews) — это стрелка самого Яндекса.
public class HintListener extends NotificationListenerService {
    static final String TAG = BridgeService.TAG;

    @Override
    public void onListenerConnected() {
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!BridgeService.YA.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        Bundle e = n.extras;
        CharSequence t = e.getCharSequence("android.title"), x = e.getCharSequence("android.text");
        if (t == null || x == null || !t.toString().trim().matches("[\\d.,\\s]+(км|м)")) {
            BridgeService.hintDist = null;
            BridgeService.hintText = null;
            BridgeService.alert = null;
            BridgeService.arrow = null;
            BridgeService.refresh();
            return;
        }
        String dist = t.toString().trim(), what = x.toString();
        if (what.toLowerCase().matches(".*(камер|переезд|дтп|ремонт|лежач|полицейск|пешеход|опасн|школ|ограничен).*")) {
            // Ближайшее событие вместо манёвра: стрелку последнего манёвра оставляем, его расстояние уже неизвестно.
            BridgeService.hintDist = null;
            BridgeService.alert = what + " " + dist;
        } else {
            BridgeService.hintDist = dist;
            BridgeService.hintText = what;
            BridgeService.alert = null;
            Bitmap a = arrowFrom(n);
            if (a != null) BridgeService.arrow = a;
        }
        BridgeService.refresh();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (!BridgeService.YA.equals(sbn.getPackageName())) return;
        BridgeService.hintDist = null;
        BridgeService.hintText = null;
        BridgeService.alert = null;
        BridgeService.arrow = null;
        BridgeService.refresh();
    }

    // Разворачиваем разметку уведомления у себя и берём картинку-стрелку, перекрашенную в белый.
    // Имена картинки манёвра по версиям Яндекса: 17.x — nextManeuverView[Tinted] (развёрнутая разметка),
    // 31.x — primaryIcon[Tinted] (свёрнутая). Своя картинка важнее общего «Tinted»-значка.
    static final String[] ARROW_IDS = {"nextManeuverView", "primaryIcon", "nextManeuverViewTinted", "primaryIconTinted"};

    Bitmap arrowFrom(Notification n) {
        try {
            Resources yr = createPackageContext(BridgeService.YA, 0).getResources();
            List<ImageView> imgs = new ArrayList<>();
            for (RemoteViews rv : new RemoteViews[]{n.contentView, n.bigContentView})
                if (rv != null) walk(rv.apply(this, new FrameLayout(this)), imgs);
            ImageView best = null;
            for (String id : ARROW_IDS) {
                for (ImageView iv : imgs) if (idName(iv, yr).equals(id)) { best = iv; break; }
                if (best != null) break;
            }
            if (best == null) return null;
            Drawable d = best.getDrawable().mutate();
            int w = Math.max(d.getIntrinsicWidth(), 1), h = Math.max(d.getIntrinsicHeight(), 1);
            float k = 256f / Math.max(w, h);
            Bitmap b = Bitmap.createBitmap(Math.round(w * k), Math.round(h * k), Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, b.getWidth(), b.getHeight());
            d.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
            d.draw(new Canvas(b));
            return b;
        } catch (Throwable th) {
            Log.e(TAG, "разметка уведомления не разобрана: " + th);
            return null;
        }
    }

    static void walk(View v, List<ImageView> imgs) {
        if (v instanceof ImageView && ((ImageView) v).getDrawable() != null && v.getVisibility() == View.VISIBLE)
            imgs.add((ImageView) v);
        if (v instanceof ViewGroup)
            for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) walk(((ViewGroup) v).getChildAt(i), imgs);
    }

    static String idName(View v, Resources yr) {
        try {
            return v.getId() == View.NO_ID ? "?" : yr.getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "#" + Integer.toHexString(v.getId());
        }
    }
}
