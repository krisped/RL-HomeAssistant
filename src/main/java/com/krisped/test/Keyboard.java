package com.krisped;

import com.sun.jna.Native;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.net.httpserver.HttpServer;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;

public class Keyboard
{
    /* JNA-grensesnitt mot user32.dll */
    public interface User32 extends StdCallLibrary
    {
        User32 INSTANCE = Native.load("user32", User32.class);
        void keybd_event(byte bVk, byte bScan, int dwFlags, int dwExtraInfo);
    }

    private static final int KEYEVENTF_KEYUP = 0x0002;

    /* Streng → VK-kode */
    private static final Map<String,Integer> KEY_MAP = Map.of(
            "M",  KeyEvent.VK_M,
            "R",  KeyEvent.VK_R,
            "F1", KeyEvent.VK_F1,
            "F2", KeyEvent.VK_F2,
            "1",  KeyEvent.VK_1,
            "2",  KeyEvent.VK_2
            // legg til flere om du vil
    );

    /** Send fysisk tastetrykk. */
    public static void pressKey(int vk)
    {
        User32.INSTANCE.keybd_event((byte) vk, (byte) 0, 0, 0);
        try { Thread.sleep(30 + (int)(Math.random()*20)); } catch (InterruptedException ignored) {}
        User32.INSTANCE.keybd_event((byte) vk, (byte) 0, KEYEVENTF_KEYUP, 0);
    }

    /** Start HTTP-server: GET /press?key=X */
    public static HttpServer start(String host, int port) throws IOException
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);

        server.createContext("/press", exchange -> {
            String q = exchange.getRequestURI().getQuery();   // f.eks. key=M
            String resp;
            int code;

            if (q != null && q.startsWith("key="))
            {
                String key = q.substring(4).toUpperCase();
                Integer vk = KEY_MAP.get(key);
                if (vk != null)
                {
                    pressKey(vk);
                    resp = "Pressed " + key;
                    code = 200;
                }
                else {
                    resp = "Unknown key: " + key;
                    code = 400;
                }
            }
            else {
                resp = "Missing key parameter";
                code = 400;
            }

            exchange.sendResponseHeaders(code, resp.length());
            try (OutputStream os = exchange.getResponseBody()) { os.write(resp.getBytes()); }
        });

        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        return server;
    }
}
