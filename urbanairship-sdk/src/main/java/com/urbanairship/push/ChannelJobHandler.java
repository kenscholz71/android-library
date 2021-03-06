/* Copyright 2016 Urban Airship and Contributors */

package com.urbanairship.push;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;

import com.urbanairship.Logger;
import com.urbanairship.PreferenceDataStore;
import com.urbanairship.UAirship;
import com.urbanairship.http.Response;
import com.urbanairship.job.Job;
import com.urbanairship.job.JobDispatcher;
import com.urbanairship.json.JsonException;
import com.urbanairship.json.JsonValue;
import com.urbanairship.util.UAHttpStatusUtil;
import com.urbanairship.util.UAStringUtil;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import static com.urbanairship.push.TagUtils.migrateTagGroups;

/**
 * Job handler for channel registration
 */
class ChannelJobHandler {

    /**
     * Action to update pending channel tag groups.
     */
    static final String ACTION_APPLY_TAG_GROUP_CHANGES = "com.urbanairship.push.ACTION_APPLY_TAG_GROUP_CHANGES";

    /**
     * Action to perform update request for pending tag group changes.
     */
    static final String ACTION_UPDATE_TAG_GROUPS = "com.urbanairship.push.ACTION_UPDATE_TAG_GROUPS";

    /**
     * Action to start channel and push registration.
     */
    static final String ACTION_START_REGISTRATION = "com.urbanairship.push.ACTION_START_REGISTRATION";

    /**
     * Action notifying the service that registration has finished.
     */
    static final String ACTION_REGISTRATION_FINISHED = "com.urbanairship.push.ACTION_REGISTRATION_FINISHED";

    /**
     * Action to update channel registration.
     */
    static final String ACTION_UPDATE_PUSH_REGISTRATION = "com.urbanairship.push.ACTION_UPDATE_PUSH_REGISTRATION";

    /**
     * Action to update channel registration.
     */
    static final String ACTION_UPDATE_CHANNEL_REGISTRATION = "com.urbanairship.push.ACTION_UPDATE_CHANNEL_REGISTRATION";


    /**
     * Data store key for the last successfully registered channel payload.
     */
    private static final String LAST_REGISTRATION_PAYLOAD_KEY = "com.urbanairship.push.LAST_REGISTRATION_PAYLOAD";

    /**
     * Data store key for the time in milliseconds of last successfully channel registration.
     */
    private static final String LAST_REGISTRATION_TIME_KEY = "com.urbanairship.push.LAST_REGISTRATION_TIME";

    /**
     * Key for storing the pending channel add tags changes in the {@link PreferenceDataStore}.
     */
    static final String PENDING_ADD_TAG_GROUPS_KEY = "com.urbanairship.push.PENDING_ADD_TAG_GROUPS";

    /**
     * Key for storing the pending channel remove tags changes in the {@link PreferenceDataStore}.
     */
    static final String PENDING_REMOVE_TAG_GROUPS_KEY = "com.urbanairship.push.PENDING_REMOVE_TAG_GROUPS";

    /**
     * Key for storing the pending tag group mutations in the {@link PreferenceDataStore}.
     */
    static final String PENDING_TAG_GROUP_MUTATIONS_KEY = "com.urbanairship.push.PENDING_TAG_GROUP_MUTATIONS";

    /**
     * Response body key for the channel ID.
     */
    private static final String CHANNEL_ID_KEY = "channel_id";

    /**
     * Response header key for the channel location.
     */
    private static final String CHANNEL_LOCATION_KEY = "Location";

    /**
     * Max time between channel registration updates.
     */
    private static final long CHANNEL_REREGISTRATION_INTERVAL_MS = 24 * 60 * 60 * 1000; //24H

    private static boolean isPushRegistering = false;
    private static boolean isRegistrationStarted = false;
    private final UAirship airship;
    private final PushManager pushManager;
    private final ChannelApiClient channelClient;
    private final NamedUser namedUser;
    private final Context context;
    private final PreferenceDataStore dataStore;
    private final JobDispatcher jobDispatcher;


    /**
     * Default constructor.
     *
     * @param context The application context.
     * @param airship The airship instance.
     * @param dataStore The preference data store.
     */
    ChannelJobHandler(Context context, UAirship airship, PreferenceDataStore dataStore) {
        this(context, airship, dataStore, JobDispatcher.shared(context), new ChannelApiClient(airship.getPlatformType(), airship.getAirshipConfigOptions()));
    }

