package com.winlator.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import androidx.core.app.NotificationCompat;
import com.winlator.R;
import com.winlator.MainActivity;
import java.lang.ref.WeakReference;

public class NotificationUtils {
    private static final String CHANNEL_ID = "nmdcomputer_foreground_service";
    private static final String CHANNEL_NAME = "NMD COMPUTER Foreground Service";

    private final Context context;
    private final NotificationManager notificationManager;

    private static volatile WeakReference<NotificationUtils> instance;
    public static NotificationUtils getInstance(Context ctx) {
        NotificationUtils inst = instance != null ? instance.get() : null;
        if (inst == null) {
            synchronized (NotificationUtils.class) {
                inst = instance != null ? instance.get() : null;
                if (inst == null) {
                    inst = new NotificationUtils(ctx.getApplicationContext());
                    instance = new WeakReference<>(inst);
                }
            }
        }
        return inst;
    }

    public NotificationUtils(Context context) {
        // Store the application context to avoid leaking an Activity context when
        // this helper is held by long-lived components such as Services.
        this.context = context == null ? null : context.getApplicationContext();
        this.notificationManager = (NotificationManager) (this.context == null ? null : this.context.getSystemService(Context.NOTIFICATION_SERVICE));
        createNotificationChannel();
    }

    /**
     * Sends or updates a notification with default title, services class, and exit action.
     */
    public void notify(int id, String content) {
        notify(id, content, context.getString(R.string.app_name));
    }

    /**
     * Sends or updates a notification with services class and exit action as null.
     */
    public void notify(int id, String content, String title) {
        notify(id, content, title, null, null);
    }

    /**
     * Sends or updates a notification with all parameters.
     */
    public void notify(int id, String content, String title, Class<?> serviceClass, String exitAction) {
        Notification notification = createForegroundNotification(content, title, serviceClass, exitAction, MainActivity.class);
        notificationManager.notify(id, notification);
    }

    /**
     * Overload to notify using a pre-built Notification object.
     */
    public void notify(int id, Notification notification) {
        notificationManager.notify(id, notification);
    }

    /**
     * Cancels a notification.
     */
    public void cancel(int id) {
        notificationManager.cancel(id);
    }

    /**
     * Creates a foreground notification.
     */
    public Notification createForegroundNotification(String content, String title, Class<?> serviceClass, String exitAction, Class<?> targetActivity) {
        Intent intent = new Intent(context, targetActivity);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.icon_notification)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(pendingIntent);

        // Add "Exit" button only if services and action are provided
        if (serviceClass != null && exitAction != null) {
            Intent stopIntent = new Intent(context, serviceClass);
            stopIntent.setAction(exitAction);
            PendingIntent stopPendingIntent = PendingIntent.getForegroundService(
                    context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(0, "Exit", stopPendingIntent);
        }

        return builder.build();
    }

    /**
     * Overload of createForegroundNotification() with default target activity.
     */
    public Notification createForegroundNotification(String content, String title, Class<?> serviceClass, String exitAction) {
        return createForegroundNotification(content, title, serviceClass, exitAction, MainActivity.class);
    }

    /**
     * Overload of createForegroundNotification() with default title and target activity.
     */
    public Notification createForegroundNotification(String content, String title) {
        return createForegroundNotification(content, title, null, null, MainActivity.class);
    }

    /**
     * Create a notification channel.
     *
     * @param context   The context of the app.
     * @param channelId Unique channel identifier.
     * @param name      Visible channel name for the user.
     * @param importance Importance level (e.g., NotificationManager.IMPORTANCE_LOW).
     * @param desc      Channel description (optional).
     */
    public void createNotificationChannel(Context context, String channelId, String name, int importance, String desc) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(channelId) == null) {
            NotificationChannel channel = new NotificationChannel(channelId, name, importance);
            if (desc != null && !desc.isEmpty()) {
                channel.setDescription(desc);
            }
            channel.setShowBadge(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(channel);
        }
    }

    /**
     * Overload of createNotificationChannel() without description.
     */
    public void createNotificationChannel(Context context, String channelId, String name, int importance) {
        createNotificationChannel(context, channelId, name, importance, "");
    }

    /**
     * Overload of createNotificationChannel() using default values.
     */
    public void createNotificationChannel() {
        createNotificationChannel(
                context,
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
                "Allows to display NMD COMPUTER foreground notifications"
        );
    }

    /**
     * Generate a unique ID based on the package name and the given string
     * to avoid conflicts with other forks/flavors.
     *
     * @param context             The context of the app for getting the package name.
     * @param notificationIDName A string that identifies the notification and is used
     *                           to generate a unique ID.
     * @return A unique integer identifier.
     */
    public static int generateNotificationId(Context context, String notificationIDName) {
        String contextKey = context.getPackageName() + notificationIDName;
        return contextKey.hashCode() & 0x7FFFFFFF; // Avoid negative IDs
    }
}
