package it.poc.keytester;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * POC Key Tester – diagnostica dei tasti fisici per radio PoC Android (Hytera P50 Pro).
 * Registra: KeyEvent [APP], tasti globali via accessibilità [ACC], eventi manopola [MOT],
 * broadcast intent [INT], righe di logcat di sistema [LOG], stringhe dei manifest [SCAN],
 * dispositivi di input e tasti dichiarati [DEV].
 * Tutti i tasti vengono "catturati"; per uscire premere BACK 3 volte entro 2 secondi.
 */
public class MainActivity extends Activity {

    /** Azioni note usate da vari terminali PoC (Zello, generiche, HyTalk-compatibili). */
    private static final String[] CANDIDATES = {
            "com.zello.ptt.down", "com.zello.ptt.up",
            "com.zello.intent.buttonExtra1.down", "com.zello.intent.buttonExtra1.up",
            "com.zello.intent.buttonSOS.down", "com.zello.intent.buttonSOS.up",
            "com.zello.intent.channelUp", "com.zello.intent.channelDown",
            "com.zello.intent.setChannel",
            "android.intent.action.PTT_DOWN", "android.intent.action.PTT_UP",
            "android.intent.action.PTT.down", "android.intent.action.PTT.up",
            "android.intent.action.ACTION_PTT_BUTTON_DOWN", "android.intent.action.ACTION_PTT_BUTTON_UP",
            "android.intent.action.PTT_BUTTON",
            "android.intent.action.CHANNELUP.down", "android.intent.action.CHANNELDOWN.down",
            "android.intent.action.CHANNELUP.up", "android.intent.action.CHANNELDOWN.up",
            "android.intent.action.SOS.down", "android.intent.action.SOS.up",
            "com.kodiak.intent.action.PTT_BUTTON",
    };

