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
package com.oceanbase.odc.service.flow.task.mapper;

import com.oceanbase.odc.core.shared.constant.ErrorCodes;
import com.oceanbase.odc.core.shared.constant.ResourceType;
import com.oceanbase.odc.core.shared.constant.TaskType;
import com.oceanbase.odc.core.shared.exception.UnsupportedException;
import com.oceanbase.odc.service.common.util.SpringContextUtil;
import com.oceanbase.odc.service.flow.task.BaseRuntimeFlowableDelegate;
import com.oceanbase.odc.service.flow.task.DataTransferRuntimeFlowableTask;
import com.oceanbase.odc.service.flow.task.DatabaseChangeRuntimeFlowableTask;
import com.oceanbase.odc.service.flow.task.DatabaseChangeRuntimeFlowableTaskCopied;
import com.oceanbase.odc.service.flow.task.MockDataRuntimeFlowableTask;
import com.oceanbase.odc.service.flow.task.PartitionPlanTask;
import com.oceanbase.odc.service.flow.task.PreCheckRuntimeFlowableTask;
import com.oceanbase.odc.service.flow.task.PreCheckRuntimeFlowableTaskCopied;
import com.oceanbase.odc.service.flow.task.RollbackPlanRuntimeFlowableTask;
import com.oceanbase.odc.service.flow.task.ShadowtableSyncRuntimeFlowableTask;
import com.oceanbase.odc.service.onlineschemachange.OnlineSchemaChangeFlowableTask;
import com.oceanbase.odc.service.permissionapply.project.ApplyProjectFlowableTask;
import com.oceanbase.odc.service.resultset.ResultSetExportFlowableTask;
import com.oceanbase.odc.service.schedule.flowtask.AlterScheduleTask;
import com.oceanbase.odc.service.task.config.TaskFrameworkProperties;

import lombok.NonNull;

/**
 * Convertor for ODC
 *
 * @author yh263208
 * @date 2022-03-05 16:56
 * @since ODC_release_3.3.0
 * @see RuntimeDelegateMapper
 */
public class OdcRuntimeDelegateMapper implements RuntimeDelegateMapper {
    @Override
    public Class<? extends BaseRuntimeFlowableDelegate<?>> map(@NonNull TaskType taskType) {
        boolean enableTaskFramework = SpringContextUtil.getBean(TaskFrameworkProperties.class).isEnabled();
        switch (taskType) {
            case ASYNC:
                return enableTaskFramework ? DatabaseChangeRuntimeFlowableTaskCopied.class
                        : DatabaseChangeRuntimeFlowableTask.class;
            case MOCKDATA:
                return MockDataRuntimeFlowableTask.class;
            case IMPORT:
            case EXPORT:
                return DataTransferRuntimeFlowableTask.class;
            case SHADOWTABLE_SYNC:
                return ShadowtableSyncRuntimeFlowableTask.class;
            case PARTITION_PLAN:
                return PartitionPlanTask.class;
            case ALTER_SCHEDULE:
                return AlterScheduleTask.class;
            case ONLINE_SCHEMA_CHANGE:
                return OnlineSchemaChangeFlowableTask.class;
            case GENERATE_ROLLBACK:
                return RollbackPlanRuntimeFlowableTask.class;
            case EXPORT_RESULT_SET:
                return ResultSetExportFlowableTask.class;
            case PRE_CHECK:
                return enableTaskFramework ? PreCheckRuntimeFlowableTaskCopied.class
                        : PreCheckRuntimeFlowableTask.class;
            case APPLY_PROJECT_PERMISSION:
                return ApplyProjectFlowableTask.class;
            default:
                throw new UnsupportedException(ErrorCodes.Unsupported, new Object[] {ResourceType.ODC_TASK},
                        "Unsupported task type: " + taskType);
        }
    }

}
