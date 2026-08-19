package com.winlator.widget;

import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.winlator.math.XForm;
import com.winlator.renderer.GLRenderer;
import com.winlator.xserver.Pointer;
import com.winlator.xserver.XServer;

public final class StylusInputHandler {
    private static final long CLICK_RELEASE_DELAY_MS = 30;

    private final View hostView;
    private final XServer xServer;
    private final float[] xform;
    private final StylusConfig config;
    private final StylusGestureStateMachine stateMachine;

    private final float[] transformedPoint = new float[2];

    private Pointer.Button ownedButton = null;
    private Pointer.Button pendingClickButton = null;
    private boolean softKeyboardVisible = false;

    private float lastStylusX;
    private float lastStylusY;

    private float keyboardPanDirection = 0.0f;
    private float keyboardPanIntensity = 0.0f;
    private long lastKeyboardPanTime = 0;
    private boolean keyboardPanScheduled = false;

    private final Rect visibleDisplayFrame = new Rect();
    private final int[] hostLocationOnScreen = new int[2];

    private final Runnable keyboardPanRunnable;

    private final Runnable clickReleaseRunnable;

    private final StylusGestureStateMachine.Sink sink = new StylusGestureStateMachine.Sink() {
        @Override
        public void buttonDown(StylusGestureStateMachine.MouseButton button) {
            pressButton(toPointerButton(button));
        }

        @Override
        public void buttonUp(StylusGestureStateMachine.MouseButton button) {
            releaseButton(toPointerButton(button));
        }

        @Override
        public void buttonClick(StylusGestureStateMachine.MouseButton button) {
            clickButton(toPointerButton(button));
        }
    };

    public StylusInputHandler(View hostView, XServer xServer, float[] xform, StylusConfig config) {
        this.hostView = hostView;
        this.xServer = xServer;
        this.xform = xform;
        this.config = config;

        keyboardPanRunnable = new Runnable() {
            @Override
            public void run() {
                keyboardPanScheduled = false;

                GLRenderer renderer = xServer.getRenderer();
                if (!softKeyboardVisible || !config.keyboardPanEnabled || keyboardPanDirection == 0.0f ||
                        renderer == null || !renderer.isManualScreenOffsetYEnabled()) {
                    return;
                }

                long now = SystemClock.uptimeMillis();
                float deltaTime = lastKeyboardPanTime > 0 ? Math.min((now - lastKeyboardPanTime) / 1000.0f, 0.05f) : 0.0f;
                lastKeyboardPanTime = now;

                if (deltaTime > 0.0f) {
                    float oldOffset = renderer.getManualScreenOffsetY();
                    float delta = keyboardPanDirection * config.keyboardPanSpeed * keyboardPanIntensity * deltaTime;

                    renderer.setManualScreenOffsetY(oldOffset + delta);

                    if (Math.abs(renderer.getManualScreenOffsetY() - oldOffset) > 0.001f) {
                        movePointerToLastStylusPosition();
                        scheduleKeyboardPan();
                        return;
                    }
                }

                keyboardPanDirection = 0.0f;
                keyboardPanIntensity = 0.0f;
                lastKeyboardPanTime = 0;
            }
        };

        this.clickReleaseRunnable = () -> {
            if (pendingClickButton != null) {
                if (xServer.pointer.isButtonPressed(pendingClickButton)) {
                    xServer.injectPointerButtonRelease(pendingClickButton);
                }

                pendingClickButton = null;
            }
        };

        stateMachine = new StylusGestureStateMachine(config.mode, config.dragThreshold);
    }

    public boolean onTouchEvent(MotionEvent event) {
        if (!config.enabled) {
            return false;
        }

        int actionMasked = event.getActionMasked();

        if (actionMasked == MotionEvent.ACTION_CANCEL && stateMachine.isActive()) {
            cancel();
            return true;
        }

        int stylusIndex = findStylusPointerIndex(event);

        if (stylusIndex < 0) {
            return false;
        }

        if (!hostView.isEnabled()) {
            cancel();
            return true;
        }

        switch (actionMasked) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int actionIndex = event.getActionIndex();

                if (!isStylusPointer(event, actionIndex)) {
                    return true;
                }

                updateKeyboardPan(event.getX(actionIndex), event.getY(actionIndex));
                int[] point = transformPoint(event.getX(actionIndex), event.getY(actionIndex));

                movePointer(point[0], point[1]);
                stateMachine.onDown(point[0], point[1], getContactButton(event), sink);

                return true;
            }

