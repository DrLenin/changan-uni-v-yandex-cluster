package com.test.clusterbridge;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Минимальный клиент протокола ADB к собственному adbd ГУ (127.0.0.1:5555, persist.adb.tcp.port).
// Нужен, чтобы после загрузки ГУ запустить helper.sh с правами оболочки: обычному приложению
// am display move-stack / am stack resize недоступны (docs/how-it-works.md). adbd здесь без RSA-ключа
// (ro.adb.secure пуст), но оболочка verify_sh спрашивает пароль — отдаём его в stdin.
final class AdbLocal {
    static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, AUTH = 0x48545541;

    static String shell(String cmd, String password) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
            s.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes("UTF-8"));
            Msg m = read(in);
            if (m.cmd == AUTH) throw new IOException("adbd требует ключ (AUTH)");
            if (m.cmd != CNXN) throw new IOException("adbd ответил " + Integer.toHexString(m.cmd));
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\0").getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            boolean pwSent = false;
            while (true) {
                m = read(in);
                if (m.cmd == OKAY && !pwSent) {
                    send(out, WRTE, 1, m.arg0, (password + "\n").getBytes("UTF-8"));
                    pwSent = true;
                } else if (m.cmd == WRTE) {
                    sb.append(new String(m.data, "UTF-8"));
                    send(out, OKAY, 1, m.arg0, new byte[0]);
                } else if (m.cmd == CLSE) {
                    return sb.toString();
                }
            }
        }
    }

    static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;  // версия протокола 0x01000000 — adbd сверяет сумму байт
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    static Msg read(DataInputStream in) throws IOException {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }
}
