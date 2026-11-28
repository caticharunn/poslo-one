package one.poslo.app;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/** Prima obavijesti dok je aplikacija otvorena (u pozadini ih Android prikazuje sam). */
public class PushService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        getSharedPreferences("poslo", Context.MODE_PRIVATE).edit().putString("fcm_token", token).apply();
    }

    @Override
    public void onMessageReceived(RemoteMessage message) {
        RemoteMessage.Notification n = message.getNotification();
        if (n == null) return;
        String url = message.getData().get("url");
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (url != null) open.putExtra("url", url);
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        MainActivity.ensureChannel(this);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, "poslo")
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2563EB)
            .setContentTitle(n.getTitle())
            .setContentText(n.getBody())
            .setStyle(new NotificationCompat.BigTextStyle().bigText(n.getBody()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify((int) System.currentTimeMillis(), b.build());
    }
}
