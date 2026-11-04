package it.poc.keytester;

import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

/** Funzioni di formattazione degli eventi. */
public final class KeyUtil {

    private KeyUtil() {
    }

    public static String hex(int v) {
        return "0x" + Integer.toHexString(v);
    }

    public static String describe(KeyEvent e) {
        String act;
        switch (e.getAction()) {
            case KeyEvent.ACTION_DOWN: act = "DOWN"; break;
            case KeyEvent.ACTION_UP:   act = "UP  "; break;
            default:                   act = "MULTI";
        }
        InputDevice d = e.getDevice();
        String dev = d == null ? "?" : d.getName();
        StringBuilder sb = new StringBuilder();
        sb.append(act)
          .append(" code=").append(e.getKeyCode())
          .append(' ').append(KeyEvent.keyCodeToString(e.getKeyCode()))
          .append(" scan=").append(e.getScanCode());
        if (e.getRepeatCount() > 0) sb.append(" HOLD");
        if (e.isLongPress()) sb.append(" LONG");
        sb.append(" dev='").append(dev).append("'(").append(e.getDeviceId()).append(')')
          .append(" src=").append(hex(e.getSource()))
          .append(" flags=").append(hex(e.getFlags()));
        return sb.toString();
    }

    public static String describe(MotionEvent e) {
        InputDevice d = e.getDevice();
        String dev = d == null ? "?" : d.getName();
        return MotionEvent.actionToString(e.getAction())
                + " SCROLL=" + e.getAxisValue(MotionEvent.AXIS_SCROLL)
                + " VSCROLL=" + e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                + " HSCROLL=" + e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                + " dev='" + dev + "'(" + e.getDeviceId() + ")"
                + " src=" + hex(e.getSource());
    }

    public static String extras(Bundle b) {
        if (b == null || b.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" extras{");
        for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(' ').append(k).append('=');
            if (v instanceof KeyEvent) sb.append('[').append(describe((KeyEvent) v)).append(']');
            else sb.append(v);
        }
        return sb.append(" }").toString();
    }
}
