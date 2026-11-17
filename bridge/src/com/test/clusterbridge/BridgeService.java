package com.test.clusterbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

// Мост «Яндекс Навигатор → приборка Changan UNI-V» (docs/how-it-works.md).
// 1) держит у ГУ статус «навигация идёт, навигатор в фоне» (EXTRA_STATE 12 и 4) — без него приборка не открывает Normal/En Navi;
// 2) подписывается на запрос видео от приборки (onRequestLVDS) и отвечает за навигатор (KEY_TYPE 10117) — без ответа приборка закрывает режим;
// 3) рисует на дисплее 1 (видеовход приборки) стрелку манёвра с расстоянием в видимом окне приборки.
// Подсказку берёт из уведомления Яндекса: оно заполнено, только пока Яндекс не на экране.
// Снимать панель Яндекса с основного экрана нельзя: системный экран разрешения выдаёт проекцию-«презентацию» без зеркала (docs/how-it-works.md).
public class BridgeService extends Service {
    static final String TAG = "ClusterBridge";
    static final String YA = "ru.yandex.yandexnavi";
    static final String BR = "AUTONAVI_STANDARD_BROADCAST_SEND";
    static final String MGR = "com.incall.serversdk.interactive.IDouInteractiveManager";
    static final String CB = "com.incall.serversdk.interactive.callback.INavigateCallback";
    static final int TX_REGISTER_NAVIGATE_CALLBACK = 29;
    static final int TX_SEND_SLIDING_EVENT = 21;
    static final String TOUCH = "android.view.IWindowTouchCallback";

    // Видимое окно Normal Navi в кадре дисплея 1 1920×720 (калибровка сеткой).
    // En Navi показывает полную карту: Яндекс переносит на приборку помощник helper.sh (нужны права оболочки ADB).
    static final Rect NORMAL = new Rect(160, 240, 480, 560);
    // Приборка в En Navi, а карта на основном экране — та же стрелка по центру видимой части (0–1920 × 0–640).
    static final Rect EN_CENTER = new Rect(800, 160, 1120, 480);

    // Подсказка из уведомления Яндекса (пишет HintListener).
    static volatile String hintDist, hintText, alert;
    static volatile android.graphics.Bitmap arrow;  // стрелка Яндекса из разметки уведомления
    static volatile BridgeService self;

    final Handler h = new Handler(Looper.getMainLooper());
    IBinder svc;
    boolean enMap;  // Яндекс сейчас на приборке (En Navi)
    boolean enConfirmed;  // приборка уже сообщила статус 3 (En Navi) после переноса карты
    volatile int ipStatus;  // последний статус приборки: 0 — без навигации, 2 — Normal Navi, 3 — En Navi
    boolean manualOff;  // карту убрали жестом, а приборка ещё в En Navi
    PanelView view;

