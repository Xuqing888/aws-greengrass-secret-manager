/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.secretmanager;

import com.aws.greengrass.authorization.AuthorizationHandler;
import com.aws.greengrass.authorization.Permission;
import com.aws.greengrass.authorization.exceptions.AuthorizationException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.ipc.ConnectionContext;
import com.aws.greengrass.ipc.common.FrameReader;
import com.aws.greengrass.ipc.services.common.ApplicationMessage;
import com.aws.greengrass.ipc.services.common.IPCUtil;
import com.aws.greengrass.ipc.services.secret.GetSecretValueRequest;
import com.aws.greengrass.ipc.services.secret.GetSecretValueResult;
import com.aws.greengrass.ipc.services.secret.SecretClientOpCodes;
import com.aws.greengrass.ipc.services.secret.SecretResponseStatus;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.secretmanager.exception.SecretManagerException;
import com.aws.greengrass.secretmanager.exception.v1.GetSecretException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class SecretManagerServiceTest {
    private static final ObjectMapper CBOR_MAPPER = new CBORMapper();
    private final String SECRET_ID = "secret";
    private final String SECRET_NAME = "secretName";
    private final String VERSION_ID = "id";
    private final String VERSION_LABEL = "label";
    private final String CURRENT_LABEL = "AWSCURRENT";
    private Kernel kernel;

    @TempDir
    Path rootDir;

    @Mock
    ConnectionContext mockContext;

    @Mock
    SecretManager mockSecretManager;

    @Mock
    AuthorizationHandler mockAuthorizationHandler;

    @Captor
    ArgumentCaptor<String> stringCaptor;

    @Captor
    ArgumentCaptor<Permission> permissionCaptor;

    void startKernelWithConfig(String configFile, State expectedState) throws InterruptedException {
        CountDownLatch secretManagerRunning = new CountDownLatch(1);
        kernel = new Kernel();
        kernel.parseArgs("-r", rootDir.toAbsolutePath().toString(), "-i", getClass().getResource(configFile).toString());
        kernel.getContext().addGlobalStateChangeListener((GreengrassService service, State was, State newState) -> {
            if (service.getName().equals(SecretManagerService.SECRET_MANAGER_SERVICE_NAME) && service.getState().equals(expectedState)) {
                secretManagerRunning.countDown();
            }
        });
        kernel.getContext().put(SecretManager.class, mockSecretManager);
        kernel.getContext().put(AuthorizationHandler.class, mockAuthorizationHandler);
        kernel.launch();
        assertTrue(secretManagerRunning.await(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void cleanup() {
        kernel.shutdown();
    }

    private FrameReader.Message getInputMessage() throws IOException {
        com.aws.greengrass.ipc.services.secret.GetSecretValueRequest request =
                com.aws.greengrass.ipc.services.secret.GetSecretValueRequest.builder().secretId(SECRET_ID).build();
        ApplicationMessage msg = ApplicationMessage.builder().version(1)
                .opCode(SecretClientOpCodes.GET_SECRET.ordinal())
                .payload(IPCUtil.encode(request))
                .build();
        return new FrameReader.Message(msg.toByteArray());
    }

    private FrameReader.Message getInvalidInputMessage() throws IOException {
        ApplicationMessage msg = ApplicationMessage.builder().version(1)
                .opCode(SecretClientOpCodes.GET_SECRET.ordinal())
                .payload(IPCUtil.encode("Junk"))
                .build();
        return new FrameReader.Message(msg.toByteArray());
    }

    @Test
    void GIVEN_secret_service_WHEN_started_with_bad_parameter_config_THEN_starts_successfully(ExtensionContext context) throws InterruptedException {
        ignoreExceptionOfType(context, com.fasterxml.jackson.core.JsonParseException.class);
        startKernelWithConfig("badConfig.yaml", State.RUNNING);
    }

    @Test
    void GIVEN_secret_service_WHEN_load_secret_fails_THEN_service_errors(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, SecretManagerException.class);

        doThrow(SecretManagerException.class).when(mockSecretManager).loadSecretsFromLocalStore();
        startKernelWithConfig("config.yaml", State.ERRORED);
    }

    @Test
    void GIVEN_secret_service_WHEN_started_without_secrets_THEN_starts_successfully(ExtensionContext context) throws InterruptedException {
        ignoreExceptionOfType(context, com.fasterxml.jackson.core.JsonParseException.class);
        startKernelWithConfig("emptyParameterConfig.yaml", State.RUNNING);
    }

    @Test
    void GIVEN_secret_service_WHEN_handler_called_THEN_correct_response_returned() throws Exception {
        startKernelWithConfig("config.yaml", State.RUNNING);
        final String secretValue = "secretValue";
        final String serviceName = "mockService";
        com.aws.greengrass.ipc.services.secret.GetSecretValueResult mockSecretResponse1 =
                com.aws.greengrass.ipc.services.secret.GetSecretValueResult.builder().secretString(secretValue)
                .secretId(SECRET_ID)
                .versionId(VERSION_ID)
                .versionStages(Arrays.asList(new String[]{CURRENT_LABEL, VERSION_LABEL}))
                .build();


        when(mockSecretManager.getSecret(any(GetSecretValueRequest.class))).thenReturn(mockSecretResponse1);
        when(mockContext.getServiceName()).thenReturn("mockService");
        when(mockAuthorizationHandler.isAuthorized(stringCaptor.capture(), permissionCaptor.capture())).thenReturn(true);

        FrameReader.Message inputMessage = getInputMessage();
        Future<FrameReader.Message> fut = kernel.getContext().get(SecretManagerService.class).handleMessage(inputMessage, mockContext);
        FrameReader.Message m = fut.get();
        com.aws.greengrass.ipc.services.secret.GetSecretValueResult returnedResult =
                IPCUtil.decode(ApplicationMessage.fromBytes(m.getPayload()).getPayload(), GetSecretValueResult.class);
        assertEquals(SECRET_ID, returnedResult.getSecretId());
        assertEquals(VERSION_ID, returnedResult.getVersionId());
        assertThat(returnedResult.getVersionStages(), hasItem(CURRENT_LABEL));
        assertThat(returnedResult.getVersionStages(), hasItem(VERSION_LABEL));
        assertEquals(SecretResponseStatus.Success, returnedResult.getStatus());
        assertEquals(SecretManagerService.SECRET_MANAGER_SERVICE_NAME, stringCaptor.getValue());
        assertEquals(SecretManagerService.SECRETS_AUTHORIZATION_OPCODE, permissionCaptor.getValue().getOperation());
        assertEquals(serviceName, permissionCaptor.getValue().getPrincipal());
        assertEquals(SECRET_ID, permissionCaptor.getValue().getResource());
        verify(mockAuthorizationHandler, atLeastOnce()).registerComponent(SecretManagerService.SECRET_MANAGER_SERVICE_NAME,
                new HashSet<>(Arrays.asList(SecretManagerService.SECRETS_AUTHORIZATION_OPCODE)));
    }

    @Test
    void GIVEN_secret_service_WHEN_v1_get_called_THEN_correct_response_returned() throws Exception {
        startKernelWithConfig("config.yaml", State.RUNNING);
        final String secretValue = "secretValue";
        final String serviceName = "mockService";
        final Date currentTime = Date.from(Instant.now());
        com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult expectedResponse =
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult.builder()
                        .secretString(secretValue)
                        .arn(SECRET_ID)
                        .name(SECRET_NAME)
                        .versionId(VERSION_ID)
                        .versionStages(Arrays.asList(new String[]{CURRENT_LABEL, VERSION_LABEL}))
                        .createdDate(currentTime)
                        .build();


        when(mockSecretManager.getSecret(any(com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.class)))
                .thenReturn(expectedResponse);
        when(mockAuthorizationHandler.isAuthorized(stringCaptor.capture(), permissionCaptor.capture())).thenReturn(true);

        com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest request =
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.builder()
                        .secretId(SECRET_ID)
                        .versionId(VERSION_ID)
                        .build();
        byte[] byteRequest = CBOR_MAPPER.writeValueAsBytes(request);
        byte[] response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);

        com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult actualResponse =
                CBOR_MAPPER.readValue(response,
                        com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult.class);

        assertEquals(SECRET_ID, actualResponse.getArn());
        assertEquals(SECRET_NAME, actualResponse.getName());
        assertEquals(VERSION_ID, actualResponse.getVersionId());
        assertEquals(secretValue, actualResponse.getSecretString());
        assertThat(actualResponse.getVersionStages(), hasItem(CURRENT_LABEL));
        assertThat(actualResponse.getVersionStages(), hasItem(VERSION_LABEL));
        assertEquals(currentTime, actualResponse.getCreatedDate());
        assertEquals(SecretManagerService.SECRET_MANAGER_SERVICE_NAME, stringCaptor.getValue());
        assertEquals(SecretManagerService.SECRETS_AUTHORIZATION_OPCODE, permissionCaptor.getValue().getOperation());
        assertEquals(serviceName, permissionCaptor.getValue().getPrincipal());
        assertEquals(SECRET_ID, permissionCaptor.getValue().getResource());
        verify(mockAuthorizationHandler, atLeastOnce()).registerComponent(SecretManagerService.SECRET_MANAGER_SERVICE_NAME,
                new HashSet<>(Arrays.asList(SecretManagerService.SECRETS_AUTHORIZATION_OPCODE)));
    }

    @Test
    void GIVEN_secret_service_WHEN_v1_get_called_and_errors_THEN_correct_response_returned() throws Exception {
        startKernelWithConfig("config.yaml", State.RUNNING);
        final String secretValue = "secretValue";
        final String serviceName = "mockService";
        final Date currentTime = Date.from(Instant.now());
        com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult expectedResponse =
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueResult.builder()
                        .secretString(secretValue)
                        .arn(SECRET_ID)
                        .name(SECRET_NAME)
                        .versionId(VERSION_ID)
                        .versionStages(Arrays.asList(new String[]{CURRENT_LABEL, VERSION_LABEL}))
                        .createdDate(currentTime)
                        .build();


        when(mockSecretManager.getSecret(any(com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.class)))
                .thenThrow(new GetSecretException(400, "getSecret Error"));
        when(mockAuthorizationHandler.isAuthorized(any(), any())).thenReturn(true);

        com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest request =
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.builder()
                        .secretId(SECRET_ID)
                        .versionId(VERSION_ID)
                        .build();
        byte[] byteRequest = CBOR_MAPPER.writeValueAsBytes(request);
        byte[] response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);

        com.aws.greengrass.secretmanager.model.v1.GetSecretValueError parsedResponse =
                CBOR_MAPPER.readValue(response,
                        com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);

        assertEquals(400, parsedResponse.getStatus());
        assertEquals("getSecret Error", parsedResponse.getMessage());

        // Now passing bogus request
        response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, "Hello".getBytes());
        parsedResponse = CBOR_MAPPER.readValue(response,
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);

        assertEquals(400, parsedResponse.getStatus());
        assertEquals("Unable to parse request", parsedResponse.getMessage());

        // now let the auth fail
        when(mockAuthorizationHandler.isAuthorized(any(), any())).
                thenThrow(new AuthorizationException("Auth error"));
        response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);
        parsedResponse = CBOR_MAPPER.readValue(response,
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);

        assertEquals(403, parsedResponse.getStatus());
        assertEquals("Auth error", parsedResponse.getMessage());

        //Case for generic errors
        reset(mockAuthorizationHandler);
        when(mockAuthorizationHandler.isAuthorized(any(), any())).thenReturn(true);
        when(mockSecretManager.getSecret(any(com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.class)))
                .thenThrow(new RuntimeException("Generic Error"));
        response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);
        parsedResponse = CBOR_MAPPER.readValue(response,
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);

        assertEquals(500, parsedResponse.getStatus());
        assertEquals("Generic Error", parsedResponse.getMessage());
    }

    @Test
    void GIVEN_secret_service_WHEN_v1_get_called_THEN_correct_errors_returned(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, AuthorizationException.class);
        startKernelWithConfig("config.yaml", State.RUNNING);
        final String serviceName = "mockService";

        when(mockAuthorizationHandler.isAuthorized(any(), any())).thenThrow(AuthorizationException.class);
        com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest request =
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.builder()
                        .secretId(SECRET_ID)
                        .versionId(VERSION_ID)
                        .build();
        byte[] byteRequest = CBOR_MAPPER.writeValueAsBytes(request);
        byte[] response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);
        com.aws.greengrass.secretmanager.model.v1.GetSecretValueError actualResponse =
                CBOR_MAPPER.readValue(response,
                        com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);

        assertEquals(403, actualResponse.getStatus());

        // simulate get secret throwing exception internally
        reset(mockAuthorizationHandler);
        when(mockAuthorizationHandler.isAuthorized(any(), any())).thenReturn(true);
        GetSecretException exception = new GetSecretException(400, "test");
        when(mockSecretManager.getSecret(any(com.aws.greengrass.secretmanager.model.v1.GetSecretValueRequest.class)))
                .thenThrow(exception);
        response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);
        actualResponse = CBOR_MAPPER.readValue(response,
                        com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);
        assertEquals(400, actualResponse.getStatus());
        assertThat(actualResponse.getMessage(), containsString("test"));

        // Now send in a bad request
        byteRequest = CBOR_MAPPER.writeValueAsBytes("bad request");
        response = kernel.getContext().get(SecretManagerService.class).getSecret(serviceName, byteRequest);
        actualResponse = CBOR_MAPPER.readValue(response,
                com.aws.greengrass.secretmanager.model.v1.GetSecretValueError.class);
        assertEquals(400, actualResponse.getStatus());
        assertThat(actualResponse.getMessage(), containsString("Unable to parse request"));
    }


    @Test
    void GIVEN_secret_service_WHEN_request_unauthorized_THEN_correct_response_returned(ExtensionContext context) throws Exception {
        startKernelWithConfig("config.yaml", State.RUNNING);
        ignoreExceptionOfType(context, AuthorizationException.class);
        when(mockContext.getServiceName()).thenReturn("mockService");
        when(mockAuthorizationHandler.isAuthorized(any(), any(Permission.class))).thenThrow(AuthorizationException.class);

        FrameReader.Message inputMessage = getInputMessage();
        Future<FrameReader.Message> fut = kernel.getContext().get(SecretManagerService.class).handleMessage(inputMessage, mockContext);
        FrameReader.Message m = fut.get();
        com.aws.greengrass.ipc.services.secret.GetSecretValueResult returnedResult =
                IPCUtil.decode(ApplicationMessage.fromBytes(m.getPayload()).getPayload(), GetSecretValueResult.class);
        assertNull(returnedResult.getSecretId());
        assertNull(returnedResult.getVersionId());
        assertNull(returnedResult.getVersionStages());
        assertEquals(SecretResponseStatus.Unauthorized, returnedResult.getStatus());
    }

    @Test
    void GIVEN_secret_service_WHEN_handler_call_errors_out_THEN_correct_response_returned(ExtensionContext context) throws Exception {
        startKernelWithConfig("config.yaml", State.RUNNING);
        ignoreExceptionOfType(context, com.fasterxml.jackson.databind.exc.MismatchedInputException.class);
        FrameReader.Message inputMessage = getInvalidInputMessage();
        Future<FrameReader.Message> fut = kernel.getContext().get(SecretManagerService.class).handleMessage(inputMessage, mockContext);
        FrameReader.Message m = fut.get();
        com.aws.greengrass.ipc.services.secret.GetSecretValueResult returnedResult =
                IPCUtil.decode(ApplicationMessage.fromBytes(m.getPayload()).getPayload(), GetSecretValueResult.class);
        assertEquals(SecretResponseStatus.InternalError, returnedResult.getStatus());
    }

}
