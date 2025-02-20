/*
 * Copyright (c) 2023 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.oceanbase.odc.service.flow;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.compress.utils.Lists;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.Charsets;
import com.oceanbase.odc.common.json.JsonUtils;
import com.oceanbase.odc.core.authority.util.SkipAuthorize;
import com.oceanbase.odc.core.flow.model.FlowTaskResult;
import com.oceanbase.odc.core.shared.PreConditions;
import com.oceanbase.odc.core.shared.Verify;
import com.oceanbase.odc.core.shared.constant.ErrorCodes;
import com.oceanbase.odc.core.shared.constant.ResourceType;
import com.oceanbase.odc.core.shared.constant.TaskType;
import com.oceanbase.odc.core.shared.exception.AccessDeniedException;
import com.oceanbase.odc.core.shared.exception.BadRequestException;
import com.oceanbase.odc.core.shared.exception.InternalServerError;
import com.oceanbase.odc.core.shared.exception.NotFoundException;
import com.oceanbase.odc.core.shared.exception.UnsupportedException;
import com.oceanbase.odc.metadb.flow.FlowInstanceRepository;
import com.oceanbase.odc.metadb.task.JobEntity;
import com.oceanbase.odc.metadb.task.TaskEntity;
import com.oceanbase.odc.metadb.task.TaskRepository;
import com.oceanbase.odc.plugin.task.api.datatransfer.model.DataTransferConfig;
import com.oceanbase.odc.plugin.task.api.datatransfer.model.DataTransferTaskResult;
import com.oceanbase.odc.service.common.FileManager;
import com.oceanbase.odc.service.common.model.FileBucket;
import com.oceanbase.odc.service.common.response.ListResponse;
import com.oceanbase.odc.service.common.response.SuccessResponse;
import com.oceanbase.odc.service.common.util.OdcFileUtil;
import com.oceanbase.odc.service.datatransfer.LocalFileManager;
import com.oceanbase.odc.service.dispatch.DispatchResponse;
import com.oceanbase.odc.service.dispatch.JobDispatchChecker;
import com.oceanbase.odc.service.dispatch.RequestDispatcher;
import com.oceanbase.odc.service.dispatch.TaskDispatchChecker;
import com.oceanbase.odc.service.flow.instance.FlowTaskInstance;
import com.oceanbase.odc.service.flow.model.BinaryDataResult;
import com.oceanbase.odc.service.flow.model.ByteArrayDataResult;
import com.oceanbase.odc.service.flow.model.FileBasedDataResult;
import com.oceanbase.odc.service.flow.model.FlowInstanceDetailResp;
import com.oceanbase.odc.service.flow.model.FlowNodeStatus;
import com.oceanbase.odc.service.flow.model.FlowNodeType;
import com.oceanbase.odc.service.flow.model.PreCheckTaskResult;
import com.oceanbase.odc.service.flow.task.OssTaskReferManager;
import com.oceanbase.odc.service.flow.task.model.DatabaseChangeResult;
import com.oceanbase.odc.service.flow.task.model.MockDataTaskResult;
import com.oceanbase.odc.service.flow.task.model.MockProperties;
import com.oceanbase.odc.service.flow.task.model.OnlineSchemaChangeTaskResult;
import com.oceanbase.odc.service.flow.task.model.PartitionPlanTaskResult;
import com.oceanbase.odc.service.flow.task.model.ResultSetExportResult;
import com.oceanbase.odc.service.flow.task.model.ShadowTableSyncTaskResult;
import com.oceanbase.odc.service.flow.task.model.SqlCheckTaskResult;
import com.oceanbase.odc.service.flow.task.util.DatabaseChangeOssUrlCache;
import com.oceanbase.odc.service.iam.auth.AuthenticationFacade;
import com.oceanbase.odc.service.objectstorage.ObjectStorageFacade;
import com.oceanbase.odc.service.objectstorage.cloud.CloudObjectStorageService;
import com.oceanbase.odc.service.partitionplan.PartitionPlanService;
import com.oceanbase.odc.service.permissionapply.project.ApplyProjectResult;
import com.oceanbase.odc.service.schedule.flowtask.AlterScheduleResult;
import com.oceanbase.odc.service.session.model.SqlExecuteResult;
import com.oceanbase.odc.service.task.TaskService;
import com.oceanbase.odc.service.task.caller.ExecutorIdentifier;
import com.oceanbase.odc.service.task.caller.ExecutorIdentifierParser;
import com.oceanbase.odc.service.task.config.TaskFrameworkProperties;
import com.oceanbase.odc.service.task.constants.JobAttributeKeyConstants;
import com.oceanbase.odc.service.task.constants.JobUrlConstants;
import com.oceanbase.odc.service.task.executor.logger.LogUtils;
import com.oceanbase.odc.service.task.model.ExecutorInfo;
import com.oceanbase.odc.service.task.model.OdcTaskLogLevel;
import com.oceanbase.odc.service.task.service.TaskFrameworkService;
import com.oceanbase.odc.service.task.util.HttpUtil;
import com.oceanbase.odc.service.task.util.JobUtils;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link FlowTaskInstanceService}
 *
 * @author yh263208
 * @date 2022-03-07 19:53
 * @since ODC_release_3.3.0
 */
