package com.hubspot.singularity.scheduler;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import javax.inject.Singleton;
import javax.ws.rs.WebApplicationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.ExtendedTaskState;
import com.hubspot.singularity.RequestCleanupType;
import com.hubspot.singularity.RequestState;
import com.hubspot.singularity.SingularityCreateResult;
import com.hubspot.singularity.SingularityPendingRequest;
import com.hubspot.singularity.SingularityPendingRequest.PendingType;
import com.hubspot.singularity.SingularityRequest;
import com.hubspot.singularity.SingularityRequestCleanup;
import com.hubspot.singularity.SingularityRequestHistory.RequestHistoryType;
import com.hubspot.singularity.SingularityRequestWithState;
import com.hubspot.singularity.SingularityShellCommand;
import com.hubspot.singularity.SingularityTaskCleanup;
import com.hubspot.singularity.SingularityTaskHistoryUpdate;
import com.hubspot.singularity.api.SingularityBounceRequest;
import com.hubspot.singularity.api.SingularityScaleRequest;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DeployManager;
import com.hubspot.singularity.data.RequestManager;
import com.hubspot.singularity.data.TaskManager;
import com.hubspot.singularity.expiring.SingularityExpiringBounce;
import com.hubspot.singularity.expiring.SingularityExpiringParent;
import com.hubspot.singularity.expiring.SingularityExpiringPause;
import com.hubspot.singularity.expiring.SingularityExpiringScale;
import com.hubspot.singularity.expiring.SingularityExpiringSkipHealthchecks;
import com.hubspot.singularity.helpers.RequestHelper;
import com.hubspot.singularity.mesos.SingularityMesosModule;
import com.hubspot.singularity.smtp.SingularityMailer;

@Singleton
public class SingularityExpiringUserActionPoller extends SingularityLeaderOnlyPoller {

  private static final Logger LOG = LoggerFactory.getLogger(SingularityExpiringUserActionPoller.class);

  private final RequestManager requestManager;
  private final DeployManager deployManager;
  private final TaskManager taskManager;
  private final SingularityMailer mailer;
  private final RequestHelper requestHelper;
  private final List<SingularityExpiringUserActionHandler<?>> handlers;
  private final SingularityConfiguration configuration;

  @Inject
  SingularityExpiringUserActionPoller(SingularityConfiguration configuration, RequestManager requestManager, DeployManager deployManager, TaskManager taskManager,
      @Named(SingularityMesosModule.SCHEDULER_LOCK_NAME) final Lock lock, RequestHelper requestHelper, SingularityMailer mailer) {
    super(configuration.getCheckExpiringUserActionEveryMillis(), TimeUnit.MILLISECONDS, lock);

    this.deployManager = deployManager;
    this.requestManager = requestManager;
    this.requestHelper = requestHelper;
    this.mailer = mailer;
    this.taskManager = taskManager;
    this.configuration = configuration;

    List<SingularityExpiringUserActionHandler<?>> tempHandlers = Lists.newArrayList();
    tempHandlers.add(new SingularityExpiringBounceHandler());
    tempHandlers.add(new SingularityExpiringPauseHandler());
    tempHandlers.add(new SingularityExpiringScaleHandler());
    tempHandlers.add(new SingularityExpiringSkipHealthchecksHandler());

    this.handlers = ImmutableList.copyOf(tempHandlers);
  }

  @Override
  public void runActionOnPoll() {
    for (SingularityExpiringUserActionHandler<?> handler : handlers) {
      handler.checkExpiringObjects();
    }
  }

  private abstract class SingularityExpiringUserActionHandler<T extends SingularityExpiringParent<?>> {

    private final Class<T> clazz;

    private SingularityExpiringUserActionHandler(Class<T> clazz) {
      this.clazz = clazz;
    }

    private boolean isExpiringDue(T expiringObject) {
      final long now = System.currentTimeMillis();
      final long duration = now - expiringObject.getStartMillis();

      return duration > getDurationMillis(expiringObject);
    }

    protected String getMessage(T expiringObject) {
      String msg = String.format("%s expired after %s", getActionName(),
          JavaUtils.durationFromMillis(getDurationMillis(expiringObject)));
      if (expiringObject.getExpiringAPIRequestObject().getMessage().isPresent() && expiringObject.getExpiringAPIRequestObject().getMessage().get().length() > 0) {
        msg = String.format("%s (%s)", msg, expiringObject.getExpiringAPIRequestObject().getMessage().get());
      }
      return msg;
    }

