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
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.asset.Asset;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.gen.edge.v1.AssetUpdateMsg;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class AssetCloudProcessor extends BaseCloudProcessor {

    private final Lock assetCreationLock = new ReentrantLock();

    public ListenableFuture<Void> processAssetMsgFromCloud(TenantId tenantId,
                                                           AssetUpdateMsg assetUpdateMsg,
                                                           Long queueStartTs) {
        AssetId assetId = new AssetId(new UUID(assetUpdateMsg.getIdMSB(), assetUpdateMsg.getIdLSB()));
        switch (assetUpdateMsg.getMsgType()) {
            case ENTITY_CREATED_RPC_MESSAGE:
            case ENTITY_UPDATED_RPC_MESSAGE:
                assetCreationLock.lock();
                try {
                    Asset asset = assetService.findAssetById(tenantId, assetId);
                    if (asset == null) {
                        asset = new Asset();
                        asset.setTenantId(tenantId);
                        asset.setId(assetId);
                        asset.setCreatedTime(Uuids.unixTimestamp(assetId.getId()));
                    }
                    asset.setName(assetUpdateMsg.getName());
                    asset.setType(assetUpdateMsg.getType());
                    asset.setLabel(assetUpdateMsg.hasLabel() ? assetUpdateMsg.getLabel() : null);
                    asset.setAdditionalInfo(assetUpdateMsg.hasAdditionalInfo() ? JacksonUtil.toJsonNode(assetUpdateMsg.getAdditionalInfo()) : null);
                    if (assetUpdateMsg.hasCustomerIdMSB() && assetUpdateMsg.hasCustomerIdLSB()) {
                        CustomerId customerId = new CustomerId(new UUID(assetUpdateMsg.getCustomerIdMSB(), assetUpdateMsg.getCustomerIdLSB()));
                        asset.setCustomerId(customerId);
                    } else {
                        asset.setCustomerId(null);
                    }
                    assetService.saveAsset(asset, false);
                } finally {
                    assetCreationLock.unlock();
                }
                break;
            case ENTITY_DELETED_RPC_MESSAGE:
                Asset assetById = assetService.findAssetById(tenantId, assetId);
                if (assetById != null) {
                    assetService.deleteAsset(tenantId, assetId);
                }
                break;
            case UNRECOGNIZED:
                return handleUnsupportedMsgType(assetUpdateMsg.getMsgType());
        }

        return Futures.transform(requestForAdditionalData(tenantId, assetUpdateMsg.getMsgType(), assetId, queueStartTs), future -> null, dbCallbackExecutor);
    }

}