@Slf4j
@Service
@SkipAuthorize("permission check inside")
public class FlowTaskInstanceService {

    @Autowired
    private MockProperties mockProperties;
    @Autowired
    private TaskRepository taskRepository;
    @Autowired
    private TaskService taskService;
    @Autowired
    private FlowInstanceService flowInstanceService;
    @Autowired
    private CloudObjectStorageService cloudObjectStorageService;
    @Autowired
    private OssTaskReferManager ossTaskReferManager;
    @Autowired
    private RequestDispatcher requestDispatcher;
    @Autowired
    private ObjectStorageFacade objectStorageFacade;
    @Autowired
    private AuthenticationFacade authenticationFacade;
    @Autowired
    private ApprovalPermissionService approvalPermissionService;
    @Autowired
    private FlowInstanceRepository flowInstanceRepository;
    @Autowired
    private TaskDispatchChecker dispatchChecker;
    @Autowired
    private DatabaseChangeOssUrlCache databaseChangeOssUrlCache;
    @Autowired
    private LocalFileManager localFileManager;
    @Autowired
    private PartitionPlanService partitionPlanService;

    @Value("${odc.task.async.result-preview-max-size-bytes:5242880}")
    private long resultPreviewMaxSizeBytes;

    @Autowired
    private TaskFrameworkProperties taskFrameworkProperties;

    @Autowired
    private TaskFrameworkService taskFrameworkService;

    @Autowired
    private JobDispatchChecker jobDispatchChecker;

    public FlowInstanceDetailResp executeTask(@NotNull Long id) throws IOException {
        List<FlowTaskInstance> instances =
                filterTaskInstance(id, instance -> instance.getStatus() == FlowNodeStatus.PENDING);
        PreConditions.validExists(ResourceType.ODC_FLOW_TASK_INSTANCE, "flowInstanceId", id,
                () -> instances.size() > 0);
        Verify.singleton(instances, "FlowTaskInstance");

        FlowTaskInstance taskInstance = instances.get(0);
        TaskEntity taskEntity = taskService.detail(taskInstance.getTargetTaskId());
        if (taskEntity.getTaskType() == TaskType.IMPORT && !dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 对于导入任务，由于文件是上传到某台机器上的，因此任务的实际执行也一定要在那台机器上才行。 如果确认执行动作发送到了非任务所在机器，就需要转发，否则任务会因为找不到上传文件而报错。异步
             * 执行之所以没这个问题是因为接入了 {@code objectStorage} ，后续如果导入任务也接入了 {@code objectStorage} 则不用再有此逻辑。
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse response = requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            return response.getContentByType(new TypeReference<SuccessResponse<FlowInstanceDetailResp>>() {}).getData();
        }
        taskInstance.confirmExecute();
        return FlowInstanceDetailResp.withIdAndType(id, taskInstance.getTaskType());
    }

    public String getLog(@NotNull Long id, OdcTaskLogLevel level) throws IOException {
        Optional<TaskEntity> taskEntityOptional = getTaskEntity(id,
                instance -> (instance.getStatus().isFinalStatus() || instance.getStatus() == FlowNodeStatus.EXECUTING)
                        && instance.getTaskType() != TaskType.SQL_CHECK
                        && instance.getTaskType() != TaskType.PRE_CHECK
                        && instance.getTaskType() != TaskType.GENERATE_ROLLBACK);
        if (!taskEntityOptional.isPresent()) {
            return null;
        }
        TaskEntity taskEntity = taskEntityOptional.get();
        if (taskEntity.getResultJson() == null) {
            return null;
        }

        if (taskFrameworkProperties.isEnabled() && taskEntity.getJobId() != null) {
            return getLogByTaskFramework(level, taskEntity.getJobId());
        }

        if (!dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 任务不在当前机器上，需要进行 {@code RPC} 转发获取
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse response = requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            return response.getContentByType(new TypeReference<SuccessResponse<String>>() {}).getData();
        }
        return taskService.getLog(taskEntity.getCreatorId(), taskEntity.getId() + "", taskEntity.getTaskType(), level);
    }

