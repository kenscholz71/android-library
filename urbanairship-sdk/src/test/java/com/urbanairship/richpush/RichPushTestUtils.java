/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.richpush;

import com.urbanairship.TestApplication;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.DateUtils;

import junit.framework.Assert;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class RichPushTestUtils {

    private static final String MCRAP_TEMPLATE = "{\"unread\": true, \"message_sent\": \"2010-09-05 12:13 -0000\"," +
            "\"title\": \"Message title\"," +
            "\"message_url\": \"https://go.urbanairship.com/api/user/some_user_id/messages/message_id\"," +
            "\"message_body_url\": \"https://go.urbanairship.com/api/user/some_user_id/messages/message_id/body/\"," +
            "\"message_read_url\": \"https://go.urbanairship.com/api/user/some_user_id/messages/message_id/read/\"," +
            "\"extra\": {\"some_key\": \"some_value\"},\"content_type\": \"text/html\",\"content_size\": \"128\"}";

    public static void insertMessage(String messageId) {
        insertMessage(messageId, null);
    }

    public static void insertMessage(String messageId, Map<String, String> extras) {
        insertMessage(messageId, extras, false);
    }

    public static void insertMessage(String messageId, Map<String, String> extras, boolean expired) {
        Map<String, Object> payload = new HashMap<>();
        payload.put(RichPushMessage.MESSAGE_ID_KEY, messageId);
        payload.put(RichPushMessage.MESSAGE_BODY_URL_KEY, String.format("https://go.urbanairship.com/api/user/tests/messages/%s/body/", messageId));
        payload.put(RichPushMessage.MESSAGE_READ_URL_KEY, String.format("https://go.urbanairship.com/api/user/tests/messages/%s/read/", messageId));
        payload.put(RichPushMessage.MESSAGE_URL_KEY, String.format("https://go.urbanairship.com/api/user/tests/messages/%s", messageId));
        payload.put(RichPushMessage.TITLE_KEY, String.format("%s title", messageId));
        payload.put(RichPushMessage.UNREAD_KEY, true);
        payload.put(RichPushMessage.MESSAGE_SENT_KEY, DateUtils.createIso8601TimeStamp(System.currentTimeMillis()));

        if (extras != null) {
            payload.put(RichPushMessage.EXTRA_KEY, extras);
        }

        if (expired) {
            payload.put(RichPushMessage.MESSAGE_EXPIRY_KEY, DateUtils.createIso8601TimeStamp(0));
        }

        try {
            RichPushResolver resolver = new RichPushResolver(TestApplication.getApplication());
            resolver.insertMessages(Arrays.asList(JsonValue.wrap(payload)));
        } catch (JsonException e) {
            Assert.fail(e.getMessage());
        }
    }


}