    @VisibleForTesting
    ChannelJobHandler(Context context, UAirship airship, PreferenceDataStore dataStore,
                      JobDispatcher jobDispatcher, ChannelApiClient channelClient) {
        this.context = context;
        this.dataStore = dataStore;
        this.channelClient = channelClient;
        this.airship = airship;
        this.pushManager = airship.getPushManager();
        this.namedUser = airship.getNamedUser();
        this.jobDispatcher = jobDispatcher;
    }

    /**
     * Called to handle jobs from {@link PushManager#onPerformJob(UAirship, Job)}.
     *
     * @param job The airship job.
     * @return The job result.
     */
    @Job.JobResult
    protected int performJob(Job job) {
        switch (job.getAction()) {
            case ACTION_START_REGISTRATION:
                return onStartRegistration();

            case ACTION_UPDATE_PUSH_REGISTRATION:
                return onUpdatePushRegistration();

            case ACTION_REGISTRATION_FINISHED:
                return onRegistrationFinished(job);

            case ACTION_UPDATE_CHANNEL_REGISTRATION:
                return onUpdateChannelRegistration();

            case ACTION_APPLY_TAG_GROUP_CHANGES:
                return onApplyTagGroupChanges(job);

            case ACTION_UPDATE_TAG_GROUPS:
                return onUpdateTagGroup();
        }

        return Job.JOB_FINISHED;
    }

    /**
     * Starts the registration process. Will either start the push registration flow or channel registration
     * depending on if push registration is needed.
     *
     * @return The job result.
     */
    @Job.JobResult
    private int onStartRegistration() {
        if (isRegistrationStarted) {
            // Happens anytime we have multiple processes
            return Job.JOB_FINISHED;
        }

        isRegistrationStarted = true;

        PushProvider provider = pushManager.getPushProvider();

        if (provider != null && provider.isAvailable(context)) {
            isPushRegistering = true;

            // Update the push registration
            Job job = Job.newBuilder(ACTION_UPDATE_PUSH_REGISTRATION)
                         .setAirshipComponent(PushManager.class)
                         .build();

            jobDispatcher.dispatch(job);
        } else {
            // Update the channel registration
            Job job = Job.newBuilder(ACTION_UPDATE_CHANNEL_REGISTRATION)
                         .setAirshipComponent(PushManager.class)
                         .build();

            jobDispatcher.dispatch(job);
        }

        return Job.JOB_FINISHED;
    }

    /**
     * Updates the push registration.
     *
     * @return The job result.
     */
    @Job.JobResult
    private int onUpdatePushRegistration() {
        isPushRegistering = true;

        PushProvider provider = pushManager.getPushProvider();
        String currentToken = pushManager.getRegistrationToken();

        if (provider == null || !provider.isAvailable(context)) {
            Logger.error("Registration failed. Push provider unavailable: " + provider);
            isPushRegistering = false;
        } else if (UAStringUtil.isEmpty(currentToken) || provider.shouldUpdateRegistration(context, currentToken)) {
            pushManager.setRegistrationToken(null);

            try {
                provider.startRegistration(context);
            } catch (IOException | SecurityException e) {
                Logger.error("Push registration failed, will retry. Error: " + e.getMessage());
                return Job.JOB_RETRY;
            }
        } else {
            isPushRegistering = false;
        }

        if (!isPushRegistering) {
            // Update the channel registration
            Job job = Job.newBuilder(ACTION_UPDATE_CHANNEL_REGISTRATION)
                         .setAirshipComponent(PushManager.class)
                         .build();

            jobDispatcher.dispatch(job);
        }

        return Job.JOB_FINISHED;
    }

    /**
     * Called when registration is finished.
     *
     * @param job The registration job.
     * @return The job result.
     */
    @Job.JobResult
    private int onRegistrationFinished(@NonNull Job job) {
        Bundle extras = job.getExtras();
        String providerClass = extras.getString(PushProviderBridge.EXTRA_PROVIDER_CLASS);

        PushProvider provider = pushManager.getPushProvider();

        if (provider == null || !provider.getClass().toString().equals(providerClass)) {
            Logger.error("Received registration finished callback from unexpected push provider: " + providerClass);
            return Job.JOB_FINISHED;
        }

        if (!provider.isAvailable(context)) {
            Logger.error("Received registration finished callback when provider is unavailable. Ignoring.");
            return Job.JOB_FINISHED;
        }

        String registrationID = extras.getString(PushProviderBridge.EXTRA_REGISTRATION_ID);
        if (registrationID != null) {
            Logger.info("Push registration successful. Registration ID: " + registrationID);
            pushManager.setRegistrationToken(registrationID);
        } else {
            Logger.error("Push registration failed for provider: " + provider);
        }

        isPushRegistering = false;

        // Update the channel registration
        Job channelUpdateJob = Job.newBuilder(ACTION_UPDATE_CHANNEL_REGISTRATION)
                                  .setAirshipComponent(PushManager.class)
                                  .build();

        jobDispatcher.dispatch(channelUpdateJob);

        return Job.JOB_FINISHED;
    }

