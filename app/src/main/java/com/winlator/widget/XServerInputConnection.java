package com.winlator.widget;

import android.os.SystemClock;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;

import com.winlator.xserver.XServer;

public class XServerInputConnection extends BaseInputConnection {
    private final XServer xServer;

    public XServerInputConnection(View targetView, XServer xServer) {
        super(targetView, false);
        this.xServer = xServer;
    }

    @Override
    public boolean sendKeyEvent(KeyEvent event) {
        return xServer.keyboard.onKeyEvent(event);
    }

    @Override
    public boolean commitText(CharSequence text, int newCursorPosition) {
        if (text == null || text.length() == 0) {
            return true;
        }

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            switch (c) {
                case ' ':
                    sendKey(KeyEvent.KEYCODE_SPACE);
                    break;

                case '\t':
                    sendKey(KeyEvent.KEYCODE_TAB);
                    break;

                case '\n':
                case '\r':
                    sendKey(KeyEvent.KEYCODE_ENTER);
                    break;

                case '\b':
                    sendKey(KeyEvent.KEYCODE_DEL);
                    break;

                default:
                    xServer.keyboard.onKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), String.valueOf(c), KeyCharacterMap.VIRTUAL_KEYBOARD, 0));
                    break;
            }
        }

        return true;
    }

    @Override
    public boolean deleteSurroundingText(int beforeLength, int afterLength) {
        for (int i = 0; i < beforeLength; i++) {
            sendKey(KeyEvent.KEYCODE_DEL);
        }

        for (int i = 0; i < afterLength; i++) {
            sendKey(KeyEvent.KEYCODE_FORWARD_DEL);
        }

        return true;
    }

    @Override
    public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
        return deleteSurroundingText(beforeLength, afterLength);
    }

    @Override
    public boolean performEditorAction(int actionCode) {
        return sendKey(KeyEvent.KEYCODE_ENTER);
    }

    private boolean sendKey(int keyCode) {
        long time = SystemClock.uptimeMillis();
        xServer.keyboard.onKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, keyCode, 0));
        xServer.keyboard.onKeyEvent(new KeyEvent(time, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));

        return true;
    }
}