    /** Parole chiave per selezionare le stringhe interessanti nei manifest. */
    private static final String[] SCAN_KEYWORDS = {
            "ptt", "knob", "channel", "sos", "emergency", "sidekey", "side_key",
            "keyevent", "key_event", "key.", "_key", "rotary", "talk", "volume"
    };
    private static final String[] SCAN_EXCLUDE = {
            "keyguard", "keystore", "keychain", "apikey", "api_key", "talkback", "notification_channel"
    };
    private static final Pattern ACTION_LIKE =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_\\-]+)+");

    /** Parole chiave per filtrare il logcat di sistema. */
    private static final String[] LOG_KEYWORDS = {
            "ptt", "knob", "keycode", "sidekey", "side_key", "rotary", "interceptkey", "emergency"
    };

    private static final int MAX_LINES = 300;

    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final Set<String> actions = new TreeSet<>();
    private final List<Long> backTimes = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextView header;
    private TextView logView;
    private BroadcastReceiver receiver;
    private Process logcatProc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogBus.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        LogBus.setListener(this::appendLine);

        LogBus.add("=== POC Key Tester avviato ===");
        LogBus.add("[INFO] " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE
                + ") Android " + Build.VERSION.RELEASE + " API " + Build.VERSION.SDK_INT
                + " build " + Build.DISPLAY);
        printInputDevices();

        actions.addAll(Arrays.asList(CANDIDATES));
        registerActions();
        startLogcat();
        new Thread(this::scanApks, "scan").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHeader();
    }

    @Override
    protected void onDestroy() {
        LogBus.setListener(null);
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
        if (logcatProc != null) logcatProc.destroy();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- interfaccia

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(6, 6, 6, 6);

        header = new TextView(this);
        header.setTextColor(Color.YELLOW);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        logView = new TextView(this);
        logView.setTextColor(Color.GREEN);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateHeader();
    }

    private void updateHeader() {
        if (header == null) return;
        boolean acc = isAccessibilityOn();
        boolean logs = checkSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
        header.setText("Premi i tasti uno alla volta. BACK x3 = esci\n"
                + "Accessibilità: " + (acc ? "ON" : "OFF")
                + "   READ_LOGS: " + (logs ? "SI" : "NO") + "\n"
                + "File: " + LogBus.path());
    }

    private void appendLine(String line) {
        lines.addFirst(line);              // la riga più recente in alto
        while (lines.size() > MAX_LINES) lines.removeLast();
        logView.setText(String.join("\n", lines));
    }

    private boolean isAccessibilityOn() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName());
    }

    // ---------------------------------------------------------------- tasti

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getRepeatCount() <= 1) LogBus.add("[APP] " + KeyUtil.describe(e));

        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) {
            long now = SystemClock.uptimeMillis();
            backTimes.add(now);
            while (!backTimes.isEmpty() && now - backTimes.get(0) > 2000) backTimes.remove(0);
            if (backTimes.size() >= 3) {
                LogBus.add("=== uscita ===");
                finish();
            }
        }
        return true; // consuma tutti i tasti: volume, PTT ecc. non producono altri effetti
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        LogBus.add("[MOT] " + KeyUtil.describe(e));
        return true;
    }

    // ---------------------------------------------------------------- dispositivi

    private void printInputDevices() {
        int max = KeyEvent.getMaxKeyCode();
        int[] all = new int[max];
        for (int i = 0; i < max; i++) all[i] = i + 1;

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null) continue;
            LogBus.add("[DEV] id=" + id + " '" + d.getName() + "' src=" + KeyUtil.hex(d.getSources())
                    + (d.isVirtual() ? " (virtuale)" : ""));
            boolean[] has = d.hasKeys(all);
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (int i = 0; i < max; i++) {
                if (has[i]) {
                    sb.append(all[i]).append(':')
                      .append(KeyEvent.keyCodeToString(all[i]).replace("KEYCODE_", ""))
                      .append(' ');
                    n++;
                }
            }
            if (n > 0 && n < 120) LogBus.add("[DEV]    tasti dichiarati (" + n + "): " + sb);
            else if (n >= 120) LogBus.add("[DEV]    " + n + " tasti dichiarati (tastiera completa, omessi)");
            for (InputDevice.MotionRange r : d.getMotionRanges()) {
                LogBus.add("[DEV]    asse " + MotionEvent.axisToString(r.getAxis())
                        + " src=" + KeyUtil.hex(r.getSource()));
            }
        }
    }

    // ---------------------------------------------------------------- broadcast

    private void registerActions() {
        if (receiver != null) {
            try { unregisterReceiver(receiver); } catch (Exception ignored) { }
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                LogBus.add("[INT] " + i.getAction() + KeyUtil.extras(i.getExtras()));
            }
        };
        IntentFilter f = new IntentFilter();
        for (String a : actions) f.addAction(a);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
            else registerReceiver(receiver, f);
            LogBus.add("[INT] in ascolto su " + actions.size() + " azioni broadcast");
        } catch (Exception ex) {
            LogBus.add("[INT] errore registrazione: " + ex);
        }
    }

    // ---------------------------------------------------------------- scansione APK

    private void scanApks() {
        LogBus.add("[SCAN] analisi dei manifest delle app installate...");
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        Map<String, Set<String>> found = new TreeMap<>();
        Set<String> newActions = new TreeSet<>();

        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(getPackageName()) || ai.sourceDir == null) continue;
            try (ZipFile z = new ZipFile(ai.sourceDir)) {
                ZipEntry en = z.getEntry("AndroidManifest.xml");
                if (en == null) continue;
                byte[] data;
                try (InputStream in = z.getInputStream(en)) {
                    data = readAll(in);
                }
                for (String s : AxmlStrings.parse(data)) {
                    if (interesting(s)) {
                        found.computeIfAbsent(ai.packageName, k -> new TreeSet<>()).add(s);
                        newActions.add(s);
                    }
                }
            } catch (Exception ignored) {
                // APK non leggibile: si salta
            }
        }

        // Prima i pacchetti Hytera / PoC, poi gli altri
        for (int pass = 0; pass < 2; pass++) {
            for (Map.Entry<String, Set<String>> e : found.entrySet()) {
                String p = e.getKey().toLowerCase(Locale.ROOT);
                boolean hyt = p.contains("hyt") || p.contains("poc") || p.contains("ptt");
                if ((pass == 0) != hyt) continue;
                LogBus.add("[SCAN] " + (hyt ? "*** " : "") + e.getKey());
                for (String s : e.getValue()) LogBus.add("[SCAN]     " + s);
            }
        }
        LogBus.add("[SCAN] completata: " + apps.size() + " app, "
                + newActions.size() + " stringhe candidate aggiunte all'ascolto");

        main.post(() -> {
            actions.addAll(newActions);
            registerActions();
        });
    }

    private static boolean interesting(String s) {
        if (s == null || s.length() < 6 || s.length() > 150) return false;
        if (!ACTION_LIKE.matcher(s).matches()) return false;
        String l = s.toLowerCase(Locale.ROOT);
        for (String x : SCAN_EXCLUDE) if (l.contains(x)) return false;
        for (String k : SCAN_KEYWORDS) if (l.contains(k)) return true;
        return false;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---------------------------------------------------------------- logcat

    private void startLogcat() {
        if (checkSelfPermission("android.permission.READ_LOGS") != PackageManager.PERMISSION_GRANTED) {
            LogBus.add("[LOG] READ_LOGS non concesso: lettura logcat disattivata (vedi README)");
            return;
        }
        Thread t = new Thread(() -> {
            try {
                logcatProc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "-T", "1"});
                BufferedReader r = new BufferedReader(new InputStreamReader(logcatProc.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(LogBus.TAG)) continue;
                    String l = line.toLowerCase(Locale.ROOT);
                    for (String k : LOG_KEYWORDS) {
                        if (l.contains(k)) {
                            LogBus.add("[LOG] " + line);
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                LogBus.add("[LOG] errore: " + e);
            }
        }, "logcat");
        t.setDaemon(true);
        t.start();
        LogBus.add("[LOG] lettura logcat di sistema attiva");
    }
}
