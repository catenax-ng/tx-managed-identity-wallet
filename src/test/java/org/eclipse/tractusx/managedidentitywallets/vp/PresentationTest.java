/*
 * *******************************************************************************
 *  Copyright (c) 2021,2023 Contributors to the Eclipse Foundation
 *
 *  See the NOTICE file(s) distributed with this work for additional
 *  information regarding copyright ownership.
 *
 *  This program and the accompanying materials are made available under the
 *  terms of the Apache License, Version 2.0 which is available at
 *  https://www.apache.org/licenses/LICENSE-2.0.
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 *
 *  SPDX-License-Identifier: Apache-2.0
 * ******************************************************************************
 */

package org.eclipse.tractusx.managedidentitywallets.vp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.eclipse.tractusx.managedidentitywallets.ManagedIdentityWalletsApplication;
import org.eclipse.tractusx.managedidentitywallets.config.MIWSettings;
import org.eclipse.tractusx.managedidentitywallets.config.TestContextInitializer;
import org.eclipse.tractusx.managedidentitywallets.constant.MIWVerifiableCredentialType;
import org.eclipse.tractusx.managedidentitywallets.constant.RestURI;
import org.eclipse.tractusx.managedidentitywallets.constant.StringPool;
import org.eclipse.tractusx.managedidentitywallets.controller.PresentationController;
import org.eclipse.tractusx.managedidentitywallets.dao.entity.HoldersCredential;
import org.eclipse.tractusx.managedidentitywallets.dao.entity.Wallet;
import org.eclipse.tractusx.managedidentitywallets.dao.repository.HoldersCredentialRepository;
import org.eclipse.tractusx.managedidentitywallets.utils.AuthenticationUtils;
import org.eclipse.tractusx.managedidentitywallets.utils.TestUtils;
import org.eclipse.tractusx.ssi.lib.did.resolver.DidDocumentResolverRegistry;
import org.eclipse.tractusx.ssi.lib.did.web.DidWebFactory;
import org.eclipse.tractusx.ssi.lib.exception.DidDocumentResolverNotRegisteredException;
import org.eclipse.tractusx.ssi.lib.exception.JwtException;
import org.eclipse.tractusx.ssi.lib.jwt.SignedJwtVerifier;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.test.context.ContextConfiguration;

