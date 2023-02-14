/**
 * Copyright © 2016-2022 The Thingsboard Authors
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
package org.thingsboard.server.service.cloud.rpc.processor;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.ShortCustomerInfo;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.dashboard.DashboardService;
import org.thingsboard.server.gen.edge.v1.DashboardUpdateMsg;

import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class DashboardCloudProcessor extends BaseCloudProcessor {

    private final Lock dashboardCreationLock = new ReentrantLock();

    @Autowired
    private DashboardService dashboardService;

    public ListenableFuture<Void> processDashboardMsgFromCloud(TenantId tenantId,
                                                               DashboardUpdateMsg dashboardUpdateMsg,
                                                               Long queueStartTs) {
        DashboardId dashboardId = new DashboardId(new UUID(dashboardUpdateMsg.getIdMSB(), dashboardUpdateMsg.getIdLSB()));
        switch (dashboardUpdateMsg.getMsgType()) {
            case ENTITY_CREATED_RPC_MESSAGE:
            case ENTITY_UPDATED_RPC_MESSAGE:
                dashboardCreationLock.lock();
                try {
                    Dashboard dashboard = dashboardService.findDashboardById(tenantId, dashboardId);
                    if (dashboard == null) {
                        dashboard = new Dashboard();
                        dashboard.setId(dashboardId);
                        dashboard.setCreatedTime(Uuids.unixTimestamp(dashboardId.getId()));
                        dashboard.setTenantId(tenantId);
                    }
                    dashboard.setTitle(dashboardUpdateMsg.getTitle());
                    dashboard.setConfiguration(JacksonUtil.toJsonNode(dashboardUpdateMsg.getConfiguration()));
                    dashboardService.saveDashboard(dashboard, false);

                    if (dashboardUpdateMsg.hasCustomerIdMSB() && dashboardUpdateMsg.hasCustomerIdLSB()) {
                        CustomerId customerId = new CustomerId(new UUID(dashboardUpdateMsg.getCustomerIdMSB(), dashboardUpdateMsg.getCustomerIdLSB()));
                        dashboardService.assignDashboardToCustomer(tenantId, dashboardId, customerId);
                    } else {
                        if (dashboard.getAssignedCustomers() != null && !dashboard.getAssignedCustomers().isEmpty()) {
                            for (ShortCustomerInfo assignedCustomer : dashboard.getAssignedCustomers()) {
                                dashboardService.unassignDashboardFromCustomer(tenantId, dashboardId, assignedCustomer.getCustomerId());
                            }
                        }
                    }
                } finally {
                    dashboardCreationLock.unlock();
                }
                break;
            case ENTITY_DELETED_RPC_MESSAGE:
                Dashboard dashboardById = dashboardService.findDashboardById(tenantId, dashboardId);
                if (dashboardById != null) {
                    dashboardService.deleteDashboard(tenantId, dashboardId);
                }
                break;
            case UNRECOGNIZED:
                return handleUnsupportedMsgType(dashboardUpdateMsg.getMsgType());
        }

        return Futures.transform(requestForAdditionalData(tenantId, dashboardUpdateMsg.getMsgType(), dashboardId, queueStartTs), future -> null, dbCallbackExecutor);
    }
}
