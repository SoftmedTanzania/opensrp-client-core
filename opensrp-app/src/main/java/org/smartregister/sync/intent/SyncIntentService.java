package org.smartregister.sync.intent;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Pair;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.smartregister.AllConstants;
import org.smartregister.CoreLibrary;
import org.smartregister.R;
import org.smartregister.SyncConfiguration;
import org.smartregister.domain.FetchStatus;
import org.smartregister.domain.Response;
import org.smartregister.domain.SyncEntity;
import org.smartregister.domain.SyncProgress;
import org.smartregister.domain.db.EventClient;
import org.smartregister.receiver.SyncStatusBroadcastReceiver;
import org.smartregister.repository.EventClientRepository;
import org.smartregister.service.HTTPAgent;
import org.smartregister.sync.helper.ECSyncHelper;
import org.smartregister.util.NetworkUtils;
import org.smartregister.util.SyncUtils;
import org.smartregister.util.Utils;
import org.smartregister.view.activity.DrishtiApplication;

import java.text.MessageFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

public class SyncIntentService extends BaseSyncIntentService {
    public static final String SYNC_URL = "/rest/event/sync";
    protected static final int EVENT_PULL_LIMIT = 250;
    protected static final int EVENT_PUSH_LIMIT = 50;
    private static final String ADD_URL = "/rest/event/add";
    private Context context;
    private HTTPAgent httpAgent;
    private SyncUtils syncUtils;
    private long totalRecords;
    private int fetchedRecords = 0;

    public SyncIntentService() {
        super("SyncIntentService");
    }

    public SyncIntentService(String name) {
        super(name);
    }

    protected void init(@NonNull Context context) {
        this.context = context;
        httpAgent = CoreLibrary.getInstance().context().getHttpAgent();
        syncUtils = new SyncUtils(getBaseContext());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        init(getBaseContext());
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        super.onHandleIntent(intent);
        handleSync();
    }

    protected void handleSync() {
        sendSyncStatusBroadcastMessage(FetchStatus.fetchStarted);

        doSync();
    }

    private void doSync() {
        if (!NetworkUtils.isNetworkAvailable()) {
            complete(FetchStatus.noConnection);
            return;
        }

        try {
            boolean hasValidAuthorization = syncUtils.verifyAuthorization();
            boolean isSuccessfulPushSync = false;
            if (hasValidAuthorization || !CoreLibrary.getInstance().getSyncConfiguration().disableSyncToServerIfUserIsDisabled()) {
                isSuccessfulPushSync = pushToServer();
            }

            if (!hasValidAuthorization) {
                syncUtils.logoutUser();
            } else if (!syncUtils.isAppVersionAllowed()) {
                if (isSuccessfulPushSync) {
                    syncUtils.logoutUser();
                } else {
                    return;
                }
            } else {
                pullECFromServer();
            }
        } catch (Exception e) {
            Timber.e(e);
            complete(FetchStatus.fetchedFailed);
        }
    }

    protected void pullECFromServer() {
        fetchRetry(0, true);
    }

    private synchronized void fetchRetry(final int count, boolean returnCount) {
        try {
            SyncConfiguration configs = CoreLibrary.getInstance().getSyncConfiguration();
            if (configs.getSyncFilterParam() == null || StringUtils.isBlank(configs.getSyncFilterValue())) {
                complete(FetchStatus.fetchedFailed);
                return;
            }

            final ECSyncHelper ecSyncUpdater = ECSyncHelper.getInstance(context);
            String baseUrl = getFormattedBaseUrl();

            Long lastSyncDatetime = ecSyncUpdater.getLastSyncTimeStamp();
            Timber.i("LAST SYNC DT %s", new DateTime(lastSyncDatetime));

            if (httpAgent == null) {
                complete(FetchStatus.fetchedFailed);
            }

            String url = baseUrl + SYNC_URL;
            Response resp;
            if (configs.isSyncUsingPost()) {
                JSONObject syncParams = new JSONObject();
                syncParams.put(configs.getSyncFilterParam().value(), configs.getSyncFilterValue());
                syncParams.put("serverVersion", lastSyncDatetime);
                syncParams.put("limit", getEventPullLimit());
                syncParams.put(AllConstants.RETURN_COUNT, returnCount);
                resp = httpAgent.postWithJsonResponse(url, syncParams.toString());
            } else {
                url += "?" + configs.getSyncFilterParam().value() + "=" + configs.getSyncFilterValue() + "&serverVersion=" + lastSyncDatetime + "&limit=" + getEventPullLimit();
                Timber.i("URL: %s", url);
                resp = httpAgent.fetch(url);
            }

            if (resp.isUrlError()) {
                FetchStatus.fetchedFailed.setDisplayValue(resp.status().displayValue());
                complete(FetchStatus.fetchedFailed);
                return;
            }

            if (resp.isTimeoutError()) {
                FetchStatus.fetchedFailed.setDisplayValue(resp.status().displayValue());
                complete(FetchStatus.fetchedFailed);
            }

            if (resp.isFailure() && !resp.isUrlError() && !resp.isTimeoutError()) {
                fetchFailed(count);
            }

            if (returnCount) {
                totalRecords = resp.getTotalRecords();
            }

            processFetchedEvents(resp, ecSyncUpdater, count);

        } catch (Exception e) {
            Timber.e(e, "Fetch Retry Exception:  %s", e.getMessage());
            fetchFailed(count);
        }
    }