    private String getLogByTaskFramework(OdcTaskLogLevel level, Long jobId) throws IOException {
        // forward to target host when task is not be executed on this machine or running in k8s pod
        JobEntity jobEntity = taskFrameworkService.find(jobId);
        PreConditions.notNull(jobEntity, "job not found by id " + jobId);

        if (JobUtils.isK8sRunMode(jobEntity.getRunMode()) && cloudObjectStorageService.supported()) {

            String logIdKey = level == OdcTaskLogLevel.ALL ? JobAttributeKeyConstants.LOG_STORAGE_ALL_OBJECT_ID
                    : JobAttributeKeyConstants.LOG_STORAGE_WARN_OBJECT_ID;
            String objId = taskFrameworkService.findByJobIdAndAttributeKey(jobEntity.getId(), logIdKey);
            String bucketName = taskFrameworkService.findByJobIdAndAttributeKey(jobEntity.getId(),
                    JobAttributeKeyConstants.LOG_STORAGE_BUCKET_NAME);

            if (objId != null && bucketName != null) {
                if (log.isDebugEnabled()) {
                    log.debug("job: {} is finished, try to get log from local or oss.", jobEntity.getId());
                }
                // check log file is exist on current disk
                String logFileStr = LogUtils.getTaskLogFileWithPath(jobEntity.getId(), level);
                if (new File(logFileStr).exists()) {
                    return LogUtils.getLogContent(logFileStr, LogUtils.MAX_LOG_LINE_COUNT, LogUtils.MAX_LOG_BYTE_COUNT);
                }

                File tempFile = cloudObjectStorageService.downloadToTempFile(objId);
                try (FileInputStream inputStream = new FileInputStream(tempFile)) {
                    FileUtils.copyInputStreamToFile(inputStream, new File(logFileStr));
                } finally {
                    FileUtils.deleteQuietly(tempFile);
                }
                return LogUtils.getLogContent(logFileStr, LogUtils.MAX_LOG_LINE_COUNT, LogUtils.MAX_LOG_BYTE_COUNT);

            }
        }
        if (JobUtils.isK8sRunMode(jobEntity.getRunMode())) {
            if (jobEntity.getExecutorDestroyedTime() == null && jobEntity.getExecutorEndpoint() != null) {
                if (log.isDebugEnabled()) {
                    log.debug("job: {} is not finished, try to get log from remote pod.", jobEntity.getId());
                }
                String hostWithUrl = jobEntity.getExecutorEndpoint() + String.format(JobUrlConstants.LOG_QUERY,
                        jobEntity.getId()) + "?logType=" + level.getName();
                SuccessResponse<String> response =
                        HttpUtil.request(hostWithUrl, new TypeReference<SuccessResponse<String>>() {});
                return response.getData();
            }
        } else {
            // process mode when executor is not current host, forward to target
            if (!jobDispatchChecker.isExecutorOnThisMachine(jobEntity)) {
                ExecutorIdentifier ei = ExecutorIdentifierParser.parser(jobEntity.getExecutorIdentifier());
                DispatchResponse response = requestDispatcher.forward(ei.getHost(), ei.getPort());
                return response.getContentByType(new TypeReference<String>() {});
            }
            String logFileStr = LogUtils.getTaskLogFileWithPath(jobEntity.getId(), level);
            return LogUtils.getLogContent(logFileStr, LogUtils.MAX_LOG_LINE_COUNT, LogUtils.MAX_LOG_BYTE_COUNT);
        }
        // if log not found then return description to user
        return jobEntity.getDescription();
    }

