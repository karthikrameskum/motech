package org.motechproject.scheduler.service.impl;

import org.motechproject.event.MotechEvent;
import org.motechproject.event.listener.EventRelay;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.SchedulerContext;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/**
 * Represents a MOTECH job scheduled with quartz. This class implements the {@code org.quartz.Job} interface -
 * its execute method will be called when a MOTECH job in quartz triggers. Since jobs in MOTECH are basically {@link org.motechproject.event.MotechEvent}s
 * getting published on a quartz schedule, upon execution this class retrieves the {@link org.motechproject.event.listener.EventRelay}
 * from the application context and uses it to immediately publish the event scheduled with this job. For every execution
 * a new copy of the event is constructed.
 */
public class MotechScheduledJob implements Job {

    private static final Logger LOGGER = LoggerFactory.getLogger(MotechScheduledJob.class);

    /**
     * Executes the job called by Quartz.
     *
     * @param jobExecutionContext  the executionContext of the job provided by Quartz
     */
    @Override
    @SuppressWarnings("unchecked")
    public void execute(JobExecutionContext jobExecutionContext) {

        LOGGER.info("executing...");

        try {
            JobDetail jobDetail = jobExecutionContext.getJobDetail();
            JobDataMap jobDataMap = jobDetail.getJobDataMap();

            String jobId = jobDetail.getKey().getName();
            String eventType = jobDataMap.getString(MotechEvent.EVENT_TYPE_KEY_NAME);
            Map<String, Object> params = jobDataMap.getWrappedMap();
            params.remove(MotechEvent.EVENT_TYPE_KEY_NAME);
            params.put("JobID", jobId);

            MotechEvent motechEvent = new MotechEvent(eventType, params);

            LOGGER.info("Sending Motech Event Message: " + motechEvent);

            SchedulerContext schedulerContext;
            try {
                schedulerContext = jobExecutionContext.getScheduler().getContext();
            } catch (SchedulerException e) {
                LOGGER.error("Can not execute job. Can not get Scheduler Context", e);
                return;
            }

            ApplicationContext applicationContext = (ApplicationContext) schedulerContext.get("applicationContext");
            EventRelay eventRelay = applicationContext.getBean(EventRelay.class);
            eventRelay.sendEventMessage(motechEvent);
        } catch (Exception e) {
            LOGGER.error("Job execution failed.", e);
        }
    }
}
