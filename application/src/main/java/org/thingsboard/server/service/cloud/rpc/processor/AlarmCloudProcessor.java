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
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.alarm.AlarmSeverity;
import org.thingsboard.server.common.data.alarm.AlarmStatus;
import org.thingsboard.server.common.data.cloud.CloudEvent;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.id.AlarmId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.gen.edge.v1.AlarmUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UpdateMsgType;
import org.thingsboard.server.gen.edge.v1.UplinkMsg;

import java.util.concurrent.ExecutionException;

@Component
@Slf4j
public class AlarmCloudProcessor extends BaseCloudProcessor {

    public ListenableFuture<Void> processAlarmMsgFromCloud(TenantId tenantId, AlarmUpdateMsg alarmUpdateMsg) {
        log.trace("[{}] onAlarmUpdate [{}]", tenantId, alarmUpdateMsg);
        EntityId originatorId = getAlarmOriginator(tenantId, alarmUpdateMsg.getOriginatorName(),
                EntityType.valueOf(alarmUpdateMsg.getOriginatorType()));
        if (originatorId == null) {
            log.warn("Originator not found for the alarm msg {}", alarmUpdateMsg);
            return Futures.immediateFuture(null);
        }
        try {
            Alarm existentAlarm = alarmService.findLatestByOriginatorAndType(tenantId, originatorId, alarmUpdateMsg.getType()).get();
            switch (alarmUpdateMsg.getMsgType()) {
                case ENTITY_CREATED_RPC_MESSAGE:
                case ENTITY_UPDATED_RPC_MESSAGE:
                    if (existentAlarm == null || existentAlarm.getStatus().isCleared()) {
                        existentAlarm = new Alarm();
                        existentAlarm.setTenantId(tenantId);
                        existentAlarm.setType(alarmUpdateMsg.getName());
                        existentAlarm.setOriginator(originatorId);
                        existentAlarm.setSeverity(AlarmSeverity.valueOf(alarmUpdateMsg.getSeverity()));
                        existentAlarm.setStartTs(alarmUpdateMsg.getStartTs());
                        existentAlarm.setClearTs(alarmUpdateMsg.getClearTs());
                        existentAlarm.setPropagate(alarmUpdateMsg.getPropagate());
                    }
                    existentAlarm.setStatus(AlarmStatus.valueOf(alarmUpdateMsg.getStatus()));
                    existentAlarm.setAckTs(alarmUpdateMsg.getAckTs());
                    existentAlarm.setEndTs(alarmUpdateMsg.getEndTs());
                    existentAlarm.setDetails(JacksonUtil.OBJECT_MAPPER.readTree(alarmUpdateMsg.getDetails()));
                    alarmService.createOrUpdateAlarm(existentAlarm);
                    break;
                case ALARM_ACK_RPC_MESSAGE:
                    if (existentAlarm != null) {
                        alarmService.ackAlarm(tenantId, existentAlarm.getId(), alarmUpdateMsg.getAckTs());
                    }
                    break;
                case ALARM_CLEAR_RPC_MESSAGE:
                    if (existentAlarm != null) {
                        alarmService.clearAlarm(tenantId, existentAlarm.getId(), JacksonUtil.OBJECT_MAPPER.readTree(alarmUpdateMsg.getDetails()), alarmUpdateMsg.getAckTs());
                    }
                    break;
                case ENTITY_DELETED_RPC_MESSAGE:
                    if (existentAlarm != null) {
                        alarmService.deleteAlarm(tenantId, existentAlarm.getId());
                    }
                    break;
            }
            return Futures.immediateFuture(null);
        } catch (Exception e) {
            String errMsg = String.format("Failed to process alarm update msg [%s]", alarmUpdateMsg);
            log.error(errMsg, e);
            return Futures.immediateFailedFuture(new RuntimeException(errMsg, e));
        }
    }

    private EntityId getAlarmOriginator(TenantId tenantId, String entityName, EntityType entityType) {
        switch (entityType) {
            case DEVICE:
                return deviceService.findDeviceByTenantIdAndName(tenantId, entityName).getId();
            case ASSET:
                return assetService.findAssetByTenantIdAndName(tenantId, entityName).getId();
            case ENTITY_VIEW:
                return entityViewService.findEntityViewByTenantIdAndName(tenantId, entityName).getId();
            default:
                return null;
        }
    }

    public UplinkMsg processAlarmMsgToCloud(TenantId tenantId, CloudEvent cloudEvent, UpdateMsgType msgType, EdgeEventActionType edgeEventAction)
            throws ExecutionException, InterruptedException {
        AlarmId alarmId = new AlarmId(cloudEvent.getEntityId());
        UplinkMsg msg = null;
        switch (edgeEventAction) {
            case ADDED:
            case UPDATED:
            case ALARM_ACK:
            case ALARM_CLEAR:
                Alarm alarm = alarmService.findAlarmByIdAsync(cloudEvent.getTenantId(), alarmId).get();
                if (alarm != null) {
                    AlarmUpdateMsg alarmUpdateMsg = alarmMsgConstructor.constructAlarmUpdatedMsg(tenantId, msgType, alarm);
                    msg = UplinkMsg.newBuilder()
                            .setUplinkMsgId(EdgeUtils.nextPositiveInt())
                            .addAlarmUpdateMsg(alarmUpdateMsg).build();
                } else {
                    log.info("Skipping event as alarm was not found [{}]", cloudEvent);
                }
                break;
            case DELETED:
                Alarm deletedAlarm = JacksonUtil.OBJECT_MAPPER.convertValue(cloudEvent.getEntityBody(), Alarm.class);
                AlarmUpdateMsg alarmUpdateMsg =
                        alarmMsgConstructor.constructAlarmUpdatedMsg(tenantId, msgType, deletedAlarm);
                msg = UplinkMsg.newBuilder()
                        .setUplinkMsgId(EdgeUtils.nextPositiveInt())
                        .addAlarmUpdateMsg(alarmUpdateMsg)
                        .build();
                break;
        }
        return msg;
    }
}
