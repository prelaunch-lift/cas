package com.yubico.webauthn;

import org.apereo.cas.configuration.CasConfigurationProperties;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yubico.internal.util.JacksonCodecs;
import com.yubico.util.Either;
import com.yubico.webauthn.data.AssertionRequestWrapper;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.RegistrationRequest;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This is {@link WebAuthnRestResource}.
 *
 * @author Misagh Moayyed
 * @since 6.2.0
 */
@RestController("webAuthnRestResource")
@Slf4j
@RequiredArgsConstructor
@RequestMapping(WebAuthnRestResource.BASE_ENDPOINT_WEBAUTHN)
public class WebAuthnRestResource {
    public static final String BASE_ENDPOINT_WEBAUTHN = "/webauthn";

    private static final ObjectMapper MAPPER = JacksonCodecs.json().findAndRegisterModules();

    private final WebAuthnServer server;

    private final CasConfigurationProperties casProperties;

    private final JsonNodeFactory jsonFactory = JsonNodeFactory.instance;

    @GetMapping
    public ResponseEntity<Object> index() {
        return new ResponseEntity<>(writeJson(new IndexResponse()), HttpStatus.OK);
    }

    @PostMapping("/register")
    public ResponseEntity<Object> startRegistration(
        @NonNull @RequestParam("username") final String username,
        @NonNull @RequestParam("displayName") final String displayName,
        @RequestParam(value = "credentialNickname", required = false, defaultValue = "true") final String credentialNickname,
        @RequestParam(value = "requireResidentKey", required = false) final boolean requireResidentKey,
        @RequestParam(value = "sessionToken", required = false, defaultValue = StringUtils.EMPTY) final String sessionTokenBase64) throws Exception {
        val result = server.startRegistration(
            username,
            Optional.of(displayName),
            Optional.ofNullable(credentialNickname),
            requireResidentKey,
            Optional.ofNullable(sessionTokenBase64).map(base64 -> {
                try {
                    return ByteArray.fromBase64Url(base64);
                } catch (final Exception e) {
                    throw new RuntimeException(e);
                }
            })
        );

        if (result.isRight()) {
            return startResponse("startRegistration", new StartRegistrationResponse(result.right().get()));
        }
        return messagesJson(
            ResponseEntity.badRequest(),
            result.left().get()
        );

    }

    @PostMapping("/register/finish")
    public ResponseEntity finishRegistration(@RequestBody final String responseJson) throws Exception {
        val result = server.finishRegistration(responseJson);
        return finishResponse(
            result,
            "Attestation verification failed",
            responseJson
        );
    }

    @PostMapping("/authenticate")
    public ResponseEntity startAuthentication(@RequestParam("username") final String username) throws MalformedURLException {
        val request = server.startAuthentication(Optional.ofNullable(username));
        if (request.isRight()) {
            return startResponse("startAuthentication", new StartAuthenticationResponse(request.right().get()));
        }
        return messagesJson(ResponseEntity.badRequest(), request.left().get());

    }

    @PostMapping("/authenticate/finish")
    public ResponseEntity finishAuthentication(@NonNull final String responseJson) {
        val result = server.finishAuthentication(responseJson);

        return finishResponse(
            result,
            "Authentication verification failed",
            responseJson
        );
    }

    @PostMapping("/action/deregister")
    public ResponseEntity deregisterCredential(
        @NonNull @RequestParam("sessionToken") final String sessionTokenBase64,
        @NonNull @RequestParam("credentialId") final String credentialIdBase64) {
        try {
            val credentialId = ByteArray.fromBase64Url(credentialIdBase64);

            val result = server.deregisterCredential(
                ByteArray.fromBase64Url(sessionTokenBase64),
                credentialId
            );

            if (result.isRight()) {
                return finishResponse(
                    result,
                    "Failed to deregister credential.",
                    StringUtils.EMPTY
                );
            }
            return messagesJson(
                ResponseEntity.badRequest(),
                result.left().get()
            );

        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
            return messagesJson(
                ResponseEntity.badRequest(),
                "Credential ID is not valid Base64Url data: " + credentialIdBase64
            );
        }


    }

    @DeleteMapping("/delete-account")
    public ResponseEntity deleteAccount(@NonNull @RequestParam("username") final String username) {
        val result = server.deleteAccount(username, () ->
            ((ObjectNode) jsonFactory.objectNode()
                .set("success", jsonFactory.booleanNode(true)))
                .set("deletedAccount", jsonFactory.textNode(username))
        );

        if (result.isRight()) {
            return ResponseEntity.ok(writeJson(result.right().get().toString()));
        }
        return messagesJson(
            ResponseEntity.badRequest(),
            result.left().get()
        );
    }

    private static ResponseEntity<Object> jsonFail() {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body("{\"messages\":[\"Failed to encode response as JSON\"]}");
    }

    private static ResponseEntity<Object> startResponse(final String operationName, final Object request) {
        try {
            LOGGER.trace("Operation: {}", operationName);
            return ResponseEntity.ok(writeJson(request));
        } catch (final Exception e) {
            LOGGER.error(e.getMessage(), e);
            return jsonFail();
        }
    }

    @SneakyThrows
    private static String writeJson(final Object o) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o);
    }

    private ResponseEntity<Object> finishResponse(final Either<List<String>, ?> result, final String jsonFailMessage,
                                                  final String responseJson) {
        if (result.isRight()) {
            try {
                LOGGER.trace("Response: [{}]", responseJson);
                return ResponseEntity.ok(writeJson(result.right().get()));
            } catch (final Exception e) {
                LOGGER.error(e.getMessage(), e);
                return messagesJson(
                    ResponseEntity.ok(),
                    jsonFailMessage
                );
            }
        }
        return messagesJson(
            ResponseEntity.badRequest(),
            result.left().get()
        );
    }

    private ResponseEntity<Object> messagesJson(final ResponseEntity.BodyBuilder response, final String message) {
        return messagesJson(response, Collections.singletonList(message));
    }

    private ResponseEntity<Object> messagesJson(final ResponseEntity.BodyBuilder response, final List<String> messages) {
        try {
            return response.body(
                writeJson(jsonFactory.objectNode()
                    .set("messages", jsonFactory.arrayNode()
                        .addAll(messages.stream().map(jsonFactory::textNode).collect(Collectors.toList()))
                    )
                ));
        } catch (final Exception e) {
            LOGGER.error("Failed to encode messages as JSON: {}", messages, e);
            return jsonFail();
        }
    }

    @RequiredArgsConstructor
    @Getter
    private class StartAuthenticationResponse {
        private final boolean success = true;

        private final AssertionRequestWrapper request;

        private final StartAuthenticationActions actions = new StartAuthenticationActions();
    }

    @RequiredArgsConstructor
    @Getter
    private class StartRegistrationResponse {
        private final boolean success = true;

        private final RegistrationRequest request;

        private final StartRegistrationActions actions = new StartRegistrationActions();
    }

    @Getter
    private class StartRegistrationActions {
        private final URL finish = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/register/finish");
    }

    @Getter
    private class StartAuthenticationActions {
        private final URL finish = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/authenticate/finish");
    }

    @NoArgsConstructor
    @Getter
    private class IndexResponse {
        private final Index actions = new Index();
    }

    @Getter
    private class Index {
        private final URL authenticate;

        private final URL deleteAccount;

        private final URL deregister;

        private final URL register;

        Index() {
            authenticate = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/authenticate");
            deleteAccount = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/delete-account");
            deregister = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/action/deregister");
            register = casProperties.getServer().buildContextRelativeUrl(BASE_ENDPOINT_WEBAUTHN + "/register");
        }
    }
}