    protected long getDurationMillis(T expiringObject) {
      return expiringObject.getExpiringAPIRequestObject().getDurationMillis().get();
    }

    protected void checkExpiringObjects() {
      for (T expiringObject : requestManager.getExpiringObjects(clazz)) {
        if (isExpiringDue(expiringObject)) {

          Optional<SingularityRequestWithState> requestWithState = requestManager.getRequest(expiringObject.getRequestId());

          if (!requestWithState.isPresent()) {
            LOG.warn("Request {} not present, discarding {}", expiringObject.getRequestId(), expiringObject);
          } else {
            handleExpiringObject(expiringObject, requestWithState.get(), getMessage(expiringObject));
          }

          requestManager.deleteExpiringObject(clazz, expiringObject.getRequestId());
        }
      }
    }

    protected abstract String getActionName();
    protected abstract void handleExpiringObject(T expiringObject, SingularityRequestWithState requestWithState, String message);

  }

  private class SingularityExpiringBounceHandler extends SingularityExpiringUserActionHandler<SingularityExpiringBounce> {

    public SingularityExpiringBounceHandler() {
      super(SingularityExpiringBounce.class);
    }

    @Override
    protected String getActionName() {
      return "Bounce";
    }

    @Override
    protected long getDurationMillis(SingularityExpiringBounce expiringBounce) {
      return expiringBounce.getExpiringAPIRequestObject().getDurationMillis().or(TimeUnit.MINUTES.toMillis(configuration.getDefaultBounceExpirationMinutes()));
    }

    @Override
    protected void handleExpiringObject(SingularityExpiringBounce expiringObject, SingularityRequestWithState requestWithState, String message) {
      for (SingularityTaskCleanup taskCleanup : taskManager.getCleanupTasks()) {
        if (taskCleanup.getTaskId().getRequestId().equals(expiringObject.getRequestId())
            && taskCleanup.getActionId().isPresent() && expiringObject.getActionId().equals(taskCleanup.getActionId().get())) {
          LOG.info("Discarding cleanup for {} ({}) because of {}", taskCleanup.getTaskId(), taskCleanup, expiringObject);
          taskManager.deleteCleanupTask(taskCleanup.getTaskId().getId());
          if (!taskManager.getTaskCleanup(taskCleanup.getTaskId().getId()).isPresent()) {
            LOG.info("No other task cleanups found, removing task cleanup update for {}", taskCleanup.getTaskId());
            List<SingularityTaskHistoryUpdate> historyUpdates = taskManager.getTaskHistoryUpdates(taskCleanup.getTaskId());
            Collections.sort(historyUpdates);
            if (Iterables.getLast(historyUpdates).getTaskState() == ExtendedTaskState.TASK_CLEANING) {
              Optional<SingularityTaskHistoryUpdate> maybePreviousHistoryUpdate = historyUpdates.size() > 1 ? Optional.of(historyUpdates.get(historyUpdates.size() - 2)) : Optional.<SingularityTaskHistoryUpdate>absent();
              taskManager.deleteTaskHistoryUpdate(taskCleanup.getTaskId(), ExtendedTaskState.TASK_CLEANING, maybePreviousHistoryUpdate);
            }
          }
        }
      }

      Optional<SingularityPendingRequest> pendingRequest = requestManager.getPendingRequest(expiringObject.getRequestId(), expiringObject.getDeployId());

      if (pendingRequest.isPresent() && pendingRequest.get().getActionId().isPresent() && pendingRequest.get().getActionId().get().equals(expiringObject.getActionId())) {
        LOG.info("Discarding pending request for {} ({}) because of {}", expiringObject.getRequestId(), pendingRequest.get(), expiringObject);

        requestManager.deletePendingRequest(pendingRequest.get());
      }

      requestManager.addToPendingQueue(new SingularityPendingRequest(expiringObject.getRequestId(), expiringObject.getDeployId(), System.currentTimeMillis(), expiringObject.getUser(),
          PendingType.CANCEL_BOUNCE, Optional.<List<String>> absent(), Optional.<String> absent(), Optional.<Boolean> absent(), Optional.of(message), Optional.of(expiringObject.getActionId())));
    }

  }

  private class SingularityExpiringPauseHandler extends SingularityExpiringUserActionHandler<SingularityExpiringPause> {

    public SingularityExpiringPauseHandler() {
      super(SingularityExpiringPause.class);
    }

    @Override
    protected String getActionName() {
      return "Pause";
    }

