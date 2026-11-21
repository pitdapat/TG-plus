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

        long receipt = PushDiagnostics.received(time, message.getPriority(), message.getOriginalPriority(), appState());
        try {
            PushListenerController.processRemoteMessage(PushListenerController.PUSH_TYPE_FIREBASE, data.get("p"), time);
        } finally {
            PushDiagnostics.callbackFinished(receipt);
        }
    }

    /** App, screen and live-connection state when a push arrives; no private data. */
    private static String appState() {
        String state;
        try {
            state = (ApplicationLoader.mainInterfacePaused ? "app=background" : "app=foreground")
                    + (ApplicationLoader.isScreenOn ? " screen=on" : " screen=off");
            int connection = org.telegram.tgnet.ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            state += " conn=" + connectionName(connection);
        } catch (Throwable e) {
            state = "state=unknown";
        }
        return state;
    }

    private static String connectionName(int state) {
        switch (state) {
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateConnecting: return "connecting";
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateWaitingForNetwork: return "waiting-for-network";
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected: return "connected";
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateConnectingToProxy: return "connecting-to-proxy";
            case org.telegram.tgnet.ConnectionsManager.ConnectionStateUpdating: return "updating";
            default: return Integer.toString(state);
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