            case MotionEvent.ACTION_MOVE: {
                for (int historyIndex = 0; historyIndex < event.getHistorySize(); historyIndex++) {
                    processMove(event.getHistoricalX(stylusIndex, historyIndex),
                                event.getHistoricalY(stylusIndex, historyIndex));
                }

                updateKeyboardPan(event.getX(stylusIndex), event.getY(stylusIndex));
                processMove(event.getX(stylusIndex), event.getY(stylusIndex));

                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                stopKeyboardPan();
                int actionIndex = event.getActionIndex();

                if (!isStylusPointer(event, actionIndex)) {
                    return true;
                }

                int[] point = transformPoint(event.getX(actionIndex), event.getY(actionIndex));
                stateMachine.onMove(point[0], point[1], sink);
                movePointer(point[0], point[1]);
                stateMachine.onUp(sink);

                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                stopKeyboardPan();
                cancel();
                return true;
        }

        return true;
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        if (!config.enabled || !config.hoverMove || !hostView.isEnabled()) {
            return false;
        }

        int action = event.getActionMasked();

        if (action != MotionEvent.ACTION_HOVER_ENTER &&
            action != MotionEvent.ACTION_HOVER_MOVE &&
            action != MotionEvent.ACTION_HOVER_EXIT) {
            return false;
        }

        int stylusIndex = findStylusPointerIndex(event);

        if (stylusIndex < 0) {
            return false;
        }

