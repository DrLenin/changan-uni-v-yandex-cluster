package com.test.clusterbridge;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

// Подсказка с видимого Яндекс Навигатора: уведомление он заполняет только скрытым, а на экране
// панель манёвра доступна службе спецвозможностей (Яндекс 31.x): image_maneuverballoon_maneuver
// с описанием «Поверните направо», text_maneuverballoon_distance + _metrics, text_nextstreet.
// Служба подписана только на пакет Яндекса (res/xml/a11y.xml).
public class YaA11y extends AccessibilityService {
    static final String ID = "ru.yandex.yandexnavi:id/";
    static volatile String dir, dist, street;
    static volatile YaA11y self;

    static boolean active() { return self != null; }

    // Панель Яндекса прочитана: Яндекс сейчас на экране с маршрутом.
    static boolean fresh() { return dir != null; }

    // Мост опрашивает раз в секунду: на стоящей машине Яндекс не шлёт событий, а после возврата
    // карты с приборки данные иначе не обновились бы.
    static void poll() {
        YaA11y s = self;
        if (s != null) s.read();
    }

    @Override
    protected void onServiceConnected() {
        self = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        read();
    }

    void read() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !BridgeService.YA.equals(String.valueOf(root.getPackageName()))) {
            if (dir != null) {
                dir = null;
                BridgeService.refresh();
            }
            return;
        }
        AccessibilityNodeInfo img = first(root, "image_maneuverballoon_maneuver");
        if (img == null || img.getContentDescription() == null) {
            if (dir != null) {
                dir = null;
                BridgeService.refresh();
            }
            return;
        }
        String d = img.getContentDescription().toString();
        String num = text(root, "text_maneuverballoon_distance"), unit = text(root, "text_maneuverballoon_metrics");
        String ds = num == null ? null : (num + (unit == null ? "" : unit)).trim();
        String st = text(root, "text_nextstreet");
        boolean changed = !d.equals(dir) || (ds != null && !ds.equals(dist)) || (st != null && !st.equals(street));
        dir = d;
        dist = ds;
        street = st;
        if (changed) BridgeService.refresh();
    }

    static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> l = root.findAccessibilityNodeInfosByViewId(ID + id);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    static String text(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo n = first(root, id);
        return n == null || n.getText() == null ? null : n.getText().toString();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        self = null;
        super.onDestroy();
    }
}
