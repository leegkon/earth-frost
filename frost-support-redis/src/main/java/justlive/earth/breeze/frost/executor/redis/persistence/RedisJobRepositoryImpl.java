package justlive.earth.breeze.frost.executor.redis.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RList;
import org.redisson.api.RListMultimap;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import com.google.common.collect.Lists;
import justlive.earth.breeze.frost.core.model.HashRef;
import justlive.earth.breeze.frost.core.model.JobExecuteRecord;
import justlive.earth.breeze.frost.core.model.JobExecutor;
import justlive.earth.breeze.frost.core.model.JobGroup;
import justlive.earth.breeze.frost.core.model.JobInfo;
import justlive.earth.breeze.frost.core.persistence.JobRepository;
import justlive.earth.breeze.frost.executor.redis.config.SystemProperties;
import justlive.earth.breeze.frost.executor.redis.model.JobRecordStatus;
import justlive.earth.breeze.snow.common.base.util.Checks;

/**
 * redis持久化实现
 * 
 * @author wubo
 *
 */
@Repository
public class RedisJobRepositoryImpl implements JobRepository {

  @Autowired
  RedissonClient redissonClient;

  @Override
  public int countExecutors() {
    RMapCache<String, JobExecutor> cache =
        redissonClient.getMapCache(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecutor.class.getName()));
    return cache.size();
  }

  @Override
  public List<JobExecutor> queryJobExecutors() {
    RMapCache<String, JobExecutor> cache =
        redissonClient.getMapCache(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecutor.class.getName()));
    return new ArrayList<>(cache.values());
  }

  @Override
  public void addJob(JobInfo jobInfo) {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    jobInfo.setId(UUID.randomUUID().toString());
    map.put(jobInfo.getId(), jobInfo);
  }

  @Override
  public void updateJob(JobInfo jobInfo) {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    map.put(jobInfo.getId(), jobInfo);
  }

  @Override
  public void removeJob(String jobId) {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    map.remove(jobId);
  }

  @Override
  public int countJobInfos() {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    return map.size();
  }

  @Override
  public List<JobInfo> queryJobInfos() {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    return new ArrayList<>(map.values());
  }

  @Override
  public JobInfo findJobInfoById(String id) {
    RMap<String, JobInfo> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, JobInfo.class.getName()));
    return map.get(id);
  }

  @Override
  public String addJobRecord(JobExecuteRecord record) {
    RListMultimap<String, JobExecuteRecord> listMultimap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    listMultimap.put(record.getJobId(), record);
    HashRef ref =
        new HashRef(record.getJobId(), listMultimap.get(record.getJobId()).indexOf(record));
    RMap<String, HashRef> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, HashRef.class.getName()));
    map.put(record.getId(), ref);

    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, HashRef.class.getName()));
    // 全部
    hashedListmap.put(JobExecuteRecord.class.getName(), record.getId());
    JobGroup group = findJobInfoById(record.getJobId()).getGroup();
    // groupKey
    hashedListmap.put(group.getGroupKey(), record.getId());
    // jobKey
    hashedListmap.put(
        String.join(SystemProperties.SEPERATOR, group.getGroupKey(), group.getJobKey()),
        record.getId());

    return record.getId();
  }

  @Override
  public int countJobRecords(String groupKey, String jobKey, String jobId) {
    RListMultimap<String, JobExecuteRecord> listMultimap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    if (StringUtils.isNoneBlank(jobId)) {
      List<JobExecuteRecord> list = listMultimap.get(jobId);
      return list.size();
    }
    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, HashRef.class.getName()));
    String key = JobExecuteRecord.class.getName();
    if (StringUtils.isNoneBlank(groupKey)) {
      key = groupKey;
    }
    if (StringUtils.isNoneBlank(jobKey)) {
      key = String.join(SystemProperties.SEPERATOR, key, jobKey);
    }
    RList<String> list = hashedListmap.get(key);
    return list.size();
  }

  @Override
  public List<JobExecuteRecord> queryJobRecords(String groupKey, String jobKey, String jobId,
      int from, int to) {
    RListMultimap<String, JobExecuteRecord> listMultimap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    List<JobExecuteRecord> records = Lists.newArrayList();
    if (StringUtils.isNoneBlank(jobId)) {
      List<JobExecuteRecord> list = listMultimap.get(jobId);
      if (list.size() <= from) {
        return records;
      }
      RListMultimap<String, JobRecordStatus> recordStatus =
          redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
              SystemProperties.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
      for (JobExecuteRecord record : list.subList(from, Math.min(to, list.size()))) {
        recordStatus.get(record.getId()).forEach(r -> r.fill(record));
        records.add(record);
      }
      return records;
    }
    RListMultimap<String, String> hashedListmap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, HashRef.class.getName()));
    String key = JobExecuteRecord.class.getName();
    if (StringUtils.isNoneBlank(groupKey)) {
      key = groupKey;
    }
    if (StringUtils.isNoneBlank(jobKey)) {
      key = String.join(SystemProperties.SEPERATOR, key, jobKey);
    }
    RList<String> list = hashedListmap.get(key);
    if (list.size() <= from) {
      return records;
    }
    for (String id : list.subList(from, Math.min(to, list.size()))) {
      records.add(findJobExecuteRecordById(id));
    }
    return records;
  }

  @Override
  public JobExecuteRecord findJobExecuteRecordById(String id) {
    RMap<String, HashRef> map = redissonClient.getMap(String.join(SystemProperties.SEPERATOR,
        SystemProperties.EXECUTOR_PREFIX, HashRef.class.getName()));
    HashRef ref = map.get(id);
    Checks.notNull(ref);
    RListMultimap<String, JobExecuteRecord> listMultimap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobExecuteRecord.class.getName()));
    JobExecuteRecord record = listMultimap.get(ref.getKey()).get(ref.getIndex());
    RListMultimap<String, JobRecordStatus> recordStatus =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
    recordStatus.get(id).forEach(r -> r.fill(record));
    return record;
  }

  @Override
  public void updateJobRecord(JobExecuteRecord record) {
    // 日志只会增加不会减少，使用此种方式避开处理事务和异步问题
    RListMultimap<String, JobRecordStatus> listMultimap =
        redissonClient.getListMultimap(String.join(SystemProperties.SEPERATOR,
            SystemProperties.EXECUTOR_PREFIX, JobRecordStatus.class.getName()));
    JobRecordStatus status = new JobRecordStatus(record);
    listMultimap.put(record.getId(), status);
  }
}
