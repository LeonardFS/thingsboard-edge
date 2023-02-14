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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EdgeUtils;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.cloud.CloudEvent;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DashboardId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.EntityIdFactory;
import org.thingsboard.server.common.data.id.EntityViewId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.relation.RelationTypeGroup;
import org.thingsboard.server.gen.edge.v1.RelationRequestMsg;
import org.thingsboard.server.gen.edge.v1.RelationUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UpdateMsgType;
import org.thingsboard.server.gen.edge.v1.UplinkMsg;

import java.io.IOException;
import java.util.UUID;

@Component
@Slf4j
public class RelationCloudProcessor extends BaseCloudProcessor {

    public ListenableFuture<Void> processRelationMsgFromCloud(TenantId tenantId, RelationUpdateMsg relationUpdateMsg) {
        try {
            EntityRelation entityRelation = new EntityRelation();

            UUID fromUUID = new UUID(relationUpdateMsg.getFromIdMSB(), relationUpdateMsg.getFromIdLSB());
            EntityId fromId = EntityIdFactory.getByTypeAndUuid(EntityType.valueOf(relationUpdateMsg.getFromEntityType()), fromUUID);
            entityRelation.setFrom(fromId);

            UUID toUUID = new UUID(relationUpdateMsg.getToIdMSB(), relationUpdateMsg.getToIdLSB());
            EntityId toId = EntityIdFactory.getByTypeAndUuid(EntityType.valueOf(relationUpdateMsg.getToEntityType()), toUUID);
            entityRelation.setTo(toId);

            entityRelation.setType(relationUpdateMsg.getType());
            entityRelation.setTypeGroup(relationUpdateMsg.hasTypeGroup()
                    ? RelationTypeGroup.valueOf(relationUpdateMsg.getTypeGroup()) : RelationTypeGroup.COMMON);
            entityRelation.setAdditionalInfo(JacksonUtil.OBJECT_MAPPER.readTree(relationUpdateMsg.getAdditionalInfo()));

            switch (relationUpdateMsg.getMsgType()) {
                case ENTITY_CREATED_RPC_MESSAGE:
                case ENTITY_UPDATED_RPC_MESSAGE:
                    if (isEntityExists(tenantId, entityRelation.getTo())
                            && isEntityExists(tenantId, entityRelation.getFrom())) {
                        return Futures.transform(relationService.saveRelationAsync(tenantId, entityRelation),
                                (result) -> null, dbCallbackExecutor);
                    } else {
                        log.warn("Skipping relating update msg because from/to entity doesn't exists on edge, {}", relationUpdateMsg);
                        return Futures.immediateFuture(null);
                    }
                case ENTITY_DELETED_RPC_MESSAGE:
                    return Futures.transform(relationService.deleteRelationAsync(tenantId, entityRelation),
                            (result) -> null, dbCallbackExecutor);
                case UNRECOGNIZED:
                    return handleUnsupportedMsgType(relationUpdateMsg.getMsgType());
            }
        } catch (Exception e) {
            String errMsg = String.format("Error during relation update msg %s", relationUpdateMsg);
            log.error(errMsg, e);
            return Futures.immediateFailedFuture(new RuntimeException(errMsg, e));
        }
        return Futures.immediateFuture(null);
    }

    private boolean isEntityExists(TenantId tenantId, EntityId entityId) {
        switch (entityId.getEntityType()) {
            case DEVICE:
                return deviceService.findDeviceById(tenantId, new DeviceId(entityId.getId())) != null;
            case ASSET:
                return assetService.findAssetById(tenantId, new AssetId(entityId.getId())) != null;
            case ENTITY_VIEW:
                return entityViewService.findEntityViewById(tenantId, new EntityViewId(entityId.getId())) != null;
            case CUSTOMER:
                return customerService.findCustomerById(tenantId, new CustomerId(entityId.getId())) != null;
            case USER:
                return userService.findUserById(tenantId, new UserId(entityId.getId())) != null;
            case DASHBOARD:
                return dashboardService.findDashboardById(tenantId, new DashboardId(entityId.getId())) != null;
            case EDGE:
                return edgeService.findEdgeById(tenantId, new EdgeId(entityId.getId())) != null;
            default:
                return false;
        }

    }

    public UplinkMsg processRelationRequestMsgToCloud(CloudEvent cloudEvent) throws IOException {
        EntityId entityId = EntityIdFactory.getByCloudEventTypeAndUuid(cloudEvent.getCloudEventType(), cloudEvent.getEntityId());
        RelationRequestMsg relationRequestMsg = RelationRequestMsg.newBuilder()
                .setEntityIdMSB(entityId.getId().getMostSignificantBits())
                .setEntityIdLSB(entityId.getId().getLeastSignificantBits())
                .setEntityType(entityId.getEntityType().name())
                .build();
        UplinkMsg.Builder builder = UplinkMsg.newBuilder()
                .setUplinkMsgId(EdgeUtils.nextPositiveInt())
                .addRelationRequestMsg(relationRequestMsg);
        return builder.build();
    }

    public UplinkMsg processRelationMsgToCloud(CloudEvent cloudEvent, UpdateMsgType msgType) {
        UplinkMsg msg = null;
        EntityRelation entityRelation = JacksonUtil.OBJECT_MAPPER.convertValue(cloudEvent.getEntityBody(), EntityRelation.class);
        if (entityRelation != null) {
            RelationUpdateMsg relationUpdateMsg = relationMsgConstructor.constructRelationUpdatedMsg(msgType, entityRelation);
            msg = UplinkMsg.newBuilder()
                    .setUplinkMsgId(EdgeUtils.nextPositiveInt())
                    .addRelationUpdateMsg(relationUpdateMsg).build();
        }
        return msg;
    }

}
