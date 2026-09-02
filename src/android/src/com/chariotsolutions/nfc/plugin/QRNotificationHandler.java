package com.chariotsolutions.nfc.plugin;

import static android.app.Notification.VISIBILITY_PUBLIC;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;


import org.json.JSONException;
import org.json.JSONObject;

import com.onesignal.notifications.IActionButton;
import com.onesignal.notifications.IDisplayableMutableNotification;
import com.onesignal.notifications.INotificationReceivedEvent;
import com.onesignal.notifications.INotificationServiceExtension;

import com.chariotsolutions.nfc.plugin.IncomingCallActivity;

import java.util.Timer;
import java.util.TimerTask;

public class QRNotificationHandler extends BroadcastReceiver implements INotificationServiceExtension{

    // Dedupe by connection_id. OPPO/MIUI's WorkManager can cancel in-flight OneSignal
    // workers, which causes OneSignal to retry the same push. Without this, the same
    // call rings 3-4 times. Also used to suppress retry rings after the user ends a call.
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> processedCalls =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEDUPE_WINDOW_MS = 600000L; // 10 min

    private Vibrator vibrator = null;
    private Context context;
    @Override
    public void onNotificationReceived(INotificationReceivedEvent notificationReceivedEvent) {
        context = notificationReceivedEvent.getContext();
        IDisplayableMutableNotification notification = notificationReceivedEvent.getNotification();

        JSONObject data = notification.getAdditionalData();
        Log.i("OneSignalExample", "Received Notification Data: " + data);

        try {
            if (data.getString("type").equals("video_stream")) {
                boolean isControlEvent = data.has("event")
                        && (data.getString("event").equals("answered")
                            || data.getString("event").equals("ended"));

                // Dedupe: cleanup stale entries, then check this push against processed set.
                String connId = data.optString("connection_id", "");
                long nowMs = System.currentTimeMillis();
                processedCalls.entrySet().removeIf(
                        e -> nowMs - e.getValue() > DEDUPE_WINDOW_MS);

                // For RING pushes: if we've already processed this connection_id (or the call
                // was ended/answered), drop it. This blocks WorkManager-retry phantom rings.
                if (!isControlEvent && !connId.isEmpty()
                        && processedCalls.containsKey(connId)) {
                    Log.w("QRNotifDebug", "duplicate ring connId=" + connId
                            + " (already processed " + (nowMs - processedCalls.get(connId))
                            + "ms ago), dropping");
                    notificationReceivedEvent.preventDefault();
                    notificationReceivedEvent.preventDefault(true);
                    return;
                }

                // Drop stale ring pushes (queued deliveries from when app was offline).
                if (!isControlEvent && data.has("start_time")) {
                    long startTime = data.getLong("start_time");
                    long age = nowMs - startTime;
                    if (age > 60000) {
                        Log.w("QRNotifDebug", "ignoring stale video_stream push, age="
                                + age + "ms notif_id=" + data.optInt("notif_id"));
                        notificationReceivedEvent.preventDefault();
                        notificationReceivedEvent.preventDefault(true);
                        return;
                    }
                }
                if (isControlEvent) {
                    NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                    if (data.has("notif_id")) {
                        notificationManager.cancel(data.getInt("notif_id"));
                    }
                    if (data.getString("event").equals("answered")) {
                        Notification.Builder notifBuilder = new Notification.Builder(context)
                                .setSmallIcon(_getResource(context, "ic_launcher", "mipmap"))
                                .setContentTitle("Call Answered")
                                .setContentText("Call answered by "+ data.getString("answered_by_username"))
                                .setOngoing(true)
                                .setCategory(NotificationCompat.CATEGORY_CALL)
                                .setAutoCancel(true);
                        Notification mNotification = notifBuilder.build();
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                            mNotification.flags |= Notification.FLAG_INSISTENT;
                        }
//                        notificationManager.notify((int) (System.currentTimeMillis() & 0xfffffff) + 22, mNotification);
                    }
                    // Mark this call as ended so any WorkManager-retry ring for the same
                    // connection_id gets dropped by the dedupe check above.
                    if (!connId.isEmpty()) {
                        processedCalls.put(connId, System.currentTimeMillis());
                    }
                    // Release OneSignal worker — control events never display a OS notification.
                    notificationReceivedEvent.preventDefault();
                    notificationReceivedEvent.preventDefault(true);
                } else {

                    int NOTIFICATION_ID = data.getInt("notif_id");

                    Person incomingCaller = null;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        incomingCaller = new Person.Builder()
                                .setName("Visitor")
                                .setImportant(true)
                                .build();
                    } else {
//                        notificationReceivedEvent.complete(notification);
                        return;
                    }

                    Intent notificationIntent = new Intent(context.getApplicationContext(), IncomingCallActivity.class);
                    notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    // the activity from a service
                    notificationIntent.setAction(Intent.ACTION_MAIN);
                    notificationIntent.addCategory(Intent.CATEGORY_LAUNCHER);


                    Intent answerMainIntent = new Intent(context, NfcActivity.class);
                    answerMainIntent.putExtra("notification_id", NOTIFICATION_ID);
                    answerMainIntent.putExtra("data", data.toString());


                    Intent contentMainIntent = new Intent(context, IncomingCallActivity.class);
                    contentMainIntent.putExtra("notification_id", NOTIFICATION_ID);
                    contentMainIntent.putExtra("data", data.toString());


                    Intent declineMainIntent = new Intent(context, QRNotificationHandler.class);
                    declineMainIntent.putExtra("notification_id", NOTIFICATION_ID);

                    final PendingIntent declineIntent = PendingIntent.getBroadcast(context, NOTIFICATION_ID, declineMainIntent,
                            PendingIntent.FLAG_MUTABLE);
                    final PendingIntent answerIntent = PendingIntent.getActivity(context, NOTIFICATION_ID - 20, answerMainIntent, PendingIntent.FLAG_MUTABLE);
                    final PendingIntent contentIntent = PendingIntent.getActivity(context, NOTIFICATION_ID - 35, contentMainIntent, PendingIntent.FLAG_IMMUTABLE);

                    final NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

                    // Create a new high-priority channel with bypassDnd=true. The original
                    // qr_video channel was created without bypassDnd, and channel settings are
                    // immutable after creation. bypassDnd is one of the three escape hatches
                    // from AOSP's "recently noisy" cooldown (alongside isCall and CallStyle).
                    final String CALL_CHANNEL_ID = "qr_video_v2";
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (notificationManager.getNotificationChannel(CALL_CHANNEL_ID) == null) {
                            NotificationChannel callCh = new NotificationChannel(
                                    CALL_CHANNEL_ID, "Incoming Video Call",
                                    NotificationManager.IMPORTANCE_HIGH);
                            callCh.setDescription("QR Video Call Alert");
                            callCh.setBypassDnd(true);
                            callCh.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                            AudioAttributes callAudioAttrs = new AudioAttributes.Builder()
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                                    .build();
                            callCh.setSound(
                                    Uri.parse("android.resource://" + context.getPackageName() + "/raw/longbell"),
                                    callAudioAttrs);
                            callCh.enableVibration(true);
                            callCh.enableLights(true);
                            notificationManager.createNotificationChannel(callCh);
                        }
                    }

                    final String DBG_TAG = "QRNotifDebug";
                    final long dbg_t0 = System.currentTimeMillis();
                    final int dbg_osId = notification.getAndroidNotificationId();
                    Log.d(DBG_TAG, "---- incoming qr_video push ----");
                    Log.d(DBG_TAG, "device=" + Build.MANUFACTURER + "/" + Build.BRAND + "/" + Build.MODEL
                            + " sdk=" + Build.VERSION.SDK_INT + " osId=" + dbg_osId + " ourId=" + NOTIFICATION_ID);

                    // Clear any pre-existing noise from OneSignal restore / fcm fallback / stray qr_video
                    // notifications before OneSignal posts the new one. These are what trigger
                    // NotifAttentionHelper's "Muting recently noisy" and swallow our sound.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            android.service.notification.StatusBarNotification[] active =
                                    notificationManager.getActiveNotifications();
                            for (android.service.notification.StatusBarNotification sbn : active) {
                                String ch = sbn.getNotification().getChannelId();
                                if ("restored_OS_notifications".equals(ch)
                                        || "fcm_fallback_notification_channel".equals(ch)
                                        || ("qr_video".equals(ch) && sbn.getId() != dbg_osId)) {
                                    Log.d(DBG_TAG, "clearing stale id=" + sbn.getId() + " channel=" + ch);
                                    notificationManager.cancel(sbn.getId());
                                }
                            }
                        } catch (Exception e) {
                            Log.e(DBG_TAG, "stale cleanup failed: " + e);
                        }
                    }

                    // Mark call as processed so WorkManager-retry deliveries of the same push
                    // get dropped by the dedupe check above.
                    if (!connId.isEmpty()) {
                        processedCalls.put(connId, System.currentTimeMillis());
                    }

                    // Prevent OneSignal from posting its own notification — we post ours with
                    // Notification.CallStyle which AOSP's NotificationAttentionHelper exempts from
                    // the "Muting recently noisy" anti-spam rule.
                    // Calling preventDefault then preventDefault(true) wakes OneSignal's
                    // displayWaiter with false, releasing the worker immediately instead of
                    // blocking for the 30s timeout (which delays and duplicates subsequent pushes).
                    notificationReceivedEvent.preventDefault();
                    notificationReceivedEvent.preventDefault(true);

                    Notification.Builder notifBuilder;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        // API 31+: use framework CallStyle — bypasses recently-noisy mute.
                        notifBuilder = new Notification.Builder(context, CALL_CHANNEL_ID)
                                .setSmallIcon(_getResource(context, "ic_launcher", "mipmap"))
                                .setContentIntent(contentIntent)
                                .setFullScreenIntent(contentIntent, true)
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true)
                                .setTimeoutAfter(45000)
                                .setAutoCancel(true)
                                .setVisibility(VISIBILITY_PUBLIC)
                                .setStyle(Notification.CallStyle.forIncomingCall(
                                        incomingCaller, declineIntent, answerIntent));
                        Log.d(DBG_TAG, "built CallStyle notification at t="
                                + (System.currentTimeMillis() - dbg_t0) + "ms");
                    } else {
                        // API 28-30 fallback: no CallStyle available, best-effort.
                        notifBuilder = new Notification.Builder(context, CALL_CHANNEL_ID)
                                .setContentIntent(contentIntent)
                                .setSmallIcon(_getResource(context, "ic_launcher", "mipmap"))
                                .setContentTitle("Incoming Call")
                                .setContentText("Answer the call to see who")
                                .setOngoing(true)
                                .setCategory(Notification.CATEGORY_CALL)
                                .setTimeoutAfter(45000)
                                .setAutoCancel(true)
                                .setVisibility(VISIBILITY_PUBLIC)
                                .addPerson(incomingCaller)
                                .addAction(android.R.drawable.sym_call_missed, "Decline", declineIntent)
                                .addAction(android.R.drawable.sym_action_call, "Answer", answerIntent);
                    }

                    Notification callNotification = notifBuilder.build();
                    notificationManager.notify(NOTIFICATION_ID, callNotification);
                    Log.d(DBG_TAG, "posted ourId=" + NOTIFICATION_ID + " at t="
                            + (System.currentTimeMillis() - dbg_t0) + "ms");

                    // Verify channel config once (cheap, helps catch regressions).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        try {
                            NotificationChannel dbg_ch = notificationManager.getNotificationChannel(CALL_CHANNEL_ID);
                            if (dbg_ch == null) {
                                Log.e(DBG_TAG, CALL_CHANNEL_ID + " channel NOT FOUND on this device");
                            } else {
                                Log.d(DBG_TAG, CALL_CHANNEL_ID + " importance=" + dbg_ch.getImportance()
                                        + " sound=" + dbg_ch.getSound()
                                        + " bypassDnd=" + dbg_ch.canBypassDnd());
                            }
                            Log.d(DBG_TAG, "interruptionFilter="
                                    + notificationManager.getCurrentInterruptionFilter());
                        } catch (Exception e) {
                            Log.e(DBG_TAG, "channel check failed: " + e);
                        }
                    }

                    // Post-check: confirm our notification made it to the shade with the right config.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        final int dbg_ourId = NOTIFICATION_ID;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(new Runnable() {
                                    @Override public void run() {
                                        try {
                                            boolean found = false;
                                            for (android.service.notification.StatusBarNotification sbn
                                                    : notificationManager.getActiveNotifications()) {
                                                if (sbn.getId() == dbg_ourId) {
                                                    found = true;
                                                    String tpl = sbn.getNotification().extras != null
                                                            ? sbn.getNotification().extras.getString(Notification.EXTRA_TEMPLATE)
                                                            : null;
                                                    Log.d(DBG_TAG, "POST-CHECK id=" + sbn.getId()
                                                            + " channel=" + sbn.getNotification().getChannelId()
                                                            + " category=" + sbn.getNotification().category
                                                            + " sound=" + sbn.getNotification().sound
                                                            + " template=" + tpl
                                                            + " flags=0x" + Integer.toHexString(sbn.getNotification().flags));
                                                }
                                            }
                                            if (!found) {
                                                Log.e(DBG_TAG, "POST-CHECK: ourId=" + dbg_ourId
                                                        + " NOT in active — OS dropped it");
                                            }
                                        } catch (Exception e) {
                                            Log.e(DBG_TAG, "post-check failed: " + e);
                                        }
                                    }
                                }, 800);
                    }

