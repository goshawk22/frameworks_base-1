/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.accessibility.gestures;

import static com.android.server.accessibility.gestures.TouchExplorer.DEBUG;

import android.content.Context;
import android.gesture.GesturePoint;
import android.graphics.PointF;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.util.TypedValue;
import android.view.MotionEvent;

import java.util.ArrayList;

/**
 * This class is responsible for matching one-finger swipe gestures. Each instance matches one swipe
 * gesture. A swipe is specified as a series of one or more directions e.g. left, left and up, etc.
 * At this time swipes with more than two directions are not supported.
 */
class Swipe extends GestureMatcher {

    // Direction constants.
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int UP = 2;
    public static final int DOWN = 3;
    // This is the calculated movement threshold used track if the user is still
    // moving their finger.
    private final float mGestureDetectionThreshold;

    // Buffer for storing points for gesture detection.
    private final ArrayList<GesturePoint> mStrokeBuffer = new ArrayList<GesturePoint>(100);

    // The minimal delta between moves to add a gesture point.
    private static final int TOUCH_TOLERANCE_PIX = 3;

    // The minimal score for accepting a predicted gesture.
    private static final float MIN_PREDICTION_SCORE = 2.0f;

    // Distance a finger must travel before we decide if it is a gesture or not.
    private static final int GESTURE_CONFIRM_CM = 1;

    // Time threshold used to determine if an interaction is a gesture or not.
    // If the first movement of 1cm takes longer than this value, we assume it's
    // a slow movement, and therefore not a gesture.
    //
    // This value was determined by measuring the time for the first 1cm
    // movement when gesturing, and touch exploring.  Based on user testing,
    // all gestures started with the initial movement taking less than 100ms.
    // When touch exploring, the first movement almost always takes longer than
    // 200ms.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS = 150;

    // Time threshold used to determine if a gesture should be cancelled.  If
    // the finger takes more than this time to move 1cm, the ongoing gesture is
    // cancelled.
    private static final long CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS = 300;

    private int[] mDirections;
    private float mBaseX;
    private float mBaseY;
    private long mBaseTime;
    private float mPreviousGestureX;
    private float mPreviousGestureY;
    // Constants for sampling motion event points.
    // We sample based on a minimum distance between points, primarily to improve accuracy by
    // reducing noisy minor changes in direction.
    private static final float MIN_CM_BETWEEN_SAMPLES = 0.25f;
    private final float mMinPixelsBetweenSamplesX;
    private final float mMinPixelsBetweenSamplesY;

    // Constants for separating gesture segments
    private static final float ANGLE_THRESHOLD = 0.0f;

    Swipe(
            Context context,
            int direction,
            int gesture,
            GestureMatcher.StateChangeListener listener) {
        this(context, new int[] {direction}, gesture, listener);
    }

    Swipe(
            Context context,
            int direction1,
            int direction2,
            int gesture,
            GestureMatcher.StateChangeListener listener) {
        this(context, new int[] {direction1, direction2}, gesture, listener);
    }

