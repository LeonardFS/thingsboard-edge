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
import org.apache.commons.lang3.RandomStringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.cloud.CloudEventType;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.id.UserId;
import org.thingsboard.server.common.data.security.Authority;
import org.thingsboard.server.common.data.security.UserCredentials;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.user.UserService;
import org.thingsboard.server.dao.user.UserServiceImpl;
import org.thingsboard.server.gen.edge.v1.UpdateMsgType;
import org.thingsboard.server.gen.edge.v1.UserCredentialsUpdateMsg;
import org.thingsboard.server.gen.edge.v1.UserUpdateMsg;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@Component
@Slf4j
public class UserCloudProcessor extends BaseCloudProcessor {

    private final Lock userCreationLock = new ReentrantLock();

    @Autowired
    private UserService userService;

    public ListenableFuture<Void> processUserMsgFromCloud(TenantId tenantId,
                                                          UserUpdateMsg userUpdateMsg,
                                                          Long queueStartTs) {
        UserId userId = new UserId(new UUID(userUpdateMsg.getIdMSB(), userUpdateMsg.getIdLSB()));
        switch (userUpdateMsg.getMsgType()) {
            case ENTITY_CREATED_RPC_MESSAGE:
            case ENTITY_UPDATED_RPC_MESSAGE:
                userCreationLock.lock();
                try {
                    boolean created = false;
                    User user = userService.findUserById(tenantId, userId);
                    if (user == null) {
                        user = new User();
                        user.setTenantId(tenantId);
                        user.setId(userId);
                        user.setCreatedTime(Uuids.unixTimestamp(userId.getId()));
                        created = true;
                    }
                    user.setEmail(userUpdateMsg.getEmail());
                    user.setAuthority(Authority.valueOf(userUpdateMsg.getAuthority()));
                    user.setFirstName(userUpdateMsg.hasFirstName() ? userUpdateMsg.getFirstName() : null);
                    user.setLastName(userUpdateMsg.hasLastName() ? userUpdateMsg.getLastName() : null);
                    user.setAdditionalInfo(userUpdateMsg.hasAdditionalInfo() ? JacksonUtil.toJsonNode(userUpdateMsg.getAdditionalInfo()) : null);
                    safeSetCustomerId(userUpdateMsg, user);
                    User savedUser = userService.saveUser(user, false);
                    if (created) {
                        UserCredentials userCredentials = new UserCredentials();
                        userCredentials.setEnabled(false);
                        userCredentials.setActivateToken(RandomStringUtils.randomAlphanumeric(UserServiceImpl.DEFAULT_TOKEN_LENGTH));
                        userCredentials.setUserId(new UserId(savedUser.getUuidId()));
                        userService.saveUserCredentialsAndPasswordHistory(user.getTenantId(), userCredentials);
                    }
                } finally {
                    userCreationLock.unlock();
                }
                break;
            case ENTITY_DELETED_RPC_MESSAGE:
                User userToDelete = userService.findUserById(tenantId, userId);
                if (userToDelete != null) {
                    userService.deleteUser(tenantId, userToDelete.getId());
                }
                break;
            case UNRECOGNIZED:
                return handleUnsupportedMsgType(userUpdateMsg.getMsgType());
        }

        ListenableFuture<Boolean> requestFuture = requestForAdditionalData(tenantId, userUpdateMsg.getMsgType(), userId, queueStartTs);

        return Futures.transformAsync(requestFuture, ignored -> {
            if (UpdateMsgType.ENTITY_CREATED_RPC_MESSAGE.equals(userUpdateMsg.getMsgType()) ||
                    UpdateMsgType.ENTITY_UPDATED_RPC_MESSAGE.equals(userUpdateMsg.getMsgType())) {
                return saveCloudEvent(tenantId, CloudEventType.USER, EdgeEventActionType.CREDENTIALS_REQUEST, userId, null);
            } else {
                return Futures.immediateFuture(null);
            }
        }, dbCallbackExecutor);
    }

    private void safeSetCustomerId(UserUpdateMsg userUpdateMsg, User user) {
        CustomerId customerId = safeGetCustomerId(userUpdateMsg.getCustomerIdMSB(),
                userUpdateMsg.getCustomerIdLSB());
        if (customerId == null) {
            customerId = new CustomerId(ModelConstants.NULL_UUID);
        }
        user.setCustomerId(customerId);
    }

    public ListenableFuture<Void> processUserCredentialsMsgFromCloud(TenantId tenantId, UserCredentialsUpdateMsg userCredentialsUpdateMsg) {
        UserId userId = new UserId(new UUID(userCredentialsUpdateMsg.getUserIdMSB(), userCredentialsUpdateMsg.getUserIdLSB()));
        ListenableFuture<User> userFuture = userService.findUserByIdAsync(tenantId, userId);
        return Futures.transform(userFuture, user -> {
            if (user != null) {
                UserCredentials userCredentials = userService.findUserCredentialsByUserId(tenantId, user.getId());
                userCredentials.setEnabled(userCredentialsUpdateMsg.getEnabled());
                userCredentials.setPassword(userCredentialsUpdateMsg.getPassword());
                userCredentials.setActivateToken(null);
                userCredentials.setResetToken(null);
                userService.saveUserCredentials(tenantId, userCredentials, false);
            }
            return null;
        }, dbCallbackExecutor);
    }
}