    /**
     * Updates channel registration.
     *
     * @return The job result.
     */
    @Job.JobResult
    private int onUpdateChannelRegistration() {
        if (isPushRegistering) {
            Logger.verbose("ChannelJobHandler - Push registration in progress, skipping registration update.");
            return Job.JOB_FINISHED;
        }

        Logger.verbose("ChannelJobHandler - Performing channel registration.");

        ChannelRegistrationPayload payload = pushManager.getNextChannelRegistrationPayload();
        String channelId = pushManager.getChannelId();
        URL channelLocation = getChannelLocationUrl();

        if (channelLocation != null && !UAStringUtil.isEmpty(channelId)) {
            return updateChannel(channelLocation, payload);
        } else {
            return createChannel(payload);
        }
    }

    /**
     * Updates a channel.
     *
     * @param channelLocation Channel location.
     * @param payload The ChannelRegistrationPayload payload.
     * @return The job result.
     */
    @Job.JobResult
    private int updateChannel(@NonNull URL channelLocation, @NonNull ChannelRegistrationPayload payload) {
        if (!shouldUpdateRegistration(payload)) {
            Logger.verbose("ChannelJobHandler - Channel already up to date.");
            return Job.JOB_FINISHED;
        }

        Response response = channelClient.updateChannelWithPayload(channelLocation, payload);

        // 5xx
        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Server error occurred, so retry later.
            Logger.error("Channel registration failed, will retry.");
            sendRegistrationFinishedBroadcast(false, false);
            return Job.JOB_RETRY;
        }

        // 2xx (API should only return 200 or 201)
        if (UAHttpStatusUtil.inSuccessRange(response.getStatus())) {
            Logger.info("Channel registration succeeded with status: " + response.getStatus());

            // Set the last registration payload and time then notify registration succeeded
            setLastRegistrationPayload(payload);
            sendRegistrationFinishedBroadcast(true, false);
            return Job.JOB_FINISHED;
        }

        // 409
        if (response.getStatus() == HttpURLConnection.HTTP_CONFLICT) {
            // Delete channel and register again.
            pushManager.setChannel(null, null);

            // Update registration
            Job channelUpdateJob = Job.newBuilder(ACTION_UPDATE_CHANNEL_REGISTRATION)
                                      .setAirshipComponent(PushManager.class)
                                      .build();

            jobDispatcher.dispatch(channelUpdateJob);

            return Job.JOB_FINISHED;
        }