    private Swipe(
            Context context,
            int[] directions,
            int gesture,
            GestureMatcher.StateChangeListener listener) {
        super(gesture, new Handler(context.getMainLooper()), listener);
        mDirections = directions;
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        mGestureDetectionThreshold =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 10, displayMetrics)
                        * GESTURE_CONFIRM_CM;
        // Calculate minimum gesture velocity
        final float pixelsPerCmX = displayMetrics.xdpi / 2.54f;
        final float pixelsPerCmY = displayMetrics.ydpi / 2.54f;
        mMinPixelsBetweenSamplesX = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmX;
        mMinPixelsBetweenSamplesY = MIN_CM_BETWEEN_SAMPLES * pixelsPerCmY;
        clear();
    }

    @Override
    protected void clear() {
        mBaseX = Float.NaN;
        mBaseY = Float.NaN;
        mBaseTime = 0;
        mStrokeBuffer.clear();
        super.clear();
    }

    @Override
    protected void onDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelAfterDelay(event, rawEvent, policyFlags);
        if (Float.isNaN(mBaseX) && Float.isNaN(mBaseY)) {
            mBaseX = rawEvent.getX();
            mBaseY = rawEvent.getY();
            mBaseTime = event.getEventTime();
            mPreviousGestureX = mBaseX;
            mPreviousGestureY = mBaseY;
        }
        // Otherwise do nothing because this event doesn't make sense in the middle of a gesture.
    }

    @Override
    protected void onMove(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        final float x = rawEvent.getX();
        final float y = rawEvent.getY();
        final long time = event.getEventTime();
        final float dX = Math.abs(x - mPreviousGestureX);
        final float dY = Math.abs(y - mPreviousGestureY);
        final long timeDelta = time - mBaseTime;
        final double moveDelta = Math.hypot(Math.abs(x - mBaseX), Math.abs(y - mBaseY));
        if (DEBUG) {
            Slog.d(
                    getGestureName(),
                    "moveDelta:"
                            + Double.toString(moveDelta)
                            + " mGestureDetectionThreshold: "
                            + Float.toString(mGestureDetectionThreshold));
        }
        if (getState() == STATE_CLEAR) {
            if (mStrokeBuffer.size() == 0) {
                // First, make sure the pointer is going in the right direction.
                cancelAfterDelay(event, rawEvent, policyFlags);
                int direction = toDirection(x - mBaseX, y - mBaseY);
                if (direction != mDirections[0]) {
                    cancelGesture(event, rawEvent, policyFlags);
                    return;
                } else {
                    // This is confirmed to be some kind of swipe so start tracking points.
                    mStrokeBuffer.add(new GesturePoint(mBaseX, mBaseY, mBaseTime));
                }
            }
            if (moveDelta > mGestureDetectionThreshold) {
                // If the pointer has moved more than the threshold,
                // update the stored values.
                mBaseX = x;
                mBaseY = y;
                mBaseTime = time;
                if (getState() == STATE_CLEAR) {
                    startGesture(event, rawEvent, policyFlags);
                    cancelAfterDelay(event, rawEvent, policyFlags);
                }
            }
        }
        if (getState() == STATE_GESTURE_STARTED) {
            if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
                mPreviousGestureX = x;
                mPreviousGestureY = y;
                mStrokeBuffer.add(new GesturePoint(x, y, time));
                cancelAfterDelay(event, rawEvent, policyFlags);
            }
        }
    }

    @Override
    protected void onUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (getState() != STATE_GESTURE_STARTED) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }

        final float x = rawEvent.getX();
        final float y = rawEvent.getY();
        final long time = event.getEventTime();
        final float dX = Math.abs(x - mPreviousGestureX);
        final float dY = Math.abs(y - mPreviousGestureY);
        if (dX >= mMinPixelsBetweenSamplesX || dY >= mMinPixelsBetweenSamplesY) {
            mStrokeBuffer.add(new GesturePoint(x, y, time));
        }
        recognizeGesture(event, rawEvent, policyFlags);
    }

    @Override
    protected void onPointerDown(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    @Override
    protected void onPointerUp(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelGesture(event, rawEvent, policyFlags);
    }

    /**
     * queues a transition to STATE_GESTURE_CANCEL based on the current state. If we have
     * transitioned to STATE_GESTURE_STARTED the delay is longer.
     */
    private void cancelAfterDelay(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        cancelPendingTransitions();
        switch (getState()) {
            case STATE_CLEAR:
                cancelAfter(CANCEL_ON_PAUSE_THRESHOLD_NOT_STARTED_MS, event, rawEvent, policyFlags);
                break;
            case STATE_GESTURE_STARTED:
                cancelAfter(CANCEL_ON_PAUSE_THRESHOLD_STARTED_MS, event, rawEvent, policyFlags);
                break;
            default:
                break;
        }
    }

    /**
     * Looks at the sequence of motions in mStrokeBuffer, classifies the gesture, then calls
     * Listener callbacks for success or failure.
     *
     * @param event The raw motion event to pass to the listener callbacks.
     * @param policyFlags Policy flags for the event.
     * @return true if the event is consumed, else false
     */
    private void recognizeGesture(MotionEvent event, MotionEvent rawEvent, int policyFlags) {
        if (mStrokeBuffer.size() < 2) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }

        // Look at mStrokeBuffer and extract 2 line segments, delimited by near-perpendicular
        // direction change.
        // Method: for each sampled motion event, check the angle of the most recent motion vector
        // versus the preceding motion vector, and segment the line if the angle is about
        // 90 degrees.

        ArrayList<PointF> path = new ArrayList<>();
        PointF lastDelimiter = new PointF(mStrokeBuffer.get(0).x, mStrokeBuffer.get(0).y);
        path.add(lastDelimiter);

        float dX = 0; // Sum of unit vectors from last delimiter to each following point
        float dY = 0;
        int count = 0; // Number of points since last delimiter
        float length = 0; // Vector length from delimiter to most recent point

        PointF next = new PointF();
        for (int i = 1; i < mStrokeBuffer.size(); ++i) {
            next = new PointF(mStrokeBuffer.get(i).x, mStrokeBuffer.get(i).y);
            if (count > 0) {
                // Average of unit vectors from delimiter to following points
                float currentDX = dX / count;
                float currentDY = dY / count;

                // newDelimiter is a possible new delimiter, based on a vector with length from
                // the last delimiter to the previous point, but in the direction of the average
                // unit vector from delimiter to previous points.
                // Using the averaged vector has the effect of "squaring off the curve",
                // creating a sharper angle between the last motion and the preceding motion from
                // the delimiter. In turn, this sharper angle achieves the splitting threshold
                // even in a gentle curve.
                PointF newDelimiter =
                        new PointF(
                                length * currentDX + lastDelimiter.x,
                                length * currentDY + lastDelimiter.y);

                // Unit vector from newDelimiter to the most recent point
                float nextDX = next.x - newDelimiter.x;
                float nextDY = next.y - newDelimiter.y;
                float nextLength = (float) Math.sqrt(nextDX * nextDX + nextDY * nextDY);
                nextDX = nextDX / nextLength;
                nextDY = nextDY / nextLength;

                // Compare the initial motion direction to the most recent motion direction,
                // and segment the line if direction has changed by about 90 degrees.
                float dot = currentDX * nextDX + currentDY * nextDY;
                if (dot < ANGLE_THRESHOLD) {
                    path.add(newDelimiter);
                    lastDelimiter = newDelimiter;
                    dX = 0;
                    dY = 0;
                    count = 0;
                }
            }

            // Vector from last delimiter to most recent point
            float currentDX = next.x - lastDelimiter.x;
            float currentDY = next.y - lastDelimiter.y;
            length = (float) Math.sqrt(currentDX * currentDX + currentDY * currentDY);

            // Increment sum of unit vectors from delimiter to each following point
            count = count + 1;
            dX = dX + currentDX / length;
            dY = dY + currentDY / length;
        }

        path.add(next);
        if (DEBUG) {
            Slog.d(getGestureName(), "path=" + path.toString());
        }
        // Classify line segments, and call Listener callbacks.
        recognizeGesturePath(event, rawEvent, policyFlags, path);
    }

    /**
     * Classifies a pair of line segments, by direction. Calls Listener callbacks for success or
     * failure.
     *
     * @param event The raw motion event to pass to the listener's onGestureCanceled method.
     * @param policyFlags Policy flags for the event.
     * @param path A sequence of motion line segments derived from motion points in mStrokeBuffer.
     * @return true if the event is consumed, else false
     */
    private void recognizeGesturePath(
            MotionEvent event, MotionEvent rawEvent, int policyFlags, ArrayList<PointF> path) {

        final int displayId = event.getDisplayId();
        if (path.size() != mDirections.length + 1) {
            cancelGesture(event, rawEvent, policyFlags);
            return;
        }
        for (int i = 0; i < path.size() - 1; ++i) {
            PointF start = path.get(i);
            PointF end = path.get(i + 1);

            float dX = end.x - start.x;
            float dY = end.y - start.y;
            int direction = toDirection(dX, dY);
            if (direction != mDirections[i]) {
                if (DEBUG) {
                    Slog.d(
                            getGestureName(),
                            "Found direction "
                                    + directionToString(direction)
                                    + " when expecting "
                                    + directionToString(mDirections[i]));
                }
                cancelGesture(event, rawEvent, policyFlags);
                return;
            }
        }
        if (DEBUG) {
            Slog.d(getGestureName(), "Completed.");
        }
        completeGesture(event, rawEvent, policyFlags);
    }

    private static int toDirection(float dX, float dY) {
        if (Math.abs(dX) > Math.abs(dY)) {
            // Horizontal
            return (dX < 0) ? LEFT : RIGHT;
        } else {
            // Vertical
            return (dY < 0) ? UP : DOWN;
        }
    }

    public static String directionToString(int direction) {
        switch (direction) {
            case LEFT:
                return "left";
            case RIGHT:
                return "right";
            case UP:
                return "up";
            case DOWN:
                return "down";
            default:
                return "Unknown Direction";
        }
    }

    @Override
    String getGestureName() {
        StringBuilder builder = new StringBuilder();
        builder.append("Swipe ").append(directionToString(mDirections[0]));
        for (int i = 1; i < mDirections.length; ++i) {
            builder.append(" and ").append(directionToString(mDirections[i]));
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder(super.toString());
        if (getState() != STATE_GESTURE_CANCELED) {
            builder.append(", mBaseX: ")
                    .append(mBaseX)
                    .append(", mBaseY: ")
                    .append(mBaseY)
                    .append(", mGestureDetectionThreshold:")
                    .append(mGestureDetectionThreshold)
                    .append(", mMinPixelsBetweenSamplesX:")
                    .append(mMinPixelsBetweenSamplesX)
                    .append(", mMinPixelsBetweenSamplesY:")
                    .append(mMinPixelsBetweenSamplesY);
        }
        return builder.toString();
    }
}
