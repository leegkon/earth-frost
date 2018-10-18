package vip.justlive.frost.core.job;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Objects;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import vip.justlive.frost.api.model.JobExecuteParam;
import vip.justlive.frost.api.model.JobExecuteRecord;
import vip.justlive.frost.api.model.JobGroup;
import vip.justlive.frost.api.model.JobInfo;
import vip.justlive.frost.api.model.JobRecordStatus;
import vip.justlive.frost.api.model.JobSharding;
import vip.justlive.frost.core.config.JobConfig;
import vip.justlive.frost.core.dispacher.Dispatcher;
import vip.justlive.frost.core.notify.Event;
import vip.justlive.frost.core.notify.EventPublisher;
import vip.justlive.frost.core.persistence.JobRepository;
import vip.justlive.oxygen.core.exception.CodedException;
import vip.justlive.oxygen.core.ioc.BeanStore;
import vip.justlive.oxygen.core.util.Checks;

/**
 * job分发包装
 * 
 * @author wubo
 *
 */
@NoArgsConstructor
@RequiredArgsConstructor
public class JobDispatchWrapper extends AbstractWrapper {

  /**
   * id for job
   */
  @NonNull
  private String id;

  private String loggerId;

  private String parentLoggerId;

  private JobRecordStatus jobRecordStatus;

  private boolean failRetry;

  private JobExecuteParam param;

  private JobInfo jobInfo;

  private boolean success;

  public JobDispatchWrapper(String id, String loggerId) {
    this.id = id;
    this.loggerId = loggerId;
  }

  public void setParentLoggerId(String parentLoggerId) {
    this.parentLoggerId = parentLoggerId;
  }

  @Override
  public void doRun() {
    Date time = Date.from(ZonedDateTime.now().toInstant());
    param = new JobExecuteParam(id);
    JobRepository jobRepository = BeanStore.getBean(JobRepository.class);
    jobInfo = jobRepository.findJobInfoById(id);
    JobLogger jobLogger = BeanStore.getBean(JobLogger.class);
    if (loggerId == null) {
      loggerId = jobLogger.bindLog(id);
      JobExecuteRecord record = this.record(id, loggerId);
      jobRepository.addJobRecord(record);
    } else {
      failRetry = true;
    }
    jobLogger.enter(loggerId, JobConfig.CENTER_STATISTICS_DISPATCH);
    jobRecordStatus = this.recordStatus(loggerId);
    jobRecordStatus.setTime(time);
    if (failRetry) {
      jobRecordStatus.setType(2);
    } else {
      jobRecordStatus.setType(0);
    }

    String key;
    if (Objects.equals(JobInfo.TYPE.SCRIPT.name(), jobInfo.getType())) {
      if (jobInfo.getGroup() != null && jobInfo.getGroup().getGroupKey() != null) {
        key = String.join(JobConfig.SEPERATOR, JobConfig.JOB_SCRIPT_PREFIX,
            jobInfo.getGroup().getGroupKey());
      } else {
        key = JobConfig.JOB_SCRIPT_PREFIX;
      }
    } else {
      JobGroup jobGroup = Checks.notNull(Checks.notNull(jobInfo).getGroup());
      key = String.join(JobConfig.SEPERATOR, JobConfig.JOB_GROUP_PREFIX, jobGroup.getGroupKey(),
          jobGroup.getJobKey());
      param.setHandlerId(jobInfo.getGroup().getJobKey());
    }
    param.setTopicKey(key);
    param.setLoggerId(loggerId);
    param.setFailRetry(failRetry);

    Dispatcher dispatcher = BeanStore.getBean(Dispatcher.class);

    if (jobInfo.isUseSharding()) {
      handleSharding(dispatcher);
    } else {
      dispatcher.dispatch(param);
    }
  }

  void handleSharding(Dispatcher dispatcher) {
    Integer total = jobInfo.getSharding();
    if (total == null) {
      total = dispatcher.count(param);
    }
    for (int i = 0; i < total; i++) {
      JobSharding sharding = new JobSharding(i, total);
      param.setSharding(sharding);
      dispatcher.dispatch(param);
    }
  }

  @Override
  public void success() {
    success = true;
    JobRepository jobRepository = BeanStore.getBean(JobRepository.class);
    jobRecordStatus.setStatus(JobExecuteRecord.STATUS.SUCCESS.name());
    jobRecordStatus.setMsg("调度成功");
    jobRepository.addJobRecordStatus(jobRecordStatus);
  }

  @Override
  public void exception(Exception e) {
    success = false;
    super.exception(e);

    JobRepository jobRepository = BeanStore.getBean(JobRepository.class);
    jobRecordStatus.setStatus(JobExecuteRecord.STATUS.FAIL.name());
    if (e instanceof CodedException) {
      jobRecordStatus.setMsg(((CodedException) e).getErrorCode().toString());
    } else {
      jobRecordStatus.setMsg(e.getMessage());
    }
    jobRepository.addJobRecordStatus(jobRecordStatus);

    EventPublisher publisher = BeanStore.getBean(EventPublisher.class);
    publisher.publish(new Event(param, Event.TYPE.DISPATCH_FAIL.name(), jobRecordStatus.getMsg(),
        jobRecordStatus.getTime().getTime()));

    if (!failRetry) {
      param.setLoggerId(loggerId);
      param.setFailRetry(failRetry);
      param.setParentLoggerId(parentLoggerId);
      publisher.publish(new Event(param, Event.TYPE.DISPATCH_FAIL_RETRY.name(),
          jobRecordStatus.getMsg(), jobRecordStatus.getTime().getTime()));
    }
  }

  @Override
  public void finshed() {
    if (parentLoggerId != null) {
      JobRecordStatus status = recordStatus(parentLoggerId);
      status.setType(5);
      status.setTime(jobRecordStatus.getTime());
      status.setMsg(String.format("[%s]触发调度[%s]-[%s]",
          DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDateTime
              .ofInstant(jobRecordStatus.getTime().toInstant(), ZoneId.systemDefault())),
          jobInfo.getName(), jobInfo.getId()));
      status.setStatus(jobRecordStatus.getStatus());
      JobRepository jobRepository = BeanStore.getBean(JobRepository.class);
      jobRepository.addJobRecordStatus(status);
    }
    JobLogger jobLogger = BeanStore.getBean(JobLogger.class);
    jobLogger.leave(loggerId, JobConfig.CENTER_STATISTICS_DISPATCH, success);
  }
}