//                startForeground(202, newNotification);
//                notificationReceivedEvent.complete();
//                    startForeground(1124, notifBuilder.build());
//                    notificationReceivedEvent.complete(null);
                }

            } else if (!data.optString("schedule_id", "").isEmpty()
                    || !data.optString("schedule_scene_id", "").isEmpty()) {
                // KIOT schedule/routine reminder (carries schedule_id /
                // schedule_scene_id). This MUST display (with its "Cancel For Once"
                // button), so it takes priority over the wake-up-ping suppression
                // below. Repoint the action button(s) at the misc plugin's
                // NotificationActionReceiver so the tap runs natively without opening
                // the app, then let OneSignal display it.
                //
                // WHY THIS LIVES HERE: OneSignal allows only ONE
                // NotificationServiceExtension per app. This handler owns it (for
                // video_stream calls), so the misc plugin can't also register one —
                // its schedule-cancel button-rewrite is merged in here. We target
                // NotificationActionReceiver by ComponentName (string package + class)
                // + its action, so there is NO compile-time dependency on the misc
                // plugin.
                rewriteScheduleCancelButtons(notificationReceivedEvent, notification, data);
                notificationReceivedEvent.complete(notification);
            } else {
                // Non-video_stream pushes (e.g. type=QR_VIDEO wake-up pings) must not display,
                // otherwise OneSignal's default channel sound primes AttentionHelper's noisy
                // window and mutes the real call that follows 1s later.
                notificationReceivedEvent.preventDefault();
                notificationReceivedEvent.preventDefault(true);
            }
        } catch (Exception ex) {
            // dont do anything
            Log.e("chudu", ex.toString());
//            notificationReceivedEvent.complete(notification);
        }

    }

    // ── Schedule "Cancel For Once" (merged from misc's KiotNotificationExtension) ──

    // Mirrors com.kiot.misc.notifications.NotificationActionReceiver (referenced by
    // ComponentName/action, not by class, to avoid a cross-plugin dependency).
    private static final String KIOT_ACTION_RECEIVER_CLASS =
            "com.kiot.misc.notifications.NotificationActionReceiver";
    private static final String KIOT_ACTION_BUTTON_TAP = "io.kiot.notification.ACTION_BUTTON_TAP";
    private static final String KIOT_EXTRA_ACTION_ID        = "kiot_action_id";
    private static final String KIOT_EXTRA_SCHEDULE_ID      = "kiot_schedule_id";
    private static final String KIOT_EXTRA_SCENE_ID         = "kiot_schedule_scene_id";
    private static final String KIOT_EXTRA_NOTIFICATION_ID  = "kiot_notification_id";
    private static final String KIOT_EXTRA_ANDROID_NOTIF_ID = "kiot_android_notif_id";

    private void rewriteScheduleCancelButtons(INotificationReceivedEvent event,
                                              IDisplayableMutableNotification notification,
                                              JSONObject data) {
        try {
            final String scheduleId     = data.optString("schedule_id", "");
            final String sceneId        = data.optString("schedule_scene_id", "");
            final String notificationId = data.optString("notification_id", "");

            // Not a schedule/routine reminder → leave it completely untouched.
            if (scheduleId.isEmpty() && sceneId.isEmpty()) {
                return;
            }

            final java.util.List<com.onesignal.notifications.IActionButton> buttons =
                    notification.getActionButtons();
            if (buttons == null || buttons.isEmpty()) {
                return;
            }

            final Context ctx = event.getContext().getApplicationContext();
            final int androidNotifId = notification.getAndroidNotificationId();

            notification.setExtender(new androidx.core.app.NotificationCompat.Extender() {
                @Override
                public androidx.core.app.NotificationCompat.Builder extend(
                        androidx.core.app.NotificationCompat.Builder builder) {
                    try {
                        // Drop OneSignal's activity-launching actions and re-add the
                        // same buttons pointed at the misc plugin's receiver instead.
                        builder.clearActions();

                        for (int i = 0; i < buttons.size(); i++) {
                            com.onesignal.notifications.IActionButton button = buttons.get(i);
                            if (button == null) continue;

                            Intent intent = new Intent(KIOT_ACTION_BUTTON_TAP);
                            intent.setComponent(new android.content.ComponentName(
                                    ctx.getPackageName(), KIOT_ACTION_RECEIVER_CLASS));
                            intent.putExtra(KIOT_EXTRA_ACTION_ID, button.getId());
                            intent.putExtra(KIOT_EXTRA_SCHEDULE_ID, scheduleId);
                            intent.putExtra(KIOT_EXTRA_SCENE_ID, sceneId);
                            intent.putExtra(KIOT_EXTRA_NOTIFICATION_ID, notificationId);
                            intent.putExtra(KIOT_EXTRA_ANDROID_NOTIF_ID, androidNotifId);

                            // Unique per notification AND per button.
                            int requestCode = androidNotifId * 31 + i;

                            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                flags |= PendingIntent.FLAG_IMMUTABLE;
                            }

                            PendingIntent pendingIntent =
                                    PendingIntent.getBroadcast(ctx, requestCode, intent, flags);

                            // Icon 0 — Android 7+ doesn't render action button icons.
                            builder.addAction(0, button.getText(), pendingIntent);
                        }
                    } catch (Throwable t) {
                        Log.e("KiotNotifExt", "Failed to rewrite schedule action buttons", t);
                    }
                    return builder;
                }
            });
        } catch (Throwable t) {
            Log.e("KiotNotifExt", "rewriteScheduleCancelButtons failed", t);
        }
    }


    private int _getResource(Context ctx, String name, String type) {
        String package_name = ctx.getPackageName();
        Resources resources = ctx.getResources();
        return resources.getIdentifier(name, type, package_name);
    }

//    private static PendingIntent askUserIntent(Context context, String topicName, int seq, boolean audioOnly) {
//        Intent intent = new Intent(CallActivity.INTENT_ACTION_CALL_INCOMING, null);
//        intent.setFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION
//                | Intent.FLAG_ACTIVITY_NEW_TASK
//                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
//        intent.putExtra(Const.INTENT_EXTRA_TOPIC, topicName)
//                .putExtra(Const.INTENT_EXTRA_SEQ, seq)
//                .putExtra(Const.INTENT_EXTRA_CALL_AUDIO_ONLY, audioOnly);
//        intent.setClass(context, CallActivity.class);
//        return PendingIntent.getActivity(context, 101, intent,
//                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
//    }



    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            int noti_id = intent.getIntExtra("notification_id", -1);

            if (noti_id > 0) {
                NotificationManager notificationManager = (NotificationManager) context
                        .getSystemService(Context.NOTIFICATION_SERVICE);

                notificationManager.cancel(noti_id);
            }

        }
    }
}