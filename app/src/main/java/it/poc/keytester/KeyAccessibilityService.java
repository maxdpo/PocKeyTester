package it.poc.keytester;

import android.accessibilityservice.AccessibilityService;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Riceve i tasti fisici a livello di sistema, anche con l'app in background.
 * Restituisce sempre false: i tasti NON vengono bloccati e continuano a funzionare.
 */
public class KeyAccessibilityService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        LogBus.init(this);
        LogBus.add("[ACC] servizio di accessibilità collegato");
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        if (e.getRepeatCount() <= 1) LogBus.add("[ACC] " + KeyUtil.describe(e));
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
