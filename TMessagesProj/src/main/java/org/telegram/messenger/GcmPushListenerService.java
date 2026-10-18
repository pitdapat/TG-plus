/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class GcmPushListenerService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage message) {
        Map<String, String> data = message.getData();
        long time = message.getSentTime();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("FCM message received");
        }

        long receipt = PushDiagnostics.received(time, message.getPriority(), message.getOriginalPriority());
        try {
            PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, data.get("p"), time);
        } finally {
            PushDiagnostics.callbackFinished(receipt);
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        PushDiagnostics.tokenReady(token);
        AndroidUtilities.runOnUIThread(() -> {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("FCM token refreshed");
            }
            ApplicationLoader.postInitApplication();
            PushListenerController.sendRegistrationToServer(PushListenerController.PUSH_TYPE_FIREBASE, token);
        });
    }
}
