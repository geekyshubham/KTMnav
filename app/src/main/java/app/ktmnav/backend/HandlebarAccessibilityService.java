package app.ktmnav.backend;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * System-bound AccessibilityService executing mapped phone UI actions.
 * No content collection, logging or network access. A touch gesture is used only as the long-press
 * fallback on the focused control's centre. Never acts on system UI or permission dialogs.
 */
public final class HandlebarAccessibilityService extends AccessibilityService {

    static final long LONG_PRESS_STROKE_MS = 600L;

    private static volatile HandlebarAccessibilityService instance = null;

    public static boolean isConnected() {
        return instance != null;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Explicitly no-op: service only executes requested phone actions, no content collection
    }

    @Override
    public void onInterrupt() {
        // Required lifecycle callback
    }

    public static String perform(HandlebarMappings.Action action) {
        HandlebarAccessibilityService service = instance;
        if (service == null) {
            return "Accessibility service not connected";
        }
        if (action == null) {
            return "No action specified";
        }

        KeyguardManager km = (KeyguardManager) service.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            return "Phone is locked";
        }

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                service.clearCache();
            } catch (Throwable ignored) {
            }
        }

        try {
            if (action != HandlebarMappings.Action.BACK && isSystemScreen(service)) {
                return SystemScreenGuard.BLOCKED_RESULT;
            }
            switch (action) {
                case BACK:
                    return performBack(service);
                case SCROLL_UP:
                    return performScroll(service, false);
                case SCROLL_DOWN:
                    return performScroll(service, true);
                case FOCUS_NEXT:
                    return performFocus(service, true);
                case FOCUS_PREVIOUS:
                    return performFocus(service, false);
                case CLICK_FOCUSED:
                    return performClickFocused(service);
                case LONG_CLICK_FOCUSED:
                    return performLongClickFocused(service);
                default:
                    return "Unsupported accessibility action: " + action.name();
            }
        } catch (Exception e) {
            return "Accessibility action error: " + e.getMessage();
        }
    }

    private static boolean isSystemScreen(AccessibilityService service) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        try {
            return SystemScreenGuard.isBlocked(root.getPackageName());
        } finally {
            root.recycle();
        }
    }

    private static String performBack(AccessibilityService service) {
        boolean success = service.performGlobalAction(GLOBAL_ACTION_BACK);
        return success ? "Back performed" : "Back action failed";
    }

    private static String performScroll(AccessibilityService service, boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return "No active window";
        }

        Queue<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int inspectedCount = 0;
        int scrollAction = forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;

        try {
            while (!queue.isEmpty() && inspectedCount < 200) {
                AccessibilityNodeInfo current = queue.poll();
                if (current == null) {
                    continue;
                }
                inspectedCount++;

                if (current.isScrollable()) {
                    boolean performed = current.performAction(scrollAction);
                    if (performed) {
                        current.recycle();
                        return forward ? "Scrolled down" : "Scrolled up";
                    }
                }

                int childCount = current.getChildCount();
                for (int i = 0; i < childCount && (inspectedCount + queue.size()) < 200; i++) {
                    AccessibilityNodeInfo child = current.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
                current.recycle();
            }
            return "No scrollable element accepted action";
        } finally {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo remaining = queue.poll();
                if (remaining != null) {
                    remaining.recycle();
                }
            }
        }
    }

    private static String performFocus(AccessibilityService service, boolean forward) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return "No active window";
        }

        AccessibilityNodeInfo currentFocus = null;
        AccessibilityNodeInfo nextFocus = null;
        try {
            currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (currentFocus == null) {
                currentFocus = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            }

            int direction = forward ? View.FOCUS_FORWARD : View.FOCUS_BACKWARD;
            if (currentFocus != null) {
                nextFocus = currentFocus.focusSearch(direction);
            } else {
                nextFocus = root.focusSearch(direction);
            }

            if (nextFocus == null) {
                return "No focus target found";
            }

            boolean ok = nextFocus.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return ok
                    ? (forward ? "Focused next element" : "Focused previous element")
                    : "Focus action failed";
        } finally {
            if (currentFocus != null) {
                currentFocus.recycle();
            }
            if (nextFocus != null && nextFocus != currentFocus) {
                nextFocus.recycle();
            }
            root.recycle();
        }
    }

    private static String performClickFocused(AccessibilityService service) {
        AccessibilityNodeInfo target = findFreshFocusedNode(service);
        if (target == null) {
            return "No focused element to click";
        }
        try {
            if (SystemScreenGuard.isBlocked(target.getPackageName())) {
                return SystemScreenGuard.BLOCKED_RESULT;
            }
            boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            return clicked ? "Clicked focused element" : "Click action failed";
        } finally {
            target.recycle();
        }
    }

    private static String performLongClickFocused(AccessibilityService service) {
        AccessibilityNodeInfo target = findFreshFocusedNode(service);
        if (target == null) {
            return "No focused element to long-press";
        }
        try {
            if (SystemScreenGuard.isBlocked(target.getPackageName())) {
                return SystemScreenGuard.BLOCKED_RESULT;
            }
            if (target.getActionList().contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)) {
                boolean done = target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
                return done ? "Long-pressed focused element" : "Long-press action failed";
            }
            if (!canPerformGestures(service)) {
                return "Focused element does not support long-press";
            }
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            float[] centre = SystemScreenGuard.centre(bounds.left, bounds.top, bounds.right, bounds.bottom);
            if (centre == null) {
                return "Focused element has no visible bounds";
            }
            Path path = new Path();
            path.moveTo(centre[0], centre[1]);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, LONG_PRESS_STROKE_MS))
                    .build();
            boolean sent = service.dispatchGesture(gesture, null, null);
            return sent ? "Long-press gesture sent" : "Long-press gesture rejected";
        } finally {
            target.recycle();
        }
    }

    private static boolean canPerformGestures(AccessibilityService service) {
        AccessibilityServiceInfo info = service.getServiceInfo();
        return info != null
                && (info.getCapabilities() & AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0;
    }

    private static AccessibilityNodeInfo findFreshFocusedNode(AccessibilityService service) {
        // 1. Check INPUT focus first
        AccessibilityNodeInfo inputTarget = findFreshNodeOfKind(service, AccessibilityNodeInfo.FOCUS_INPUT);
        if (inputTarget != null) {
            return inputTarget;
        }

        // 2. Fall back to genuine fresh ACCESSIBILITY focus
        return findFreshNodeOfKind(service, AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
    }

    private static AccessibilityNodeInfo findFreshNodeOfKind(AccessibilityService service, int focusType) {
        AccessibilityNodeInfo candidate = queryFocusedNode(service, focusType);
        if (candidate != null) {
            if (isNodeCurrentAndFocused(candidate, focusType)) {
                return candidate;
            }
            candidate.recycle();
        }

        // Max one retry find with fresh window root
        candidate = queryFocusedNode(service, focusType);
        if (candidate != null) {
            if (isNodeCurrentAndFocused(candidate, focusType)) {
                return candidate;
            }
            candidate.recycle();
        }

        return null;
    }

    private static AccessibilityNodeInfo queryFocusedNode(AccessibilityService service, int focusType) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) {
            return null;
        }
        try {
            return root.findFocus(focusType);
        } finally {
            root.recycle();
        }
    }

    private static boolean isNodeCurrentAndFocused(AccessibilityNodeInfo node, int focusType) {
        try {
            if (!node.refresh()) return false;
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (focusType == AccessibilityNodeInfo.FOCUS_INPUT) {
            return node.isFocused();
        } else {
            return node.isAccessibilityFocused();
        }
    }
}
