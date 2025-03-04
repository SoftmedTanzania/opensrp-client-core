package org.smartregister.view.receiver;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlarmManager;
import org.robolectric.util.ReflectionHelpers;
import org.smartregister.BaseRobolectricUnitTest;
import org.smartregister.CoreLibrary;
import org.smartregister.sync.DrishtiSyncScheduler;
import org.smartregister.util.Session;
import org.smartregister.view.activity.mock.LoginActivityMock;

/**
 * Created by Ephraim Kigamba - nek.eam@gmail.com on 21-07-2020.
 */
public class ConnectivityChangeReceiverTest extends BaseRobolectricUnitTest {

    private ConnectivityChangeReceiver connectivityChangeReceiver;

    @Before
    public void setUp() throws Exception {
        connectivityChangeReceiver = new ConnectivityChangeReceiver();

        // Make sure the user is logged in
        Session session = ReflectionHelpers.getField(CoreLibrary.getInstance().context().userService(), "session");
        session.setPassword("");
        session.start(360 * 60 * 1000);
    }

    @After
    public void tearDown() throws Exception {
        // Log out the user
        Session session = ReflectionHelpers.getField(CoreLibrary.getInstance().context().userService(), "session");
        session.setPassword(null);
        session.start(0);
    }

    @Test
    public void onReceiveShouldCreateAlarmWhenDeviceIsConnectedToNetwork() {
        Intent intent = new Intent();
        NetworkInfo networkInfo = Mockito.mock(NetworkInfo.class);
        Mockito.doReturn(true).when(networkInfo).isConnected();

        intent.putExtra(ConnectivityManager.EXTRA_NETWORK_INFO, networkInfo);

        DrishtiSyncScheduler.setReceiverClass(LoginActivityMock.class);

        AlarmManager alarmManager = (AlarmManager) RuntimeEnvironment.application.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager shadowAlarmManager = Shadows.shadowOf(alarmManager);
        Assert.assertNull(shadowAlarmManager.getNextScheduledAlarm());

        connectivityChangeReceiver.onReceive(RuntimeEnvironment.application, intent);


        ShadowAlarmManager.ScheduledAlarm scheduledAlarm = shadowAlarmManager.getNextScheduledAlarm();
        Assert.assertEquals(120000, scheduledAlarm.interval);
        Assert.assertNotNull(scheduledAlarm.operation);

    }

    @Test
    public void onReceiveShouldCancelAlarmWhenDeviceIsDisconnectFromNetwork() {
        Intent intent = new Intent();
        NetworkInfo networkInfo = Mockito.mock(NetworkInfo.class);
        Mockito.doReturn(true).when(networkInfo).isConnected();

        intent.putExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, true);

        DrishtiSyncScheduler.setReceiverClass(LoginActivityMock.class);

        // Create the alarm
        DrishtiSyncScheduler.start(RuntimeEnvironment.application);

        AlarmManager alarmManager = (AlarmManager) RuntimeEnvironment.application.getSystemService(Context.ALARM_SERVICE);
        ShadowAlarmManager shadowAlarmManager = Shadows.shadowOf(alarmManager);

        // Assert that the alarm was created
        Assert.assertNotNull(shadowAlarmManager.getNextScheduledAlarm());


        connectivityChangeReceiver.onReceive(RuntimeEnvironment.application, intent);


        // Assert that the alarm was cancelled
        Assert.assertNull(shadowAlarmManager.getNextScheduledAlarm());

    }
}