    final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == 1 || code == 2) {
                data.enforceInterface(CB);
                final int v = data.readInt();
                if (code == 1) h.post(new Runnable() { public void run() { onRequestLvds(v); } });
                else h.post(new Runnable() { public void run() { onIpStatus(v); } });
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    // Жест тремя-четырьмя пальцами: та же подписка в PhoneWindowManager, что у штатного WindowGestureManager
    // (список подписчиков, штатного не вытесняем). Транзакции: 3 — свайп от правого края, 4 — от левого, аргумент — число пальцев.
    final Binder touch = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code >= 1 && code <= 5) {
                data.enforceInterface(TOUCH);
                if (code == 3 || code == 4) {
                    final int fingers = data.readInt();
                    final boolean toCluster = code == 3;
                    h.post(new Runnable() { public void run() { onSwipe(toCluster, fingers); } });
                }
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    final ServiceConnection conn = new ServiceConnection() {
        public void onServiceConnected(ComponentName n, IBinder b) {
            svc = b;
            register();
        }
        public void onServiceDisconnected(ComponentName n) {
            svc = null;
            Log.w(TAG, "сервис ГУ отключён");
        }
    };

    final Runnable stateLoop = new Runnable() {
        public void run() {
            state(12);
            state(4);
            h.postDelayed(this, 20000);
        }
    };

    final Runnable a11yPoll = new Runnable() {
        public void run() {
            YaA11y.poll();
            h.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        self = this;
        callback.attachInterface(null, CB);
        touch.attachInterface(null, TOUCH);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("bridge", "ClusterBridge", NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new Notification.Builder(this, "bridge")
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentTitle("Навигация на приборку").build());
        Intent i = new Intent("com.incall.double.INTERACTIVE_SERVICE");
        i.setPackage("com.incall.interactive");
        Log.i(TAG, "bind сервиса ГУ: " + bindService(i, conn, BIND_AUTO_CREATE));
        addOverlay();
        registerSwipes();
        startHelper();
        h.post(stateLoop);
        h.post(a11yPoll);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Проверка без приборки: am start -n com.test.clusterbridge/.MainActivity --ei show 1
        if (intent != null && intent.hasExtra("show") && view != null) {
            view.setVisibility(View.VISIBLE);
            view.invalidate();
        }
        return START_STICKY;
    }

    void register() {
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MGR);
            d.writeStrongBinder(callback);
            svc.transact(TX_REGISTER_NAVIGATE_CALLBACK, d, r, 0);
            r.readException();
            Log.i(TAG, "подписка на запрос видео приборки оформлена");
        } catch (Throwable t) {
            Log.e(TAG, "подписка не удалась: " + t);
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    // Помощник helper.sh (права оболочки) запускаем через собственный adbd ГУ; повторный запуск helper.sh гасит сам.
    // После загрузки adbd может подняться позже моста — несколько попыток.
    void startHelper() {
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 10; i++) {
                    try {
                        // sleep: дать фоновому процессу отсоединиться (setsid), пока adbd не закрыл сессию вместе с её процессами.
                        String out = AdbLocal.shell("(setsid nohup sh /data/local/tmp/helper.sh >/dev/null 2>&1 </dev/null &); sleep 2; echo started", "adb36987");
                        if (out.contains("started")) {
                            Log.i(TAG, "помощник запущен через локальный ADB");
                            return;
                        }
                        Log.w(TAG, "локальный ADB ответил: " + out.trim());
                    } catch (Throwable t) {
                        Log.w(TAG, "локальный ADB недоступен (попытка " + (i + 1) + "): " + t);
                    }
                    try { Thread.sleep(15000); } catch (InterruptedException e) { return; }
                }
            }
        }, "helper-start").start();
    }

    // Интерфейса IWindowTouchCallback нет в SDK — регистрируем через Proxy, который отдаёт наш Binder.
    void registerSwipes() {
        try {
            Class<?> itf = Class.forName(TOUCH);
            Object cb = java.lang.reflect.Proxy.newProxyInstance(itf.getClassLoader(), new Class<?>[]{itf},
                    new java.lang.reflect.InvocationHandler() {
                        public Object invoke(Object p, java.lang.reflect.Method m, Object[] a) {
                            switch (m.getName()) {
                                case "asBinder": return touch;
                                case "equals": return p == a[0];
                                case "hashCode": return System.identityHashCode(p);
                                case "toString": return "ClusterBridgeTouch";
                                default: return null;
                            }
                        }
                    });
            WindowManager wm = getSystemService(WindowManager.class);
            wm.getClass().getMethod("registerWindowTouchCallback", itf).invoke(wm, cb);
            Log.i(TAG, "подписка на жесты оформлена");
        } catch (Throwable t) {
            Log.e(TAG, "подписка на жесты не удалась: " + t);
        }
    }

    // Свайп к приборке (от правого края влево) — карту на приборку; обратно — карту с приборки, на ней маленькая стрелка.
    // Режим самой приборки жест не меняет: на событие штатного жеста (sendSlidingEvent) она не отвечает
    // (тесты 16.11), режим выбирается меню на руле. Поэтому жест работает, когда приборка в En Navi.
    void onSwipe(boolean toCluster, int fingers) {
        if (fingers < 3) return;
        Log.i(TAG, "жест " + fingers + " пальцами " + (toCluster ? "к приборке" : "к экрану") + ", статус приборки " + ipStatus);
        if (toCluster) {
            manualOff = false;
            if (ipStatus == 3) mapToCluster();
            else slide(1, 0);  // как штатный жест; приборка на него пока не отвечала
        } else if (enMap) {
            manualOff = true;  // пока приборка в En Navi, статус 3 карту обратно не возвращает
            mapOff();
        }
    }

    void slide(int from, int type) {
        if (svc == null) return;
        Parcel d = Parcel.obtain(), r = Parcel.obtain();
        try {
            d.writeInterfaceToken(MGR);
            d.writeInt(from);
            d.writeInt(type);
            svc.transact(TX_SEND_SLIDING_EVENT, d, r, 0);
            r.readException();
        } catch (Throwable t) {
            Log.e(TAG, "sendSlidingEvent: " + t);
        } finally {
            d.recycle();
            r.recycle();
        }
    }

    void state(int s) {
        sendBroadcast(new Intent(BR).putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", s));
    }

    // Приборка просит видео: 1 — Normal Navi, 3 — En Navi, 7 — AR, 5/6 — камеры (не наше).
    void onRequestLvds(int code) {
        Log.i(TAG, "приборка запросила видео, code=" + code);
        final int m;
        if (code == 1) m = 1;
        else if (code == 3) m = 2;
        else if (code == 7) m = 3;
        else {
            if (view != null) view.setVisibility(View.GONE);  // видео камер рисует не мост — не перекрываем
            return;
        }
        if (code == 3) {
            manualOff = false;
            mapToCluster();
        } else {
            manualOff = true;  // сразу после запроса Normal ещё приходит прежний статус 3 — карту по нему не везём
            mapOff();
        }
        // Ответ за навигатор после того, как ГУ запомнит запрос (bU) — иначе ГУ его проигнорирует.
        h.postDelayed(new Runnable() {
            public void run() {
                sendBroadcast(new Intent(BR).putExtra("KEY_TYPE", 10117).putExtra("EXTSCREEN_STATUS_INFO", 0)
                        .putExtra("EXTSCREEN_WINDOW_INFO", 1001).putExtra("EXTSCREEN_MODE_INFO", m));
                Log.i(TAG, "ответ 10117 mode=" + m);
            }
        }, 500);
    }

    // Статус приборки: 0 — без навигации, 2 — Normal Navi, 3 — En Navi (лог теста 16.11).
    // Выход из En Navi — только после того, как приборка сама подтвердила 3: сразу после запроса
    // ещё приходит прежний статус (0 или 2), и карта уезжала обратно в ту же секунду.
    void onIpStatus(int v) {
        if (v != ipStatus) {
            Log.i(TAG, "статус приборки " + ipStatus + " → " + v);
            if (view != null) view.postInvalidate();  // стрелка меняет место: Normal ↔ центр En
        }
        ipStatus = v;
        if (v == 3) {
            // Из Normal в En приборка переходит без запроса видео (видео уже включено) — карту везём по статусу.
            if (enMap) enConfirmed = true;
            else if (!manualOff) {
                mapToCluster();
                enConfirmed = true;
            }
        } else {
            manualOff = false;
            // Сразу после запроса En ещё приходит прежний статус — выходим только после подтверждённого 3.
            if (enMap && enConfirmed) mapOff();
        }
    }

    // Сигнал помощнику helper.sh: он читает эти строки лога и переносит Яндекс между дисплеями.
    void setEnMap(boolean on) {
        if (on == enMap) return;
        enMap = on;
        enConfirmed = false;
        // show: служба спецвозможностей читает подсказку с видимого Яндекса — прятать его под прежнее окно не нужно.
        Log.i(TAG, on ? "EN_MAP on" : YaA11y.active() ? "EN_MAP off show" : "EN_MAP off");
    }

    // Карта на приборку: своё окно прячем, Яндекс переносит helper.sh.
    void mapToCluster() {
        if (view != null) view.setVisibility(View.GONE);
        setEnMap(true);
    }

    // Карта с приборки: Яндекс возвращается на основной экран (без службы спецвозможностей — под прежнее окно,
    // иначе нет подсказок); на приборке — маленькая стрелка, как в Normal Navi.
    void mapOff() {
        setEnMap(false);
        if (view != null) {
            view.setVisibility(View.VISIBLE);
            view.invalidate();
        }
    }

    void addOverlay() {
        Display d1 = null;
        for (Display d : getSystemService(DisplayManager.class).getDisplays())
            if (d.getDisplayId() != Display.DEFAULT_DISPLAY && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) d1 = d;
        if (d1 == null) {
            Log.e(TAG, "дисплей приборки не найден");
            return;
        }
        Context dc = createDisplayContext(d1);
        view = new PanelView(dc);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.OPAQUE);
        try {
            dc.getSystemService(WindowManager.class).addView(view, lp);
            Log.i(TAG, "окно моста на дисплее " + d1.getDisplayId());
        } catch (Throwable t) {
            view = null;
            Log.e(TAG, "окно на дисплее приборки не создано (нужно appops SYSTEM_ALERT_WINDOW allow): " + t);
        }
    }

    static void refresh() {
        BridgeService s = self;
        if (s != null && s.view != null) s.view.postInvalidate();
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { unbindService(conn); } catch (Throwable ignored) {}
        self = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Окно на дисплее 1: чёрный фон, в видимом окне приборки — стрелка с расстоянием.
    class PanelView extends View {
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        PanelView(Context c) {
            super(c);
            line.setColor(Color.WHITE);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
            line.setStrokeJoin(Paint.Join.ROUND);
            fillP.setColor(Color.WHITE);
            text.setColor(Color.WHITE);
            text.setFakeBoldText(true);
            text.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.BLACK);
            String al = alert;
            if (hintText != null || al != null) {
                // Яндекс скрыт: подсказка из уведомления, стрелка — картинкой самого Яндекса.
                String what = hintText;
                boolean isDir = what != null && what.toLowerCase().matches(".*(налев|направ|прям|развор|левее|правее).*");
                drawHint(c, rect(), arrow, what, hintDist, isDir ? null : what, al);
            } else if (YaA11y.fresh()) {
                // Яндекс на экране: подсказка из его окна, стрелка — по описанию картинки манёвра.
                drawHint(c, rect(), null, YaA11y.dir, YaA11y.dist, YaA11y.street, null);
            }
        }

        Rect rect() { return ipStatus == 3 ? EN_CENTER : NORMAL; }

        // Стрелка (картинка Яндекса или своя по тексту направления), под ней расстояние, улица и предупреждение.
        void drawHint(Canvas c, Rect dst, android.graphics.Bitmap bmp, String dir, String dist, String street, String al) {
            if (al != null) fitText(c, al, dst, dst.top + dst.height() * 0.97f, dst.height() * 0.09f);
            if (dir == null && bmp == null) return;
            float s = Math.min(dst.width(), dst.height()) * 0.50f;
            float cx = dst.centerX(), cy = dst.top + dst.height() * 0.30f;
            if (bmp != null) {
                c.drawBitmap(bmp, null, new android.graphics.RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), null);
            } else {
                line.setStrokeWidth(s / 7);
                String t = dir.toLowerCase();
                Path pth = new Path();
                float[] tip;
                if (t.contains("развор") || t.contains("развер")) {
                    pth.moveTo(cx + s / 4, cy + s * 0.38f);
                    pth.lineTo(cx + s / 4, cy - s / 6);
                    pth.quadTo(cx, cy - s / 2, cx - s / 4, cy - s / 6);
                    pth.lineTo(cx - s / 4, cy + s / 4);
                    tip = new float[]{cx - s / 4, cy - s / 6, cx - s / 4, cy + s / 4};
                } else if (t.contains("лев")) {
                    boolean slight = t.contains("левее");
                    pth.moveTo(cx + s / 5, cy + s * 0.38f);
                    pth.lineTo(cx + s / 5, cy);
                    float ex = cx - s / 2, ey = slight ? cy - s / 2 : cy;
                    pth.lineTo(ex, ey);
                    tip = new float[]{cx + s / 5, cy, ex, ey};
                } else if (t.contains("прав")) {
                    boolean slight = t.contains("правее");
                    pth.moveTo(cx - s / 5, cy + s * 0.38f);
                    pth.lineTo(cx - s / 5, cy);
                    float ex = cx + s / 2, ey = slight ? cy - s / 2 : cy;
                    pth.lineTo(ex, ey);
                    tip = new float[]{cx - s / 5, cy, ex, ey};
                } else {
                    pth.moveTo(cx, cy + s * 0.38f);
                    pth.lineTo(cx, cy - s / 2);
                    tip = new float[]{cx, cy, cx, cy - s / 2};
                }
                c.drawPath(pth, line);
                arrowHead(c, tip[0], tip[1], tip[2], tip[3], s / 3);
            }
            if (dist != null) fitText(c, dist, dst, dst.top + dst.height() * 0.68f, dst.height() * 0.15f);
            if (street != null) fitText(c, street, dst, dst.top + dst.height() * 0.84f, dst.height() * 0.09f);
        }

        void fitText(Canvas c, String s, Rect dst, float y, float size) {
            text.setTextSize(size);
            float w = text.measureText(s);
            if (w > dst.width() * 0.95f) text.setTextSize(size * dst.width() * 0.95f / w);
            c.drawText(s, dst.centerX(), y, text);
        }

        void arrowHead(Canvas c, float x1, float y1, float x2, float y2, float len) {
            double a = Math.atan2(y2 - y1, x2 - x1);
            Path head = new Path();
            head.moveTo((float) (x2 + Math.cos(a) * len / 2), (float) (y2 + Math.sin(a) * len / 2));
            head.lineTo((float) (x2 + Math.cos(a + 2.4) * len), (float) (y2 + Math.sin(a + 2.4) * len));
            head.lineTo((float) (x2 + Math.cos(a - 2.4) * len), (float) (y2 + Math.sin(a - 2.4) * len));
            head.close();
            c.drawPath(head, fillP);
        }
    }
}
