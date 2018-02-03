/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker;

import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.ReaderConfiguration;
import org.apache.pulsar.functions.proto.Function;
import org.apache.pulsar.functions.proto.Function.FunctionMetaData;
import org.apache.pulsar.functions.proto.Request;
import org.apache.pulsar.functions.worker.request.RequestResult;
import org.apache.pulsar.functions.worker.request.ServiceRequestInfo;
import org.apache.pulsar.functions.worker.request.ServiceRequestManager;
import org.apache.pulsar.functions.worker.request.ServiceRequestUtils;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class maintains a global state of all function metadata and is responsible for serving function metadata
 */
@Slf4j
public class FunctionMetaDataManager implements AutoCloseable {
    // Represents the global state
    // tenant -> namespace -> (function name, FunctionRuntimeInfo)
    @VisibleForTesting
    final Map<String, Map<String, Map<String, FunctionMetaData>>> functionMetaDataMap = new ConcurrentHashMap<>();

    // A map in which the key is the service request id and value is the service request
    private final Map<String, ServiceRequestInfo> pendingServiceRequests = new ConcurrentHashMap<>();

    private final ServiceRequestManager serviceRequestManager;
    private final FunctionMetaDataSnapshotManager functionMetaDataSnapshotManager;
    private final SchedulerManager schedulerManager;
    private final WorkerConfig workerConfig;
    private final PulsarClient pulsarClient;
    final String initializeMarkerRequestId = UUID.randomUUID().toString();

    // The message id of the last messaged processed by function runtime manager
    @VisibleForTesting
    @Getter
    MessageId lastProcessedMessageId = MessageId.earliest;

    private FunctionMetaDataTopicTailer functionMetaDataTopicTailer;

    private CompletableFuture<Void> initializePhase = new CompletableFuture<>();

    public FunctionMetaDataManager(WorkerConfig workerConfig,
                                   SchedulerManager schedulerManager,
                                   PulsarClient pulsarClient) throws PulsarClientException {
        this.workerConfig = workerConfig;
        this.pulsarClient = pulsarClient;
        this.serviceRequestManager = getServiceRequestManager(
                this.pulsarClient, this.workerConfig.getFunctionMetadataTopic());
        this.functionMetaDataSnapshotManager = new FunctionMetaDataSnapshotManager(
                this.workerConfig, this, this.pulsarClient);
        this.schedulerManager = schedulerManager;
    }