    public List<? extends FlowTaskResult> getResult(@NotNull Long id) throws IOException {
        TaskEntity task = flowInstanceService.getTaskByFlowInstanceId(id);
        if (task.getTaskType() == TaskType.ONLINE_SCHEMA_CHANGE) {
            return getOnlineSchemaChangeResult(task);
        } else if (task.getTaskType() == TaskType.EXPORT) {
            return getDataTransferResult(task);
        }

        Optional<TaskEntity> taskEntityOptional = getCompleteTaskEntity(id);
        if (!taskEntityOptional.isPresent()) {
            return Collections.emptyList();
        }
        TaskEntity taskEntity = taskEntityOptional.get();
        if (taskEntity.getTaskType() == TaskType.ASYNC) {
            return getAsyncResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.MOCKDATA) {
            return getMockDataResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.IMPORT) {
            return getDataTransferResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.SHADOWTABLE_SYNC) {
            return getShadowTableSyncTaskResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.PARTITION_PLAN) {
            return getPartitionPlanResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.ALTER_SCHEDULE) {
            return getAlterScheduleResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.EXPORT_RESULT_SET) {
            return getResultSetExportResult(taskEntity);
        } else if (taskEntity.getTaskType() == TaskType.APPLY_PROJECT_PERMISSION) {
            return getApplyProjectResult(taskEntity);
        } else {
            throw new UnsupportedException(ErrorCodes.Unsupported, new Object[] {ResourceType.ODC_TASK},
                    "Unsupported task type: " + taskEntity.getTaskType());
        }
    }

    public List<? extends FlowTaskResult> getResult(
            @NotNull Long flowInstanceId, @NotNull Long nodeInstanceId) throws IOException {
        List<FlowTaskInstance> taskInstances = this.flowInstanceService.mapFlowInstance(
                flowInstanceId, i -> i.filterInstanceNode(f -> f instanceof FlowTaskInstance)
                        .stream().map(f -> (FlowTaskInstance) f).collect(Collectors.toList()),
                false);
        Optional<FlowTaskInstance> target = taskInstances.stream()
                .filter(f -> f.getId().equals(nodeInstanceId)).findFirst();
        if (!target.isPresent()) {
            return Collections.emptyList();
        }
        FlowTaskInstance taskInstance = target.get();
        if (taskInstance.getTaskType() == TaskType.SQL_CHECK
                || taskInstance.getTaskType() == TaskType.PRE_CHECK) {
            Long taskId = taskInstance.getTargetTaskId();
            TaskEntity taskEntity = this.taskService.detail(taskId);
            PreCheckTaskResult result = JsonUtils.fromJson(taskEntity.getResultJson(), PreCheckTaskResult.class);
            if (Objects.isNull(result)) {
                return Collections.emptyList();
            }
            SqlCheckTaskResult checkTaskResult = result.getSqlCheckResult();
            ExecutorInfo info = result.getExecutorInfo();
            if (!this.dispatchChecker.isThisMachine(info)) {
                DispatchResponse response = requestDispatcher.forward(info.getHost(), info.getPort());
                return response.getContentByType(
                        new TypeReference<ListResponse<SqlCheckTaskResult>>() {}).getData().getContents();
            }
            String dir = FileManager.generateDir(FileBucket.PRE_CHECK) + File.separator + taskId;
            Verify.notNull(checkTaskResult.getFileName(), "SqlCheckResultFileName");
            File jsonFile = new File(dir + File.separator + checkTaskResult.getFileName());
            if (!jsonFile.exists()) {
                throw new NotFoundException(ErrorCodes.NotFound, new Object[] {
                        ResourceType.ODC_FILE.getLocalizedMessage(), "file", jsonFile.getName()}, "File is not found");
            }
            String content = FileUtils.readFileToString(jsonFile, Charsets.UTF_8);
            checkTaskResult = JsonUtils.fromJson(content, SqlCheckTaskResult.class);
            checkTaskResult.setFileName(null);
            result.setSqlCheckResult(checkTaskResult);
            result.setExecutorInfo(null);
            return Collections.singletonList(result);
        } else {
            throw new UnsupportedException(ErrorCodes.Unsupported, new Object[] {ResourceType.ODC_TASK},
                    "Unsupported task type: " + taskInstance.getTaskType());
        }
    }

    private List<ShadowTableSyncTaskResult> getShadowTableSyncTaskResult(TaskEntity taskEntity) {
        return innerGetResult(taskEntity, ShadowTableSyncTaskResult.class);
    }

    public List<BinaryDataResult> download(@NonNull Long flowInstanceId, String targetFileName) throws IOException {
        Optional<TaskEntity> taskEntityOptional = getDownloadableTaskEntity(flowInstanceId);
        PreConditions.validExists(ResourceType.ODC_FILE, "flowInstanceId", flowInstanceId,
                taskEntityOptional::isPresent);
        TaskEntity taskEntity = taskEntityOptional.get();
        if (!dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 任务不在该节点上，需要进行请求转发
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse dispatchResponse =
                    requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            HttpHeaders headers = dispatchResponse.getResponseHeaders();
            if (headers == null) {
                return Collections
                        .singletonList(new ByteArrayDataResult("download.data", dispatchResponse.getContent()));
            }
            String fileName = headers.getContentDisposition().getFilename();
            if (fileName == null) {
                return Collections
                        .singletonList(new ByteArrayDataResult("download.data", dispatchResponse.getContent()));
            }
            return Collections.singletonList(new ByteArrayDataResult(fileName, dispatchResponse.getContent()));
        }
        return internalDownload(taskEntity, targetFileName);
    }

