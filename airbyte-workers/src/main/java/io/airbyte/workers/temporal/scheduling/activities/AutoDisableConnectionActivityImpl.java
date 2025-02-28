/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.scheduling.activities;

import static io.airbyte.scheduler.models.Job.REPLICATION_TYPES;
import static io.airbyte.scheduler.persistence.JobNotifier.CONNECTION_DISABLED_NOTIFICATION;
import static io.airbyte.scheduler.persistence.JobNotifier.CONNECTION_DISABLED_WARNING_NOTIFICATION;
import static java.time.temporal.ChronoUnit.DAYS;

import io.airbyte.commons.features.FeatureFlags;
import io.airbyte.config.Configs;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardSync.Status;
import io.airbyte.config.persistence.ConfigNotFoundException;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.scheduler.models.Job;
import io.airbyte.scheduler.models.JobStatus;
import io.airbyte.scheduler.models.JobWithStatusAndTimestamp;
import io.airbyte.scheduler.persistence.JobNotifier;
import io.airbyte.scheduler.persistence.JobPersistence;
import io.airbyte.validation.json.JsonValidationException;
import io.airbyte.workers.temporal.exception.RetryableException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class AutoDisableConnectionActivityImpl implements AutoDisableConnectionActivity {

  private ConfigRepository configRepository;
  private JobPersistence jobPersistence;
  private FeatureFlags featureFlags;
  private Configs configs;
  private JobNotifier jobNotifier;

  // Given a connection id and current timestamp, this activity will set a connection to INACTIVE if
  // either:
  // - fails jobs consecutively and hits the `configs.getMaxFailedJobsInARowBeforeConnectionDisable()`
  // limit
  // - all the jobs in the past `configs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable()` days are
  // failures, and that the connection's first job is at least that many days old
  // Notifications will be sent if a connection is disabled or warned if it has reached halfway to
  // disable limits
  @Override
  public AutoDisableConnectionOutput autoDisableFailingConnection(final AutoDisableConnectionActivityInput input) {
    if (featureFlags.autoDisablesFailingConnections()) {
      try {
        final int maxDaysOfOnlyFailedJobs = configs.getMaxDaysOfOnlyFailedJobsBeforeConnectionDisable();
        final int maxDaysOfOnlyFailedJobsBeforeWarning = maxDaysOfOnlyFailedJobs / 2;
        final int maxFailedJobsInARowBeforeConnectionDisableWarning = configs.getMaxFailedJobsInARowBeforeConnectionDisable() / 2;
        final long currTimestampInSeconds = input.getCurrTimestamp().getEpochSecond();
        final Job lastJob = jobPersistence.getLastReplicationJob(input.getConnectionId())
            .orElseThrow(() -> new Exception("Auto-Disable Connection should not have been attempted if can't get latest replication job."));

        final List<JobWithStatusAndTimestamp> jobs = jobPersistence.listJobStatusAndTimestampWithConnection(input.getConnectionId(),
            REPLICATION_TYPES, input.getCurrTimestamp().minus(maxDaysOfOnlyFailedJobs, DAYS));

        int numFailures = 0;
        Optional<Long> successTimestamp = Optional.empty();

        for (final JobWithStatusAndTimestamp job : jobs) {
          final JobStatus jobStatus = job.getStatus();
          if (jobStatus == JobStatus.FAILED) {
            numFailures++;
          } else if (jobStatus == JobStatus.SUCCEEDED) {
            successTimestamp = Optional.of(job.getUpdatedAtInSecond());
            break;
          }
        }

        if (numFailures == 0) {
          return new AutoDisableConnectionOutput(false);
        } else if (numFailures == configs.getMaxFailedJobsInARowBeforeConnectionDisable()) {
          // disable connection if max consecutive failed jobs limit has been hit
          disableConnection(input.getConnectionId(), lastJob);
          return new AutoDisableConnectionOutput(true);
        } else if (numFailures == maxFailedJobsInARowBeforeConnectionDisableWarning) {
          // warn if number of consecutive failures hits 50% of MaxFailedJobsInARow
          jobNotifier.autoDisableConnectionAlertWithoutCustomerioConfig(CONNECTION_DISABLED_WARNING_NOTIFICATION, lastJob);
          return new AutoDisableConnectionOutput(false);
        }

        // calculate the number of days this connection first tried a replication job, used to ensure not to
        // disable or warn for `maxDaysOfOnlyFailedJobs` if the first job is younger than
        // `maxDaysOfOnlyFailedJobs` days, This avoids cases such as "the very first job run was a failure".
        final Job firstJob = jobPersistence.getFirstReplicationJob(input.getConnectionId())
            .orElseThrow(() -> new Exception("Auto-Disable Connection should not have been attempted if no replication job has been run."));
        final int numDaysSinceFirstReplicationJob = getDaysSinceTimestamp(currTimestampInSeconds, firstJob.getCreatedAtInSecond());
        final boolean firstReplicationOlderThanMaxDisableDays = numDaysSinceFirstReplicationJob >= maxDaysOfOnlyFailedJobs;
        final boolean noPreviousSuccess = successTimestamp.isEmpty();

        // disable connection if only failed jobs in the past maxDaysOfOnlyFailedJobs days
        if (firstReplicationOlderThanMaxDisableDays && noPreviousSuccess) {
          disableConnection(input.getConnectionId(), lastJob);
          return new AutoDisableConnectionOutput(true);
        }

        final boolean firstReplicationOlderThanMaxDisableWarningDays = numDaysSinceFirstReplicationJob >= maxDaysOfOnlyFailedJobsBeforeWarning;
        final boolean successOlderThanPrevFailureByMaxWarningDays = // set to true if no previous success is found
            noPreviousSuccess || getDaysSinceTimestamp(currTimestampInSeconds, successTimestamp.get()) >= maxDaysOfOnlyFailedJobsBeforeWarning;

        // send warning if there are only failed jobs in the past maxDaysOfOnlyFailedJobsBeforeWarning days
        // _unless_ a warning should have already been sent in the previous failure
        if (firstReplicationOlderThanMaxDisableWarningDays && successOlderThanPrevFailureByMaxWarningDays) {
          sendWarningIfNotPreviouslySent(successTimestamp, maxDaysOfOnlyFailedJobsBeforeWarning, firstJob, lastJob, jobs, numFailures);
        }

      } catch (final Exception e) {
        throw new RetryableException(e);
      }
    }
    return new AutoDisableConnectionOutput(false);
  }

  private void sendWarningIfNotPreviouslySent(final Optional<Long> successTimestamp,
                                              final int maxDaysOfOnlyFailedJobsBeforeWarning,
                                              final Job firstJob,
                                              final Job lastJob,
                                              final List<JobWithStatusAndTimestamp> jobs,
                                              final int numFailures) {
    if (numFailures > 1 && checkIfWarningPreviouslySent(successTimestamp, maxDaysOfOnlyFailedJobsBeforeWarning, firstJob, jobs)) {
      return;
    }
    jobNotifier.autoDisableConnectionAlertWithoutCustomerioConfig(CONNECTION_DISABLED_WARNING_NOTIFICATION, lastJob);
  }

  // Checks to see if warning should have been sent in the previous failure, if so skip sending of
  // warning to avoid spam
  // Assume warning has been sent if either of the following is true:
  // 1. no success found in the time span and the previous failure occurred
  // maxDaysOfOnlyFailedJobsBeforeWarning days after the first job
  // 2. success found and the previous failure occurred maxDaysOfOnlyFailedJobsBeforeWarning days
  // after that success
  private boolean checkIfWarningPreviouslySent(final Optional<Long> successTimestamp,
                                               final int maxDaysOfOnlyFailedJobsBeforeWarning,
                                               final Job firstJob,
                                               final List<JobWithStatusAndTimestamp> jobs) {
    if (jobs.size() <= 1)
      return false;

    // get previous failed job (skipping first job since that's considered "current" job)
    JobWithStatusAndTimestamp prevFailedJob = jobs.get(1);
    for (int i = 2; i < jobs.size(); i++) {
      if (prevFailedJob.getStatus() == JobStatus.FAILED)
        break;
      prevFailedJob = jobs.get(i);
    }

    final boolean successExists = successTimestamp.isPresent();
    boolean successOlderThanPrevFailureByMaxWarningDays = false;
    if (successExists) {
      successOlderThanPrevFailureByMaxWarningDays =
          getDaysSinceTimestamp(prevFailedJob.getUpdatedAtInSecond(), successTimestamp.get()) >= maxDaysOfOnlyFailedJobsBeforeWarning;
    }
    final boolean prevFailureOlderThanFirstJobByMaxWarningDays =
        getDaysSinceTimestamp(prevFailedJob.getUpdatedAtInSecond(), firstJob.getUpdatedAtInSecond()) >= maxDaysOfOnlyFailedJobsBeforeWarning;

    return (successExists && successOlderThanPrevFailureByMaxWarningDays)
        || (!successExists && prevFailureOlderThanFirstJobByMaxWarningDays);
  }

  private int getDaysSinceTimestamp(final long currentTimestampInSeconds, final long timestampInSeconds) {
    return Math.toIntExact(TimeUnit.SECONDS.toDays(currentTimestampInSeconds - timestampInSeconds));
  }

  private void disableConnection(final UUID connectionId, final Job lastJob) throws JsonValidationException, IOException, ConfigNotFoundException {
    final StandardSync standardSync = configRepository.getStandardSync(connectionId);
    standardSync.setStatus(Status.INACTIVE);
    configRepository.writeStandardSync(standardSync);

    jobNotifier.autoDisableConnectionAlertWithoutCustomerioConfig(CONNECTION_DISABLED_NOTIFICATION, lastJob);
  }

}