    public void initialize() {
        log.info("/** Initializing Function Metadata Manager **/");
        log.info("Restoring metadata store from snapshot...");
        MessageId lastMessageId = this.restore();
        log.info("Function metadata store restored from snapshot with message id: {}", lastMessageId);
        try {
            Reader reader = this.pulsarClient.createReader(
                    this.workerConfig.getFunctionMetadataTopic(),
                    lastMessageId,
                    new ReaderConfiguration());
            this.functionMetaDataTopicTailer = new FunctionMetaDataTopicTailer(this, reader);
            this.functionMetaDataTopicTailer.start();
            this.sendIntializationMarker();
            this.initializePhase.get();

        } catch (Exception e) {
            log.error("Failed to initialize meta data store: ", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    ServiceRequestManager getServiceRequestManager(PulsarClient pulsarClient, String functionMetadataTopic) throws PulsarClientException {
        return new ServiceRequestManager(pulsarClient.createProducer(functionMetadataTopic));
    }

    void sendIntializationMarker() {
        log.info("Sending Initialize message...");
        this.serviceRequestManager.submitRequest(
                ServiceRequestUtils.getIntializationRequest(
                        this.initializeMarkerRequestId,
                        this.workerConfig.getWorkerId()));
    }

    public FunctionMetaData getFunctionMetaData(String tenant, String namespace, String functionName) {
        return this.functionMetaDataMap.get(tenant).get(namespace).get(functionName);
    }

    public FunctionMetaData getFunctionMetaData(Function.FunctionConfig functionConfig) {
        return getFunctionMetaData(functionConfig.getTenant(), functionConfig.getNamespace(), functionConfig.getName());
    }

    public FunctionMetaData getFunctionMetaData(FunctionMetaData functionMetaData) {
        return getFunctionMetaData(functionMetaData.getFunctionConfig());
    }

    public List<FunctionMetaData> getAllFunctionMetaData() {
        List<FunctionMetaData> ret = new LinkedList<>();
        for (Map<String, Map<String, FunctionMetaData>> i : this.functionMetaDataMap.values()) {
            for (Map<String, FunctionMetaData> j : i.values()) {
                ret.addAll(j.values());
            }
        }
        return ret;
    }

    public Collection<String> listFunctions(String tenant, String namespace) {
        List<String> ret = new LinkedList<>();

        if (!this.functionMetaDataMap.containsKey(tenant)) {
            return ret;
        }

        if (!this.functionMetaDataMap.get(tenant).containsKey(namespace)) {
            return ret;
        }
        for (FunctionMetaData functionMetaData : this.functionMetaDataMap.get(tenant).get(namespace).values()) {
            ret.add(functionMetaData.getFunctionConfig().getName());
        }
        return ret;
    }

    public boolean containsFunctionMetaData(FunctionMetaData functionMetaData) {
        return containsFunctionMetaData(functionMetaData.getFunctionConfig());
    }

    boolean containsFunctionMetaData(Function.FunctionConfig functionConfig) {
        return containsFunctionMetaData(
                functionConfig.getTenant(), functionConfig.getNamespace(), functionConfig.getName());
    }

    public boolean containsFunctionMetaData(String tenant, String namespace, String functionName) {
        if (this.functionMetaDataMap.containsKey(tenant)) {
            if (this.functionMetaDataMap.get(tenant).containsKey(namespace)) {
                if (this.functionMetaDataMap.get(tenant).get(namespace).containsKey(functionName)) {
                    return true;
                }
            }
        }
        return false;
    }

    public CompletableFuture<RequestResult> updateFunction(FunctionMetaData functionMetaData) {

        long version = 0;

        String tenant = functionMetaData.getFunctionConfig().getTenant();
        if (!this.functionMetaDataMap.containsKey(tenant)) {
            this.functionMetaDataMap.put(tenant, new ConcurrentHashMap<>());
        }

        Map<String, Map<String, FunctionMetaData>> namespaces = this.functionMetaDataMap.get(tenant);
        String namespace = functionMetaData.getFunctionConfig().getNamespace();
        if (!namespaces.containsKey(namespace)) {
            namespaces.put(namespace, new ConcurrentHashMap<>());
        }

        Map<String, FunctionMetaData> functionMetaDatas = namespaces.get(namespace);
        String functionName = functionMetaData.getFunctionConfig().getName();
        if (functionMetaDatas.containsKey(functionName)) {
            version = functionMetaDatas.get(functionName).getVersion() + 1;
        }

        FunctionMetaData newFunctionMetaData = functionMetaData.toBuilder().setVersion(version).build();

        Request.ServiceRequest updateRequest = ServiceRequestUtils.getUpdateRequest(
                this.workerConfig.getWorkerId(), newFunctionMetaData);

        return submit(updateRequest);
    }

    public CompletableFuture<RequestResult> deregisterFunction(String tenant, String namespace, String functionName) {
        FunctionMetaData functionMetaData = this.functionMetaDataMap.get(tenant).get(namespace).get(functionName);

        FunctionMetaData newFunctionMetaData = functionMetaData.toBuilder()
                .setVersion(functionMetaData.getVersion() + 1)
                .build();

        Request.ServiceRequest deregisterRequest = ServiceRequestUtils.getDeregisterRequest(
                this.workerConfig.getWorkerId(), newFunctionMetaData);

        return submit(deregisterRequest);
    }

    void processRequest(MessageId messageId, Request.ServiceRequest serviceRequest) {

        // make sure that snapshotting and processing requests don't happen simultaneously
        synchronized (this) {
            switch (serviceRequest.getServiceRequestType()) {
                case INITIALIZE:
                    this.processInitializeMarker(serviceRequest);
                    break;
                case UPDATE:
                    this.processUpdate(serviceRequest);
                    break;
                case DELETE:
                    this.proccessDeregister(serviceRequest);
                    break;
                default:
                    log.warn("Received request with unrecognized type: {}", serviceRequest);
            }
            this.lastProcessedMessageId = messageId;
        }
    }

    void proccessDeregister(Request.ServiceRequest deregisterRequest) {

        FunctionMetaData deregisterRequestFs = deregisterRequest.getFunctionMetaData();
        String functionName = deregisterRequestFs.getFunctionConfig().getName();
        String tenant = deregisterRequestFs.getFunctionConfig().getTenant();
        String namespace = deregisterRequestFs.getFunctionConfig().getNamespace();

        boolean needsScheduling = false;

        log.debug("Process deregister request: {}", deregisterRequest);

        // Check if we still have this function. Maybe already deleted by someone else
        if (this.containsFunctionMetaData(deregisterRequestFs)) {
            // check if request is outdated
            if (!isRequestOutdated(deregisterRequest)) {
                this.functionMetaDataMap.get(tenant).get(namespace).remove(functionName);
                completeRequest(deregisterRequest, true);
                needsScheduling = true;
            } else {
                completeRequest(deregisterRequest, false,
                        "Request ignored because it is out of date. Please try again.");
            }
        } else {
            // already deleted so  just complete request
            completeRequest(deregisterRequest, true);
        }

        if (needsScheduling) {
            this.schedulerManager.schedule();
        }
    }

    void processUpdate(Request.ServiceRequest updateRequest) {

        log.debug("Process update request: {}", updateRequest);

        FunctionMetaData updateRequestFs = updateRequest.getFunctionMetaData();

        boolean needsScheduling = false;

        // Worker doesn't know about the function so far
        if (!this.containsFunctionMetaData(updateRequestFs)) {
            // Since this is the first time worker has seen function, just put it into internal function metadata store
            setFunctionMetaData(updateRequestFs);
            needsScheduling = true;
            completeRequest(updateRequest, true);
        } else {
            // The request is an update to an existing function since this worker already has a record of this function
            // in its function metadata store
            // Check if request is outdated
            if (!isRequestOutdated(updateRequest)) {
                // update the function metadata
                setFunctionMetaData(updateRequestFs);
                needsScheduling = true;
                completeRequest(updateRequest, true);
            } else {
                completeRequest(updateRequest, false,
                        "Request ignored because it is out of date. Please try again.");
            }
        }

        if (needsScheduling) {
            this.schedulerManager.schedule();
        }
    }

    void processInitializeMarker(Request.ServiceRequest serviceRequest) {
        if (isMyInitializeMarkerRequest(serviceRequest)) {
            this.completeInitializePhase();
            log.info("Initializing Metadata state done!");
        }
    }

    MessageId restore() {
       return this.functionMetaDataSnapshotManager.restore();
    }

    void snapshot() {
        this.functionMetaDataSnapshotManager.snapshot();
    }

    /**
     * Complete requests that this worker has pending
     * @param serviceRequest
     * @param isSuccess
     * @param message
     */
    private void completeRequest(Request.ServiceRequest serviceRequest, boolean isSuccess, String message) {
        ServiceRequestInfo pendingServiceRequestInfo
                = this.pendingServiceRequests.getOrDefault(
                serviceRequest.getRequestId(), null);
        if (pendingServiceRequestInfo != null) {
            RequestResult requestResult = new RequestResult();
            requestResult.setSuccess(isSuccess);
            requestResult.setMessage(message);
            pendingServiceRequestInfo.getRequestResultCompletableFuture().complete(requestResult);
        }
    }

    private void completeRequest(Request.ServiceRequest serviceRequest, boolean isSuccess) {
        completeRequest(serviceRequest, isSuccess, null);
    }


    private boolean isRequestOutdated(Request.ServiceRequest serviceRequest) {
        FunctionMetaData requestFunctionMetaData = serviceRequest.getFunctionMetaData();
        Function.FunctionConfig functionConfig = requestFunctionMetaData.getFunctionConfig();
        FunctionMetaData currentFunctionMetaData = this.functionMetaDataMap.get(functionConfig.getTenant())
                .get(functionConfig.getNamespace()).get(functionConfig.getName());
        return currentFunctionMetaData.getVersion() >= requestFunctionMetaData.getVersion();
    }

    void setFunctionMetaData(FunctionMetaData functionMetaData) {
        Function.FunctionConfig functionConfig = functionMetaData.getFunctionConfig();
        if (!this.functionMetaDataMap.containsKey(functionConfig.getTenant())) {
            this.functionMetaDataMap.put(functionConfig.getTenant(), new ConcurrentHashMap<>());
        }

        if (!this.functionMetaDataMap.get(functionConfig.getTenant()).containsKey(functionConfig.getNamespace())) {
            this.functionMetaDataMap.get(functionConfig.getTenant())
                    .put(functionConfig.getNamespace(), new ConcurrentHashMap<>());
        }
        this.functionMetaDataMap.get(functionConfig.getTenant())
                .get(functionConfig.getNamespace()).put(functionConfig.getName(), functionMetaData);
    }

    CompletableFuture<RequestResult> submit(Request.ServiceRequest serviceRequest) {
        ServiceRequestInfo serviceRequestInfo = ServiceRequestInfo.of(serviceRequest);
        CompletableFuture<MessageId> messageIdCompletableFuture = this.serviceRequestManager.submitRequest(serviceRequest);

        serviceRequestInfo.setCompletableFutureRequestMessageId(messageIdCompletableFuture);
        CompletableFuture<RequestResult> requestResultCompletableFuture = new CompletableFuture<>();

        serviceRequestInfo.setRequestResultCompletableFuture(requestResultCompletableFuture);

        this.pendingServiceRequests.put(serviceRequestInfo.getServiceRequest().getRequestId(), serviceRequestInfo);

        return requestResultCompletableFuture;
    }

    private boolean isMyInitializeMarkerRequest(Request.ServiceRequest serviceRequest) {
        return isSendByMe(serviceRequest) && this.initializeMarkerRequestId.equals(serviceRequest.getRequestId());
    }

    private boolean isSendByMe(Request.ServiceRequest serviceRequest) {
        return this.workerConfig.getWorkerId().equals(serviceRequest.getWorkerId());
    }

    void completeInitializePhase() {
        this.initializePhase.complete(null);
    }

    @Override
    public void close() throws Exception {
        if (this.functionMetaDataTopicTailer != null) {
            this.functionMetaDataTopicTailer.close();
        }
        if (this.serviceRequestManager != null) {
            this.serviceRequestManager.close();
        }
        if (this.functionMetaDataSnapshotManager != null) {
            this.functionMetaDataSnapshotManager.close();
        }
    }
}