    /**
     * download task relative files, only for internal usage
     */
    public List<BinaryDataResult> internalDownload(@NonNull TaskEntity taskEntity, String targetFileName)
            throws IOException {
        List<File> targetFiles;
        if (taskEntity.getTaskType() == TaskType.MOCKDATA) {
            File filePath = mockProperties.getDownloadPath(taskEntity.getId() + "");
            PreConditions.validExists(ResourceType.ODC_TASK, "id", taskEntity.getId(), filePath::exists);
            File[] files = filePath.listFiles();
            targetFiles = Arrays.asList(files);
        } else if (taskEntity.getTaskType() == TaskType.EXPORT) {
            targetFiles = new LinkedList<>();
            for (DataTransferTaskResult result : getDataTransferResult(taskEntity)) {
                if (result.getExportZipFilePath() == null) {
                    continue;
                }
                Optional<File> optional = localFileManager.findByName(TaskType.EXPORT,
                        taskEntity.getId() + "", result.getExportZipFilePath());
                if (!optional.isPresent()) {
                    continue;
                }
                targetFiles.add(optional.get());
            }
        } else if (taskEntity.getTaskType() == TaskType.IMPORT) {
            String bucket = this.localFileManager.getUploadBucket(taskEntity.getCreatorId() + "");
            targetFiles = new LinkedList<>();
            for (String target : getDownloadImportFileNames(taskEntity, targetFileName)) {
                Optional<File> uploadFile = localFileManager.findByName(TaskType.IMPORT, bucket, target);
                if (!uploadFile.isPresent()) {
                    throw new NotFoundException(ResourceType.ODC_FILE, "fileName", target);
                }
                targetFiles.add(uploadFile.get());
            }
        } else if (taskEntity.getTaskType() == TaskType.ASYNC) {
            targetFiles = getAsyncResult(taskEntity).stream().map(value -> {
                String zipFileId = value.getZipFileId();
                String filePath = FileManager.generatePath(FileBucket.ASYNC, zipFileId) + ".zip";
                return new File(filePath);
            }).collect(Collectors.toList());
        } else if (taskEntity.getTaskType() == TaskType.EXPORT_RESULT_SET) {
            List<ResultSetExportResult> results = getResultSetExportResult(taskEntity);
            Verify.singleton(results, "ResultSetExportResult");
            String fileName = results.get(0).getFileName();
            PreConditions.validExists(ResourceType.ODC_FILE, "taskId", taskEntity.getId(),
                    () -> StringUtils.isNotBlank(fileName));
            Optional<File> optional =
                    localFileManager.findByName(TaskType.EXPORT_RESULT_SET, taskEntity.getId().toString(), fileName);
            targetFiles = Collections.singletonList(
                    optional.orElseThrow(() -> new NotFoundException(ResourceType.ODC_FILE, "fileName", fileName)));
        } else {
            throw new UnsupportedException(ErrorCodes.Unsupported, new Object[] {ResourceType.ODC_TASK},
                    "Unsupported task type: " + taskEntity.getTaskType());
        }
        return targetFiles.stream().map(FileBasedDataResult::new).collect(Collectors.toList());
    }