    @Override
    protected void handleExpiringObject(SingularityExpiringPause expiringObject, SingularityRequestWithState requestWithState, String message) {
      if (requestWithState.getState() != RequestState.PAUSED) {
        LOG.warn("Discarding {} because request {} is in state {}", expiringObject, requestWithState.getRequest().getId(), requestWithState.getState());
        return;
      }

      LOG.info("Unpausing request {} because of {}", requestWithState.getRequest().getId(), expiringObject);

      requestHelper.unpause(requestWithState.getRequest(), expiringObject.getUser(), Optional.of(message), Optional.<Boolean> absent());
    }

  }

  private class SingularityExpiringScaleHandler extends SingularityExpiringUserActionHandler<SingularityExpiringScale> {

    public SingularityExpiringScaleHandler() {
      super(SingularityExpiringScale.class);
    }

    @Override
    protected String getActionName() {
      return "Scale";
    }

    @Override
    protected void handleExpiringObject(SingularityExpiringScale expiringObject, SingularityRequestWithState requestWithState, String message) {
      final SingularityRequest oldRequest = requestWithState.getRequest();
      final SingularityRequest newRequest = oldRequest.toBuilder().setInstances(expiringObject.getRevertToInstances()).build();

      try {
        requestHelper.updateRequest(newRequest, Optional.of(oldRequest), requestWithState.getState(), Optional.of(RequestHistoryType.SCALE_REVERTED), expiringObject.getUser(),
            Optional.<Boolean> absent(), Optional.of(message));

        if (newRequest.getBounceAfterScale().or(false)) {
          LOG.info("Attempting to bounce request {} after expiring scale", newRequest.getId());
          Optional<String> maybeActiveDeployId = deployManager.getInUseDeployId(newRequest.getId());
          if (maybeActiveDeployId.isPresent()) {
            String bounceMessage = String.format("Bouncing after expiring scale by %s", expiringObject.getUser());
            String actionId = UUID.randomUUID().toString();
            SingularityCreateResult createResult = requestManager.createCleanupRequest(
              new SingularityRequestCleanup(expiringObject.getUser(), RequestCleanupType.INCREMENTAL_BOUNCE, System.currentTimeMillis(), Optional.<Boolean> absent(),
                newRequest.getId(), maybeActiveDeployId, Optional.<Boolean>absent(), Optional.of(bounceMessage), Optional.of(actionId), Optional.<SingularityShellCommand>absent()));

            if (createResult != SingularityCreateResult.EXISTED) {
              requestManager.bounce(requestWithState.getRequest(), System.currentTimeMillis(), expiringObject.getUser(), Optional.of(bounceMessage));
              requestManager.saveExpiringObject(new SingularityExpiringBounce(newRequest.getId(), maybeActiveDeployId.get(), expiringObject.getUser(),
                System.currentTimeMillis(), SingularityBounceRequest.defaultRequest(), actionId));
            } else {
              LOG.debug("Request {} was already bouncing, not bouncing again after expiring scale", newRequest.getId());
            }
          } else {
            LOG.debug("No active deploy id present for request {}, not bouncing after expiring scale", newRequest.getId());
          }
        }

        mailer.sendRequestScaledMail(newRequest, Optional.<SingularityScaleRequest> absent(), oldRequest.getInstances(), expiringObject.getUser());
      } catch (WebApplicationException wae) {
        LOG.error("While trying to apply {} for {}", expiringObject, expiringObject.getRequestId(), wae);
      }
    }

  }

  private class SingularityExpiringSkipHealthchecksHandler extends SingularityExpiringUserActionHandler<SingularityExpiringSkipHealthchecks> {

    public SingularityExpiringSkipHealthchecksHandler() {
      super(SingularityExpiringSkipHealthchecks.class);
    }

    @Override
    protected String getActionName() {
      return "Skip healthchecks";
    }

    @Override
    protected void handleExpiringObject(SingularityExpiringSkipHealthchecks expiringObject, SingularityRequestWithState requestWithState, String message) {
      final SingularityRequest oldRequest = requestWithState.getRequest();
      final SingularityRequest newRequest = oldRequest.toBuilder().setSkipHealthchecks(expiringObject.getRevertToSkipHealthchecks()).build();

      try {
        requestHelper.updateRequest(newRequest, Optional.of(oldRequest), requestWithState.getState(), Optional.<RequestHistoryType> absent(), expiringObject.getUser(),
            Optional.<Boolean> absent(), Optional.of(message));
      } catch (WebApplicationException wae) {
        LOG.error("While trying to apply {} for {}", expiringObject, expiringObject.getRequestId(), wae);
      }
    }
  }

}
