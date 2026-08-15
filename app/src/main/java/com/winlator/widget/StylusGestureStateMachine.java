package com.winlator.widget;

final class StylusGestureStateMachine {
    enum State {
        IDLE,
        DIRECT_PRESSED,
        PENDING,
        DRAGGING
    }

    enum MouseButton {
        LEFT,
        RIGHT
    }

    interface Sink {
        void buttonDown(MouseButton button);
        void buttonUp(MouseButton button);
        void buttonClick(MouseButton button);
    }

    private final StylusConfig.Mode mode;
    private final int dragThresholdSquared;

    private State state = State.IDLE;
    private MouseButton activeButton = MouseButton.LEFT;
    private int startX;
    private int startY;

    StylusGestureStateMachine(StylusConfig.Mode mode, int dragThreshold) {
        this.mode = mode;
        this.dragThresholdSquared = dragThreshold * dragThreshold;
    }

    void onDown(int x, int y, MouseButton button, Sink sink) {
        if (state != State.IDLE)
            cancel(sink);

        startX = x;
        startY = y;
        activeButton = button;

        if (mode == StylusConfig.Mode.DIRECT) {
            sink.buttonDown(activeButton);
            state = State.DIRECT_PRESSED;
        } else {
            state = State.PENDING;
        }
    }

    void onMove(int x, int y, Sink sink) {
        if (state != State.PENDING)
            return;

        int dx = x - startX;
        int dy = y - startY;
        int distanceSquared = dx * dx + dy * dy;

        if (distanceSquared >= dragThresholdSquared) {
            sink.buttonDown(activeButton);
            state = State.DRAGGING;
        }
    }

    void onUp(Sink sink) {
        switch (state) {
            case DIRECT_PRESSED:
            case DRAGGING:
                sink.buttonUp(activeButton);
                break;

            case PENDING:
                sink.buttonClick(activeButton);
                break;
        }

        state = State.IDLE;
        activeButton = MouseButton.LEFT;
    }

    void cancel(Sink sink) {
        if (state == State.DIRECT_PRESSED || state == State.DRAGGING) {
            sink.buttonUp(activeButton);
        }

        state = State.IDLE;
        activeButton = MouseButton.LEFT;
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    State getState() {
        return state;
    }
}