    /**
     * download a mock task's script, only for public cloud
     */
    public List<URL> download(@NonNull Long flowInstanceId, String targetFileName,
            Long expiration, TimeUnit timeUnit) throws Exception {
        Optional<TaskEntity> taskEntityOptional = getDownloadableTaskEntity(flowInstanceId);
        PreConditions.validExists(ResourceType.ODC_FILE, "flowInstanceId", flowInstanceId,
                taskEntityOptional::isPresent);
        TaskEntity taskEntity = taskEntityOptional.get();
        TaskType taskType = taskEntity.getTaskType();
        Long expirationSecs = null;
        if (expiration != null && timeUnit != null) {
            expirationSecs = TimeUnit.SECONDS.convert(expiration, timeUnit);
        }
        if (taskType == TaskType.IMPORT) {
            if (!dispatchChecker.isTaskEntitySubmitOnThisMachine(taskEntity)) {
                /**
                 * 任务不在该节点上，需要进行请求转发（公有云导入任务需要从 submitter 下载）
                 */
                ExecutorInfo submitterInfo = JsonUtils.fromJson(taskEntity.getSubmitter(), ExecutorInfo.class);
                DispatchResponse dispatchResponse =
                        requestDispatcher.forward(submitterInfo.getHost(), submitterInfo.getPort());
                String url =
                        dispatchResponse.getContentByType(new TypeReference<SuccessResponse<String>>() {}).getData();
                return Collections.singletonList(new URL(url));
            }
            List<URL> urls = new LinkedList<>();
            for (String target : getDownloadImportFileNames(taskEntity, targetFileName)) {
                String objectName = ossTaskReferManager.get(target);
                if (objectName == null) {
                    throw new NotFoundException(ResourceType.ODC_FILE, "fileName", target);
                }
                urls.add(generatePresignedUrl(objectName, expirationSecs));
            }
            return urls;
        }
        if (!dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 任务不在该节点上，需要进行请求转发
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse dispatchResponse =
                    requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            String url = dispatchResponse.getContentByType(new TypeReference<SuccessResponse<String>>() {}).getData();
            return Collections.singletonList(new URL(url));
        }
        if (taskEntity.getTaskType() == TaskType.MOCKDATA) {
            List<MockDataTaskResult> details = getMockDataResult(taskEntity);
            Verify.singleton(details, "MockDataDetail");

            String objectName = details.get(0).getObjectName();
            PreConditions.validExists(ResourceType.ODC_FILE, "taskId", taskEntity.getId(),
                    () -> StringUtils.isNotBlank(objectName));
            URL url = generatePresignedUrl(objectName, expirationSecs);
            if (url == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(url);
        } else if (taskEntity.getTaskType() == TaskType.EXPORT) {
            List<DataTransferTaskResult> taskResults = getDataTransferResult(taskEntity);
            Verify.singleton(taskResults, "DataTransferTaskResult");

            String objectName = taskResults.get(0).getExportZipFilePath();
            PreConditions.validExists(ResourceType.ODC_FILE, "taskId", taskEntity.getId(),
                    () -> StringUtils.isNotBlank(objectName));
            URL url = generatePresignedUrl(objectName, expirationSecs);
            if (url == null) {
                return Collections.emptyList();
            }
            return Collections.singletonList(url);
        } else if (taskEntity.getTaskType() == TaskType.EXPORT_RESULT_SET) {
            List<ResultSetExportResult> results = getResultSetExportResult(taskEntity);
            Verify.singleton(results, "ResultSetExportResult");
            String objectName = results.get(0).getFileName();
            PreConditions.validExists(ResourceType.ODC_FILE, "taskId", taskEntity.getId(),
                    () -> StringUtils.isNotBlank(objectName));
            return Collections.singletonList(generatePresignedUrl(objectName, expirationSecs));
        } else {
            throw new UnsupportedException(ErrorCodes.Unsupported, new Object[] {ResourceType.ODC_TASK},
                    "Unsupported task type: " + taskEntity.getTaskType());
        }
    }

    public List<String> getAsyncDownloadUrl(Long id, List<String> objectIds) {
        Set<Long> creatorIdSet = flowInstanceRepository.findCreatorIdById(id);
        if (creatorIdSet.stream().anyMatch(creatorId -> creatorId == authenticationFacade.currentUserId())
                || approvalPermissionService.getApprovableApprovalInstances()
                        .stream().anyMatch(entity -> entity.getFlowInstanceId() == id)) {
            List<String> downloadUrls = Lists.newArrayList();
            for (String objectId : objectIds) {
                downloadUrls.add(objectStorageFacade.getDownloadUrl(
                        "async".concat(File.separator).concat(creatorIdSet.iterator().next().toString()),
                        objectId));
            }
            return downloadUrls;
        } else {
            log.info("Bad download request");
            throw new AccessDeniedException();
        }
    }

    public List<SqlExecuteResult> getExecuteResult(Long flowInstanceId) throws IOException {
        Optional<TaskEntity> taskEntityOptional = getCompleteTaskEntity(flowInstanceId);
        if (!taskEntityOptional.isPresent()) {
            return Collections.emptyList();
        }
        TaskEntity taskEntity = taskEntityOptional.get();
        if (!dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 任务不在当前机器上，需要进行 {@code RPC} 转发获取
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse response = requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            return response.getContentByType(new TypeReference<ListResponse<SqlExecuteResult>>() {}).getData()
                    .getContents();
        }
        DatabaseChangeResult asyncTaskResult =
                JsonUtils.fromJson(taskEntity.getResultJson(), DatabaseChangeResult.class);
        String jsonFileName = asyncTaskResult.getJsonFileName();
        List<SqlExecuteResult> result;
        try {
            String jsonString =
                    OdcFileUtil.readFile(String.format("%s/ASYNC/%s.json", FileManager.basePath, jsonFileName));
            result = JsonUtils.fromJsonList(jsonString, SqlExecuteResult.class);
            return result;
        } catch (IOException ex) {
            log.info("query result file not found, fileName={}", jsonFileName);
            throw new NotFoundException(ResourceType.ODC_FILE, "fileName", jsonFileName);
        }
    }

    private Set<String> getDownloadImportFileNames(@NonNull TaskEntity taskEntity, String targetFileName) {
        DataTransferConfig config = JsonUtils.fromJson(
                taskEntity.getParametersJson(), DataTransferConfig.class);
        if (config == null) {
            throw new InternalServerError("Json value is illegal");
        }
        List<String> importFileNames = config.getImportFileName();
        if (CollectionUtils.isEmpty(importFileNames)) {
            throw new NotFoundException(ResourceType.ODC_FILE, "taskId", taskEntity.getId());
        }
        if (targetFileName == null && importFileNames.size() > 1) {
            throw new BadRequestException("Download file not specified");
        }
        if (targetFileName != null) {
            return importFileNames.stream().filter(targetFileName::equals).collect(Collectors.toSet());
        }
        return new HashSet<>(importFileNames);
    }

    private List<FlowTaskInstance> filterTaskInstance(@NonNull Long flowInstanceId,
            @NonNull Predicate<FlowTaskInstance> predicate) {
        return flowInstanceService.mapFlowInstance(flowInstanceId,
                flowInstance -> flowInstance.filterInstanceNode(instance -> {
                    if (instance.getNodeType() != FlowNodeType.SERVICE_TASK) {
                        return false;
                    }
                    return predicate.test((FlowTaskInstance) instance);
                }).stream().map(instance -> {
                    Verify.verify(instance instanceof FlowTaskInstance, "FlowTaskInstance's type is illegal");
                    return (FlowTaskInstance) instance;
                }).collect(Collectors.toList()), false);
    }

    private List<DatabaseChangeResult> getAsyncResult(@NonNull TaskEntity taskEntity) throws IOException {
        if (!dispatchChecker.isTaskEntityOnThisMachine(taskEntity)) {
            /**
             * 任务不在当前机器上，需要进行 {@code RPC} 转发获取
             */
            ExecutorInfo executorInfo = JsonUtils.fromJson(taskEntity.getExecutor(), ExecutorInfo.class);
            DispatchResponse response = requestDispatcher.forward(executorInfo.getHost(), executorInfo.getPort());
            return response.getContentByType(new TypeReference<ListResponse<DatabaseChangeResult>>() {})
                    .getData().getContents();
        }
        List<DatabaseChangeResult> results = innerGetResult(taskEntity, DatabaseChangeResult.class);
        if (CollectionUtils.isEmpty(results)) {
            return Collections.emptyList();
        }
        Verify.singleton(results, "OdcAsyncTaskResults");

        DatabaseChangeResult result = results.get(0);
        // read records from file
        if (StringUtils.isNotEmpty(result.getErrorRecordsFilePath())) {
            File errorRecords = new File(result.getErrorRecordsFilePath());
            if (!errorRecords.exists()) {
                result.setRecords(Collections.singletonList("Execute result has been expired."));
            } else {
                try {
                    result.setRecords(FileUtils.readLines(errorRecords));
                } catch (IOException e) {
                    log.error("Error occurs while reading records from file", e);
                    result.setRecords(Collections
                            .singletonList("Error occurs while reading records from file " + e.getMessage()));
                }
            }
        } else {
            result.setRecords(Collections.emptyList());
        }
        if (StringUtils.isNotEmpty(result.getJsonFileName())) {
            File jsonFile = new File(String.format("%s/ASYNC/%s.json", FileManager.basePath, result.getJsonFileName()));
            if (jsonFile.exists()) {
                BasicFileAttributes attributes = Files.readAttributes(jsonFile.toPath(), BasicFileAttributes.class);
                if (attributes.isRegularFile()) {
                    result.setResultPreviewMaxSizeBytes(resultPreviewMaxSizeBytes);
                    result.setJsonFileBytes(attributes.size());
                }
            }
        }
        if (cloudObjectStorageService.supported()) {
            result.setZipFileDownloadUrl(databaseChangeOssUrlCache.get(taskEntity.getId()));
        }
        return Collections.singletonList(result);
    }

    private List<MockDataTaskResult> getMockDataResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, MockDataTaskResult.class);
    }

    private List<DataTransferTaskResult> getDataTransferResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, DataTransferTaskResult.class);
    }

    private List<ResultSetExportResult> getResultSetExportResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, ResultSetExportResult.class);
    }

    private List<PartitionPlanTaskResult> getPartitionPlanResult(@NonNull TaskEntity taskEntity) {
        List<PartitionPlanTaskResult> partitionPlanTaskResults = innerGetResult(taskEntity,
                PartitionPlanTaskResult.class);
        if (partitionPlanTaskResults.isEmpty()) {
            return null;
        }
        Long flowInstanceId = partitionPlanTaskResults.get(0).getFlowInstanceId();
        partitionPlanTaskResults.get(0).setDatabasePartitionPlan(
                partitionPlanService.findDatabasePartitionPlanByFlowInstanceId(flowInstanceId));
        return partitionPlanTaskResults;
    }

    private List<AlterScheduleResult> getAlterScheduleResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, AlterScheduleResult.class);
    }

    private List<OnlineSchemaChangeTaskResult> getOnlineSchemaChangeResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, OnlineSchemaChangeTaskResult.class);
    }

    private List<ApplyProjectResult> getApplyProjectResult(@NonNull TaskEntity taskEntity) {
        return innerGetResult(taskEntity, ApplyProjectResult.class);
    }

    private <T extends FlowTaskResult> List<T> innerGetResult(@NonNull TaskEntity taskEntity,
            @NonNull Class<T> clazz) {
        String resultJson = taskEntity.getResultJson();
        if (resultJson == null) {
            return Collections.emptyList();
        }
        T detail = JsonUtils.fromJson(resultJson, clazz);
        if (detail == null) {
            return Collections.emptyList();
        }
        return Collections.singletonList(detail);
    }

    private Optional<TaskEntity> getCompleteTaskEntity(@NonNull Long flowInstanceId) {
        return getTaskEntity(flowInstanceId, i -> i.getStatus().isFinalStatus()
                && i.getTaskType() != TaskType.SQL_CHECK
                && i.getTaskType() != TaskType.PRE_CHECK
                && i.getTaskType() != TaskType.GENERATE_ROLLBACK);
    }

    private Optional<TaskEntity> getDownloadableTaskEntity(@NonNull Long flowInstanceId) {
        AtomicBoolean exists = new AtomicBoolean(false);
        return getTaskEntity(flowInstanceId, instance -> {
            if (instance.getTaskType() == TaskType.IMPORT && !exists.get()) {
                exists.set(true);
                return true;
            } else {
                return instance.getStatus().isFinalStatus()
                        && instance.getTaskType() != TaskType.IMPORT
                        && instance.getTaskType() != TaskType.SQL_CHECK
                        && instance.getTaskType() != TaskType.PRE_CHECK
                        && instance.getTaskType() != TaskType.GENERATE_ROLLBACK
                        && instance.getTaskType() != TaskType.APPLY_PROJECT_PERMISSION;
            }
        });
    }

    private Optional<TaskEntity> getTaskEntity(@NonNull Long flowInstanceId,
            @NonNull Predicate<FlowTaskInstance> predicate) {
        List<FlowTaskInstance> taskInstances = filterTaskInstance(flowInstanceId, predicate);
        if (CollectionUtils.isEmpty(taskInstances)) {
            return Optional.empty();
        }
        Verify.singleton(taskInstances, "TaskInstances");

        FlowTaskInstance flowTaskInstance = taskInstances.get(0);
        Long targetTaskId = flowTaskInstance.getTargetTaskId();
        Verify.notNull(targetTaskId, "TargetTaskId can not be null");

        Optional<TaskEntity> optional = taskRepository.findById(targetTaskId);
        PreConditions.validExists(ResourceType.ODC_TASK, "id", targetTaskId, optional::isPresent);
        return optional;
    }

    private URL generatePresignedUrl(String objectName, Long expirationSeconds) throws IOException {
        return cloudObjectStorageService.generateDownloadUrl(objectName, expirationSeconds);
    }
}