    private void processFetchedEvents(Response resp, ECSyncHelper ecSyncUpdater, final int count) throws JSONException {
        int eCount;
        JSONObject jsonObject = new JSONObject();
        if (resp.payload() == null) {
            eCount = 0;
        } else {
            jsonObject = new JSONObject((String) resp.payload());
            eCount = fetchNumberOfEvents(jsonObject);
            Timber.i("Parse Network Event Count: %s", eCount);
        }

        if (eCount == 0) {
            complete(FetchStatus.nothingFetched);
        } else if (eCount < 0) {
            fetchFailed(count);
        } else {
            final Pair<Long, Long> serverVersionPair = getMinMaxServerVersions(jsonObject);
            long lastServerVersion = serverVersionPair.second - 1;
            if (eCount < getEventPullLimit()) {
                lastServerVersion = serverVersionPair.second;
            }

            boolean isSaved = ecSyncUpdater.saveAllClientsAndEvents(jsonObject);
            //update sync time if all event client is save.
            if (isSaved) {
                processClient(serverVersionPair);
                ecSyncUpdater.updateLastSyncTimeStamp(lastServerVersion);
            }
            sendSyncProgressBroadcast(eCount);
            fetchRetry(0, false);
        }
    }

    public void fetchFailed(int count) {
        if (count < CoreLibrary.getInstance().getSyncConfiguration().getSyncMaxRetries()) {
            int newCount = count + 1;
            fetchRetry(newCount, false);
        } else {
            complete(FetchStatus.fetchedFailed);
        }
    }

    protected void processClient(Pair<Long, Long> serverVersionPair) {
        try {
            ECSyncHelper ecUpdater = ECSyncHelper.getInstance(context);
            List<EventClient> events = ecUpdater.allEventClients(serverVersionPair.first - 1, serverVersionPair.second);
            DrishtiApplication.getInstance().getClientProcessor().processClient(events);
            sendSyncStatusBroadcastMessage(FetchStatus.fetched);
        } catch (Exception e) {
            Timber.e(e, "Process Client Exception: %s", e.getMessage());
        }
    }

    // PUSH TO SERVER
    private boolean pushToServer() {
        return pushECToServer(CoreLibrary.getInstance().context().getEventClientRepository()) &&
                (!CoreLibrary.getInstance().context().hasForeignEvents() || pushECToServer(CoreLibrary.getInstance().context().getForeignEventClientRepository()));
    }

    private boolean pushECToServer(EventClientRepository db) {
        boolean isSuccessfulPushSync = true;

        // push foreign events to server
        int totalEventCount = db.getUnSyncedEventsCount();
        int eventsUploadedCount = 0;

        while (true) {
            Map<String, Object> pendingEvents = db.getUnSyncedEvents(EVENT_PUSH_LIMIT);

            if (pendingEvents.isEmpty()) {
                break;
            }

            String baseUrl = CoreLibrary.getInstance().context().configuration().dristhiBaseURL();
            if (baseUrl.endsWith(context.getString(R.string.url_separator))) {
                baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf(context.getString(R.string.url_separator)));
            }
            // create request body
            JSONObject request = new JSONObject();
            try {
                if (pendingEvents.containsKey(AllConstants.KEY.CLIENTS)) {
                    Object value = pendingEvents.get(AllConstants.KEY.CLIENTS);
                    request.put(AllConstants.KEY.CLIENTS, value);

                    if (value instanceof List) {
                        eventsUploadedCount += ((List) value).size();
                    }
                }
                if (pendingEvents.containsKey(AllConstants.KEY.EVENTS)) {
                    request.put(AllConstants.KEY.EVENTS, pendingEvents.get(AllConstants.KEY.EVENTS));
                }
            } catch (JSONException e) {
                Timber.e(e);
            }
            String jsonPayload = request.toString();
            Response<String> response = httpAgent.post(
                    MessageFormat.format("{0}{1}",
                            baseUrl,
                            ADD_URL),
                    jsonPayload);
            if (response.isFailure()) {
                Timber.e("Events sync failed.");
                isSuccessfulPushSync = false;
            }
            db.markEventsAsSynced(pendingEvents);
            Timber.i("Events synced successfully.");
            updateProgress(eventsUploadedCount, totalEventCount);
        }

