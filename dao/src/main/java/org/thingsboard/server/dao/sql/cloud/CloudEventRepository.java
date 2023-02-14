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
package org.thingsboard.server.dao.sql.cloud;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.thingsboard.server.common.data.cloud.CloudEventType;
import org.thingsboard.server.dao.model.sql.CloudEventEntity;

import java.util.UUID;

public interface CloudEventRepository extends JpaRepository<CloudEventEntity, UUID>, JpaSpecificationExecutor<CloudEventEntity> {

    @Query("SELECT e FROM CloudEventEntity e WHERE " +
            "e.tenantId = :tenantId " +
            "AND (:startTime IS NULL OR e.createdTime > :startTime) " +
            "AND (:endTime IS NULL OR e.createdTime <= :endTime) "
    )
    Page<CloudEventEntity> findEventsByTenantId(@Param("tenantId") UUID tenantId,
                                                @Param("startTime") Long startTime,
                                                @Param("endTime") Long endTime,
                                                Pageable pageable);

    @Query("SELECT e FROM CloudEventEntity e WHERE " +
            "e.tenantId = :tenantId " +
            "AND e.entityId  = :entityId " +
            "AND e.cloudEventType = :cloudEventType " +
            "AND e.cloudEventAction = :cloudEventAction " +
            "AND (:startTime IS NULL OR e.createdTime > :startTime) " +
            "AND (:endTime IS NULL OR e.createdTime <= :endTime) "
    )
    Page<CloudEventEntity> findEventsByTenantIdAndEntityIdAndCloudEventActionAndCloudEventType(
            @Param("tenantId") UUID tenantId,
            @Param("entityId") UUID entityId,
            @Param("cloudEventType") CloudEventType cloudEventType,
            @Param("cloudEventAction") String cloudEventAction,
            @Param("startTime") Long startTime,
            @Param("endTime") Long endTime,
            Pageable pageable);
}