import java.text.ParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, classes = {ManagedIdentityWalletsApplication.class})
@ContextConfiguration(initializers = {TestContextInitializer.class})
class PresentationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private HoldersCredentialRepository holdersCredentialRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PresentationController presentationController;

    @Autowired
    private MIWSettings miwSettings;


    @Test
    void validateVPAssJsonLd400() throws JsonProcessingException {
        //create VP
        String bpn = UUID.randomUUID().toString();
        String audience = "companyA";
        ResponseEntity<Map> vpResponse = createBpnVCAsJwt(bpn, audience);
        Map body = vpResponse.getBody();

        //validate VP
        HttpHeaders headers = AuthenticationUtils.getValidUserHttpHeaders(bpn);
        HttpEntity<Map> entity = new HttpEntity<>(body, headers);

        ResponseEntity<Map> validationResponse = restTemplate.exchange(RestURI.API_PRESENTATIONS_VALIDATION, HttpMethod.POST, entity, Map.class);
        Assertions.assertEquals(validationResponse.getStatusCode().value(), HttpStatus.BAD_REQUEST.value());
    }


    @Test
    void validateVPAsJwt() throws JsonProcessingException {
        String bpn = UUID.randomUUID().toString();
        String audience = "companyA";
        ResponseEntity<Map> vpResponse = createBpnVCAsJwt(bpn, audience);
        Map body = vpResponse.getBody();

        ResponseEntity<Map<String, Object>> mapResponseEntity = presentationController.validatePresentation(body, null, true, false);

        Map map = mapResponseEntity.getBody();
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALID).toString()));
        Assertions.assertFalse(map.containsKey(StringPool.VALIDATE_AUDIENCE));
        Assertions.assertFalse(map.containsKey(StringPool.VALIDATE_EXPIRY_DATE));
        Assertions.assertTrue(map.containsKey(StringPool.VALIDATE_JWT_EXPIRY_DATE));
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALIDATE_JWT_EXPIRY_DATE).toString()));
    }

    @Test
    void validateVPAsJwtWithInvalidSignatureAndInValidAudienceAndExpiryDateValidation() throws JsonProcessingException, DidDocumentResolverNotRegisteredException, JwtException, InterruptedException {
        //create VP
        String bpn = UUID.randomUUID().toString();
        String audience = "companyA";
        ResponseEntity<Map> vpResponse = createBpnVCAsJwt(bpn, audience);
        Map body = vpResponse.getBody();

        try (MockedConstruction<SignedJwtVerifier> mocked = Mockito.mockConstruction(SignedJwtVerifier.class)) {

            DidDocumentResolverRegistry didDocumentResolverRegistry = Mockito.mock(DidDocumentResolverRegistry.class);
            SignedJwtVerifier signedJwtVerifier = new SignedJwtVerifier(didDocumentResolverRegistry);

            Mockito.doThrow(new JwtException("invalid")).when(signedJwtVerifier).verify(Mockito.any(SignedJWT.class));

            Thread.sleep(62000L); // need to remove this??? Can not mock 2 object creation using new

            ResponseEntity<Map<String, Object>> mapResponseEntity = presentationController.validatePresentation(body, "no valid", true, true);

            Map map = mapResponseEntity.getBody();

            Assertions.assertFalse(Boolean.parseBoolean(map.get(StringPool.VALID).toString()));
            Assertions.assertFalse(Boolean.parseBoolean(map.get(StringPool.VALIDATE_AUDIENCE).toString()));
            Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALIDATE_EXPIRY_DATE).toString()));
            Assertions.assertTrue(map.containsKey(StringPool.VALIDATE_JWT_EXPIRY_DATE));
            Assertions.assertFalse(Boolean.parseBoolean(map.get(StringPool.VALIDATE_JWT_EXPIRY_DATE).toString()));
        }
    }

    @Test
    void validateVPAsJwtWithValidAudienceAndDateValidation() throws JsonProcessingException{
        //create VP
        String bpn = UUID.randomUUID().toString();
        String audience = "companyA";
        ResponseEntity<Map> vpResponse = createBpnVCAsJwt(bpn, audience);
        Map body = vpResponse.getBody();

        ResponseEntity<Map<String, Object>> mapResponseEntity = presentationController.validatePresentation(body, audience, true, true);

        Map map = mapResponseEntity.getBody();
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALID).toString()));
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALIDATE_AUDIENCE).toString()));
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALIDATE_EXPIRY_DATE).toString()));
        Assertions.assertTrue(Boolean.parseBoolean(map.get(StringPool.VALIDATE_JWT_EXPIRY_DATE).toString()));
    }

    @Test
    void createPresentationAsJWT201() throws JsonProcessingException, ParseException {
        String bpn = UUID.randomUUID().toString();
        String did = DidWebFactory.fromHostnameAndPath(miwSettings.host(), bpn).toString();
        String audience = "companyA";
        ResponseEntity<Map> vpResponse = createBpnVCAsJwt(bpn, audience);
        Assertions.assertEquals(vpResponse.getStatusCode().value(), HttpStatus.CREATED.value());
        String jwt = vpResponse.getBody().get("vp").toString();
        SignedJWT signedJWT = SignedJWT.parse(jwt);
        JWTClaimsSet claimsSet = signedJWT.getJWTClaimsSet();
        String iss = claimsSet.getStringClaim("iss");

        //issuer of VP is must be holder of VP
        Assertions.assertEquals(iss, did);
    }

    private ResponseEntity<Map> createBpnVCAsJwt(String bpn, String audience) throws JsonProcessingException {
        String didWeb = DidWebFactory.fromHostnameAndPath(miwSettings.host(), bpn).toString();

        Map<String, Object> request = getIssueVPRequest(bpn);

        HttpHeaders headers = AuthenticationUtils.getValidUserHttpHeaders(bpn);
        headers.put(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE));

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

        ResponseEntity<Map> vpResponse = restTemplate.exchange(RestURI.API_PRESENTATIONS + "?asJwt={asJwt}&audience={audience}", HttpMethod.POST, entity, Map.class, true, audience);
        return vpResponse;
    }


    @Test
    void createPresentationAsJsonLD201() throws JsonProcessingException {

        String bpn = UUID.randomUUID().toString();
        String didWeb = DidWebFactory.fromHostnameAndPath(miwSettings.host(), bpn).toString();

        Map<String, Object> request = getIssueVPRequest(bpn);

        HttpHeaders headers = AuthenticationUtils.getValidUserHttpHeaders(bpn);
        headers.put(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE));

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

        ResponseEntity<Map> vpResponse = restTemplate.exchange(RestURI.API_PRESENTATIONS, HttpMethod.POST, entity, Map.class);
        Assertions.assertEquals(vpResponse.getStatusCode().value(), HttpStatus.CREATED.value());

    }

    @Test
    void createPresentationWithInvalidBPNAccess403() throws JsonProcessingException {
        String bpn = UUID.randomUUID().toString();
        String didWeb = DidWebFactory.fromHostnameAndPath(miwSettings.host(), bpn).toString();

        Map<String, Object> request = getIssueVPRequest(bpn);

        HttpHeaders headers = AuthenticationUtils.getValidUserHttpHeaders("invalid bpn");
        headers.put(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE));

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

        ResponseEntity<Map> vpResponse = restTemplate.exchange(RestURI.API_PRESENTATIONS + "?asJwt={asJwt}&audience={audience}", HttpMethod.POST, entity, Map.class, true, "companyA");
        Assertions.assertEquals(vpResponse.getStatusCode().value(), HttpStatus.NOT_FOUND.value());
    }

    @Test
    void createPresentationWithMoreThenOneVC400() throws JsonProcessingException {
        String bpn = UUID.randomUUID().toString();
        String didWeb = DidWebFactory.fromHostnameAndPath(miwSettings.host(), bpn).toString();

        ResponseEntity<String> response = TestUtils.createWallet(bpn, bpn, restTemplate);
        Assertions.assertEquals(response.getStatusCode().value(), HttpStatus.CREATED.value());
        Wallet wallet = TestUtils.getWalletFromString(response.getBody());

        //get BPN credentials
        List<HoldersCredential> credentials = holdersCredentialRepository.getByHolderDidAndType(wallet.getDid(), MIWVerifiableCredentialType.BPN_CREDENTIAL);
        Assertions.assertFalse(credentials.isEmpty());
        Map<String, Object> map = objectMapper.readValue(credentials.get(0).getData().toJson(), Map.class);

        //create request
        Map<String, Object> request = new HashMap<>();
        request.put(StringPool.HOLDER_IDENTIFIER, wallet.getDid());
        request.put(StringPool.VERIFIABLE_CREDENTIALS, List.of(map, map));

        HttpHeaders headers = AuthenticationUtils.getValidUserHttpHeaders("invalid bpn");
        headers.put(HttpHeaders.CONTENT_TYPE, List.of(MediaType.APPLICATION_JSON_VALUE));

        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), headers);

        ResponseEntity<Map> vpResponse = restTemplate.exchange(RestURI.API_PRESENTATIONS + "?asJwt={asJwt}&audience={audience}", HttpMethod.POST, entity, Map.class, true, "companyA");
        Assertions.assertEquals(vpResponse.getStatusCode().value(), HttpStatus.BAD_REQUEST.value());
    }

    @NotNull
    private Map<String, Object> getIssueVPRequest(String bpn) throws JsonProcessingException {
        ResponseEntity<String> response = TestUtils.createWallet(bpn, bpn, restTemplate);
        Assertions.assertEquals(response.getStatusCode().value(), HttpStatus.CREATED.value());
        Wallet wallet = TestUtils.getWalletFromString(response.getBody());

        //get BPN credentials
        List<HoldersCredential> credentials = holdersCredentialRepository.getByHolderDidAndType(wallet.getDid(), MIWVerifiableCredentialType.BPN_CREDENTIAL);

        Map<String, Object> map = objectMapper.readValue(credentials.get(0).getData().toJson(), Map.class);

        //create request
        Map<String, Object> request = new HashMap<>();
        request.put(StringPool.HOLDER_IDENTIFIER, wallet.getDid());
        request.put(StringPool.VERIFIABLE_CREDENTIALS, List.of(map));
        return request;
    }
}