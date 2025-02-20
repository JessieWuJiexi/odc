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

package com.oceanbase.odc.service.task.caller;

import java.util.HashMap;
import java.util.Map;

import com.oceanbase.odc.service.task.constants.JobConstants;

import lombok.Data;

/**
 * @author yaobin
 * @date 2023-11-15
 * @since 4.2.4
 */
@Data
public class PodParam {

    private Map<String, String> environments = new HashMap<>(2);

    private String imagePullPolicy = JobConstants.IMAGE_PULL_POLICY_ALWAYS;

    private Double requestCpu;

    private Long requestMem;

    private Double limitCpu;

    private Long limitMem;

    private Boolean enableMount;

    private String mountPath;

    private Long mountDiskSize;

}