        return isSuccessfulPushSync;
    }

    private void sendSyncStatusBroadcastMessage(FetchStatus fetchStatus) {
        Intent intent = new Intent();
        intent.setAction(SyncStatusBroadcastReceiver.ACTION_SYNC_STATUS);
        intent.putExtra(SyncStatusBroadcastReceiver.EXTRA_FETCH_STATUS, fetchStatus);
        sendBroadcast(intent);
    }

    protected void complete(FetchStatus fetchStatus) {
        Intent intent = new Intent();
        intent.setAction(SyncStatusBroadcastReceiver.ACTION_SYNC_STATUS);
        intent.putExtra(SyncStatusBroadcastReceiver.EXTRA_FETCH_STATUS, fetchStatus);
        intent.putExtra(SyncStatusBroadcastReceiver.EXTRA_COMPLETE_STATUS, true);

        sendBroadcast(intent);
        //sync time not update if sync is fail
        if (!fetchStatus.equals(FetchStatus.noConnection) && !fetchStatus.equals(FetchStatus.fetchedFailed)) {
            ECSyncHelper ecSyncUpdater = ECSyncHelper.getInstance(context);
            ecSyncUpdater.updateLastCheckTimeStamp(new Date().getTime());
        }

    }

    protected void updateProgress(@IntRange(from = 0) int progress, @IntRange(from = 1) int total) {
        FetchStatus uploadProgressStatus = FetchStatus.fetchProgress;
        uploadProgressStatus.setDisplayValue(String.format(getString(R.string.sync_upload_progress_float), (progress * 100) / total));
        sendSyncStatusBroadcastMessage(uploadProgressStatus);
    }

    protected Pair<Long, Long> getMinMaxServerVersions(JSONObject jsonObject) {
        final String EVENTS = "events";
        final String SERVER_VERSION = "serverVersion";
        try {
            if (jsonObject != null && jsonObject.has(EVENTS)) {
                JSONArray events = jsonObject.getJSONArray(EVENTS);

                long maxServerVersion = Long.MIN_VALUE;
                long minServerVersion = Long.MAX_VALUE;

                for (int i = 0; i < events.length(); i++) {
                    Object o = events.get(i);
                    if (o instanceof JSONObject) {
                        JSONObject jo = (JSONObject) o;
                        if (jo.has(SERVER_VERSION)) {
                            long serverVersion = jo.getLong(SERVER_VERSION);
                            if (serverVersion > maxServerVersion) {
                                maxServerVersion = serverVersion;
                            }

                            if (serverVersion < minServerVersion) {
                                minServerVersion = serverVersion;
                            }
                        }
                    }
                }
                return Pair.create(minServerVersion, maxServerVersion);
            }
        } catch (Exception e) {
            Timber.e(e);
        }
        return Pair.create(0L, 0L);
    }

    protected int fetchNumberOfEvents(JSONObject jsonObject) {
        int count = -1;
        final String NO_OF_EVENTS = "no_of_events";
        try {
            if (jsonObject != null && jsonObject.has(NO_OF_EVENTS)) {
                count = jsonObject.getInt(NO_OF_EVENTS);
            }
        } catch (JSONException e) {
            Timber.e(e);
        }
        return count;
    }

    protected void sendSyncProgressBroadcast(int eventCount) {
        fetchedRecords = fetchedRecords + eventCount;
        SyncProgress syncProgress = new SyncProgress();
        syncProgress.setSyncEntity(SyncEntity.EVENTS);
        syncProgress.setTotalRecords(totalRecords);
        syncProgress.setPercentageSynced(Utils.calculatePercentage(totalRecords, fetchedRecords));
        Intent intent = new Intent();
        intent.setAction(AllConstants.SyncProgressConstants.ACTION_SYNC_PROGRESS);
        intent.putExtra(AllConstants.SyncProgressConstants.SYNC_PROGRESS_DATA, syncProgress);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public int getEventPullLimit() {
        return EVENT_PULL_LIMIT;
    }

    public HTTPAgent getHttpAgent() {
        return httpAgent;
    }

    public Context getContext() {
        return this.context;
    }

    @NonNull
    protected String getFormattedBaseUrl() {
        String baseUrl = CoreLibrary.getInstance().context().
                configuration().dristhiBaseURL();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf("/"));
        }
        return baseUrl;
    }

}