        // Unexpected status code
        Logger.error("Channel registration failed with status: " + response.getStatus());
        sendRegistrationFinishedBroadcast(false, false);
        return Job.JOB_FINISHED;
    }

    /**
     * Actually creates the channel.
     *
     * @param payload The ChannelRegistrationPayload payload.
     * @return The job result.
     */
    @Job.JobResult
    private int createChannel(@NonNull ChannelRegistrationPayload payload) {

        if (pushManager.isChannelCreationDelayEnabled()) {
            Logger.info("Channel registration is currently disabled.");
            return Job.JOB_FINISHED;
        }

        Response response = channelClient.createChannelWithPayload(payload);

        // 5xx
        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            // Server error occurred, so retry later.
            Logger.error("Channel registration failed, will retry.");
            sendRegistrationFinishedBroadcast(false, true);
            return Job.JOB_RETRY;

        }

        // 200 or 201
        if (response.getStatus() == HttpURLConnection.HTTP_OK || response.getStatus() == HttpURLConnection.HTTP_CREATED) {
            String channelId = null;
            try {
                channelId = JsonValue.parseString(response.getResponseBody()).optMap().opt(CHANNEL_ID_KEY).getString();
            } catch (JsonException e) {
                Logger.debug("Unable to parse channel registration response body: " + response.getResponseBody(), e);
            }

            String channelLocation = response.getResponseHeader(CHANNEL_LOCATION_KEY);

            if (!UAStringUtil.isEmpty(channelLocation) && !UAStringUtil.isEmpty(channelId)) {
                Logger.info("Channel creation succeeded with status: " + response.getStatus() + " channel ID: " + channelId);

                // Set the last registration payload and time then notify registration succeeded
                pushManager.setChannel(channelId, channelLocation);
                setLastRegistrationPayload(payload);
                sendRegistrationFinishedBroadcast(true, true);

                if (response.getStatus() == HttpURLConnection.HTTP_OK) {
                    // 200 means channel previously existed and a named user may be associated to it.
                    if (airship.getAirshipConfigOptions().clearNamedUser) {
                        // If clearNamedUser is true on re-install, then disassociate if necessary
                        namedUser.disassociateNamedUserIfNull();
                    }
                }

                // If setId was called before channel creation, update named user
                namedUser.dispatchNamedUserUpdateJob();
                pushManager.updateRegistration();

                pushManager.startUpdateTagsService();
                airship.getInbox().getUser().update(true);

                // Send analytics event
                airship.getAnalytics().uploadEvents();

            } else {
                Logger.error("Failed to register with channel ID: " + channelId +
                        " channel location: " + channelLocation);
                sendRegistrationFinishedBroadcast(false, true);
            }

            return Job.JOB_FINISHED;
        }

        // Unexpected status code
        Logger.error("Channel registration failed with status: " + response.getStatus());
        sendRegistrationFinishedBroadcast(false, true);

        return Job.JOB_FINISHED;
    }

    /**
     * Check the specified payload and last registration time to determine if registration is required
     *
     * @param payload The channel registration payload
     * @return <code>True</code> if registration is required, <code>false</code> otherwise
     */
    private boolean shouldUpdateRegistration(@NonNull ChannelRegistrationPayload payload) {
        // check time and payload
        ChannelRegistrationPayload lastSuccessPayload = getLastRegistrationPayload();
        long timeSinceLastRegistration = (System.currentTimeMillis() - getLastRegistrationTime());
        return (!payload.equals(lastSuccessPayload)) ||
                (timeSinceLastRegistration >= CHANNEL_REREGISTRATION_INTERVAL_MS);
    }

    /**
     * Get the channel location as a URL
     *
     * @return The channel location URL
     */
    @Nullable
    private URL getChannelLocationUrl() {
        String channelLocationString = pushManager.getChannelLocation();
        if (!UAStringUtil.isEmpty(channelLocationString)) {
            try {
                return new URL(channelLocationString);
            } catch (MalformedURLException e) {
                Logger.error("Channel location from preferences was invalid: " + channelLocationString, e);
            }
        }

        return null;
    }

    /**
     * Sets the last registration payload and registration time. The last payload and registration
     * time are used to prevent duplicate channel updates.
     *
     * @param channelPayload A ChannelRegistrationPayload.
     */
    private void setLastRegistrationPayload(ChannelRegistrationPayload channelPayload) {
        dataStore.put(LAST_REGISTRATION_PAYLOAD_KEY, channelPayload);
        dataStore.put(LAST_REGISTRATION_TIME_KEY, System.currentTimeMillis());
    }

    /**
     * Gets the last registration payload
     *
     * @return a ChannelRegistrationPayload
     */
    @Nullable
    private ChannelRegistrationPayload getLastRegistrationPayload() {
        String payloadJSON = dataStore.getString(LAST_REGISTRATION_PAYLOAD_KEY, null);

        try {
            return ChannelRegistrationPayload.parseJson(payloadJSON);
        } catch (JsonException e) {
            Logger.error("ChannelJobHandler - Failed to parse payload from JSON.", e);
            return null;
        }
    }

    /**
     * Get the last registration time
     *
     * @return the last registration time
     */
    private long getLastRegistrationTime() {
        long lastRegistrationTime = dataStore.getLong(LAST_REGISTRATION_TIME_KEY, 0L);

        // If its in the future reset it
        if (lastRegistrationTime > System.currentTimeMillis()) {
            dataStore.put(LAST_REGISTRATION_TIME_KEY, 0);
            return 0;
        }

        return lastRegistrationTime;
    }

    /**
     * Broadcasts an intent to notify the host application of a registration finished, but
     * only if a receiver is set to get the user-defined intent receiver.
     *
     * @param isSuccess A boolean indicating whether registration succeeded or not.
     * @param isCreateRequest A boolean indicating the channel registration request type - true if
     * the request is of the create type, false otherwise.
     */
    private void sendRegistrationFinishedBroadcast(boolean isSuccess, boolean isCreateRequest) {
        Intent intent = new Intent(PushManager.ACTION_CHANNEL_UPDATED)
                .putExtra(PushManager.EXTRA_CHANNEL_ID, pushManager.getChannelId())
                .putExtra(PushManager.EXTRA_CHANNEL_CREATE_REQUEST, isCreateRequest)
                .addCategory(UAirship.getPackageName())
                .setPackage(UAirship.getPackageName());

        if (!isSuccess) {
            intent.putExtra(PushManager.EXTRA_ERROR, true);
        }

        context.sendBroadcast(intent, UAirship.getUrbanAirshipPermission());
    }


    /**
     * Handles performing any tag group requests if any pending tag group changes are available.
     *
     * @return The job result.
     */
    @Job.JobResult
    private int onUpdateTagGroup() {
        migrateTagGroups(dataStore, PENDING_ADD_TAG_GROUPS_KEY, PENDING_REMOVE_TAG_GROUPS_KEY, PENDING_TAG_GROUP_MUTATIONS_KEY);

        String channelId = pushManager.getChannelId();
        if (channelId == null) {
            Logger.verbose("Failed to update channel tags due to null channel ID.");
            return Job.JOB_FINISHED;
        }

        List<TagGroupsMutation> mutations = TagGroupsMutation.fromJsonList(dataStore.getJsonValue(PENDING_TAG_GROUP_MUTATIONS_KEY).optList());

        if (mutations.isEmpty()) {
            Logger.verbose( "ChannelJobHandler - No pending tag group updates. Skipping update.");
            return Job.JOB_FINISHED;
        }

        Response response = channelClient.updateTagGroups(channelId, mutations.get(0));

        // 5xx or no response
        if (response == null || UAHttpStatusUtil.inServerErrorRange(response.getStatus())) {
            Logger.info("ChannelJobHandler - Failed to update tag groups, will retry later.");
            return Job.JOB_RETRY;
        }

        int status = response.getStatus();
        Logger.info("ChannelJobHandler - Update tag groups finished with status: " + status);
        if (UAHttpStatusUtil.inSuccessRange(status) || status == HttpURLConnection.HTTP_FORBIDDEN || status == HttpURLConnection.HTTP_BAD_REQUEST) {
            mutations.remove(0);

            if (mutations.isEmpty()) {
                dataStore.remove(PENDING_TAG_GROUP_MUTATIONS_KEY);
            } else {
                dataStore.put(PENDING_TAG_GROUP_MUTATIONS_KEY, JsonValue.wrapOpt(mutations));
            }

            if (!mutations.isEmpty()) {
                Job updateJob = Job.newBuilder(ACTION_UPDATE_TAG_GROUPS)
                                   .setAirshipComponent(PushManager.class)
                                   .build();

                jobDispatcher.dispatch(updateJob);
            }
        }

        return Job.JOB_FINISHED;
    }

    /**
     * Handles any pending tag group changes.
     *
     * @param job The airship job.
     * @return The job result.
     */
    @Job.JobResult
    private int onApplyTagGroupChanges(Job job) {
        migrateTagGroups(dataStore, PENDING_ADD_TAG_GROUPS_KEY, PENDING_REMOVE_TAG_GROUPS_KEY, PENDING_TAG_GROUP_MUTATIONS_KEY);

        List<TagGroupsMutation> mutations = TagGroupsMutation.fromJsonList(dataStore.getJsonValue(PENDING_TAG_GROUP_MUTATIONS_KEY).optList());
        try {
            JsonValue jsonValue = JsonValue.parseString(job.getExtras().getString(TagGroupsEditor.EXTRA_TAG_GROUP_MUTATIONS));
            mutations.addAll(TagGroupsMutation.fromJsonList(jsonValue.optList()));
        } catch (JsonException e) {
            Logger.error("Failed to parse tag group change:", e);
            return Job.JOB_FINISHED;
        }

        mutations = TagGroupsMutation.collapseMutations(mutations);
        dataStore.put(PENDING_TAG_GROUP_MUTATIONS_KEY, JsonValue.wrapOpt(mutations));

        if (pushManager.getChannelId() != null) {
            Job updateJob = Job.newBuilder(ACTION_UPDATE_TAG_GROUPS)
                               .setAirshipComponent(PushManager.class)
                               .build();

            jobDispatcher.dispatch(updateJob);
        }

        return Job.JOB_FINISHED;
    }
}