        if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE) {
            updateKeyboardPan(event.getX(stylusIndex), event.getY(stylusIndex));

            int[] point = transformPoint(event.getX(stylusIndex), event.getY(stylusIndex));
            movePointer(point[0], point[1]);
        }
        else if (action == MotionEvent.ACTION_HOVER_EXIT) {
            stopKeyboardPan();
        }

        return true;
    }

    public void cancel() {
        stopKeyboardPan();
        hostView.removeCallbacks(clickReleaseRunnable);

        if (pendingClickButton != null) {
            if (xServer.pointer.isButtonPressed(pendingClickButton)) {
                xServer.injectPointerButtonRelease(pendingClickButton);
            }

            pendingClickButton = null;
        }

        stateMachine.cancel(sink);

        if (ownedButton != null) {
            Pointer.Button button = ownedButton;
            releaseButton(button);
        }
    }

    private void processMove(float x, float y) {
        int[] point = transformPoint(x, y);

        /*
         * Important ordering for GESTURE mode:
         * transition to DRAGGING first, then move the pointer.
         */
        stateMachine.onMove(point[0], point[1], sink);
        movePointer(point[0], point[1]);
    }

    private int[] transformPoint(float x, float y) {
        XForm.transformPoint(xform, x, y, transformedPoint);

        int transformedX = (int) transformedPoint[0];
        int transformedY = (int) transformedPoint[1];

        GLRenderer renderer = xServer.getRenderer();
        if (renderer != null && renderer.isManualScreenOffsetYEnabled()) {
            transformedY += Math.round(renderer.getManualScreenOffsetY());
        }

        transformedX = Math.max(0, Math.min(xServer.screenInfo.width - 1, transformedX));
        transformedY = Math.max(0, Math.min(xServer.screenInfo.height - 1, transformedY));

        return new int[] { transformedX, transformedY };
    }

    private void movePointer(int x, int y) {
        xServer.injectPointerMove(x, y);
    }

    private void pressButton(Pointer.Button button) {
        hostView.removeCallbacks(clickReleaseRunnable);

        if (pendingClickButton != null) {
            if (pendingClickButton == button && xServer.pointer.isButtonPressed(button)) {
                pendingClickButton = null;
                ownedButton = button;
                return;
            }

            if (xServer.pointer.isButtonPressed(pendingClickButton)) {
                xServer.injectPointerButtonRelease(pendingClickButton);
            }

            pendingClickButton = null;
        }

        if (ownedButton != null) {
            if (ownedButton == button)
                return;
            releaseButton(ownedButton);
        }

        if (!xServer.pointer.isButtonPressed(button)) {
            xServer.injectPointerButtonPress(button);
            ownedButton = button;
        }
    }

    private void releaseButton(Pointer.Button button) {
        if (ownedButton != button)
            return;

        if (xServer.pointer.isButtonPressed(button)) {
            xServer.injectPointerButtonRelease(button);
        }

        ownedButton = null;
    }

    private void clickButton(Pointer.Button button) {
        if (ownedButton != null || pendingClickButton != null || xServer.pointer.isButtonPressed(button))
            return;

        xServer.injectPointerButtonPress(button);
        pendingClickButton = button;
        hostView.postDelayed(clickReleaseRunnable, CLICK_RELEASE_DELAY_MS);
    }

    public static boolean isStylusEvent(MotionEvent event) {
        return findStylusPointerIndex(event) >= 0;
    }

    private static int findStylusPointerIndex(MotionEvent event) {
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (isStylusPointer(event, i)) {
                return i;
            }
        }

        return -1;
    }

    private static boolean isStylusPointer(MotionEvent event, int pointerIndex) {
        return pointerIndex >= 0
            && pointerIndex < event.getPointerCount()
            && event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS;
    }

    public void setSoftKeyboardVisible(boolean visible) {
        softKeyboardVisible = visible;
        if (!visible)
            stopKeyboardPan();
    }

    private void updateKeyboardPan(float x, float y) {
        lastStylusX = x;
        lastStylusY = y;

        GLRenderer renderer = xServer.getRenderer();

        if (!softKeyboardVisible || !config.keyboardPanEnabled || renderer == null || !renderer.isManualScreenOffsetYEnabled()) {
            stopKeyboardPan();
            return;
        }

        float keyboardTop = getKeyboardTop();
        float edgeSize = Math.min(config.keyboardPanEdgeSize, keyboardTop * 0.45f);

        if (edgeSize <= 0.0f) {
            stopKeyboardPan();
            return;
        }

        if (y < edgeSize) {
            keyboardPanDirection = -1.0f;
            keyboardPanIntensity = Math.min(1.0f, Math.max(0.0f, (edgeSize - y) / edgeSize));
        }
        else if (y > keyboardTop - edgeSize && y < keyboardTop) {
            keyboardPanDirection = 1.0f;
            keyboardPanIntensity = Math.min(1.0f, Math.max(0.0f, (y - (keyboardTop - edgeSize)) / edgeSize));
        }
        else {
            stopKeyboardPan();
            return;
        }

        scheduleKeyboardPan();
    }

    private void scheduleKeyboardPan() {
        if (keyboardPanScheduled)
            return;

        keyboardPanScheduled = true;

        if (lastKeyboardPanTime == 0)
            lastKeyboardPanTime = SystemClock.uptimeMillis();

        hostView.postOnAnimation(keyboardPanRunnable);
    }

    private void stopKeyboardPan() {
        if (keyboardPanScheduled)
            hostView.removeCallbacks(keyboardPanRunnable);

        keyboardPanScheduled = false;
        keyboardPanDirection = 0.0f;
        keyboardPanIntensity = 0.0f;
        lastKeyboardPanTime = 0;
    }

    private void movePointerToLastStylusPosition() {
        int[] point = transformPoint(lastStylusX, lastStylusY);
        movePointer(point[0], point[1]);
    }

    private float getKeyboardTop() {
        hostView.getWindowVisibleDisplayFrame(visibleDisplayFrame);
        hostView.getLocationOnScreen(hostLocationOnScreen);

        float keyboardTop = visibleDisplayFrame.bottom - hostLocationOnScreen[1];
        return Math.max(0.0f, Math.min(hostView.getHeight(), keyboardTop));
    }

    private static Pointer.Button toPointerButton(StylusGestureStateMachine.MouseButton button) {
        return button == StylusGestureStateMachine.MouseButton.RIGHT ? Pointer.Button.BUTTON_RIGHT : Pointer.Button.BUTTON_LEFT;
    }

    private StylusGestureStateMachine.MouseButton getContactButton(MotionEvent event) {
        if (config.barrelButtonRightClick && (event.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            return StylusGestureStateMachine.MouseButton.RIGHT;
        }

        return StylusGestureStateMachine.MouseButton.LEFT;
    }
}
