package org.smartregister.job;

import org.smartregister.sync.intent.DocumentConfigurationIntentWorker;

/**
 * Created by cozej4 on 2020-04-16.
 *
 * @author cozej4 https://github.com/cozej4
 */
public class DocumentConfigurationServiceJobTest extends ServiceJobTest {

    @Override
    protected String getServiceId() {
        return "org.smartregister.sync.intent.DocumentConfigurationIntentService";
    }

    @Override
    protected BaseWorkRequest getJob() {
        return new DocumentConfigurationServiceWorkRequest(DocumentConfigurationIntentWorker.class);
    }
}