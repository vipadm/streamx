/*
 * Copyright (c) 2019 The StreamX Project
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.streamxhub.streamx.console.core.service.alert.impl;

import com.streamxhub.streamx.console.base.exception.ServiceException;
import com.streamxhub.streamx.console.base.util.FreemarkerUtils;
import com.streamxhub.streamx.console.core.entity.alert.AlertConfigWithParams;
import com.streamxhub.streamx.console.core.entity.alert.AlertTemplate;
import com.streamxhub.streamx.console.core.entity.alert.DingTalkParams;
import com.streamxhub.streamx.console.core.entity.alert.RobotResponse;
import com.streamxhub.streamx.console.core.service.alert.AlertNotifyService;

import freemarker.template.Template;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.net.util.Base64;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * @author weijinglun
 * @date 2022.01.14
 */
@Slf4j
@Service
@Lazy
public class DingTalkAlertNotifyServiceImpl implements AlertNotifyService {
    private Template template;

    private final RestTemplate alertRestTemplate;

    public DingTalkAlertNotifyServiceImpl(RestTemplate alertRestTemplate) {
        this.alertRestTemplate = alertRestTemplate;
    }

    @PostConstruct
    public void loadTemplateFile() throws Exception {
        String template = "alert-dingTalk.ftl";
        this.template = FreemarkerUtils.loadTemplateFile(template);
    }

    @Override
    public boolean doAlert(AlertConfigWithParams alertConfig, AlertTemplate alertTemplate) {
        DingTalkParams dingTalkParams = alertConfig.getDingTalkParams();
        try {
            // handling contacts
            List<String> contactList = new ArrayList<>();
            String contacts = dingTalkParams.getContacts();
            if (StringUtils.hasLength(contacts)) {
                Collections.addAll(contactList, contacts.split(","));
            }
            String title = alertTemplate.getTitle();
            if (contactList.size() > 0) {
                StringJoiner joiner = new StringJoiner(",@", title + " @", "");
                contactList.forEach(joiner::add);
                title = joiner.toString();
            }
            Map<String, Object> contactMap = new HashMap<>();
            contactMap.put("atMobiles", contactList);
            contactMap.put("isAtAll", BooleanUtils.toBoolean(dingTalkParams.getIsAtAll()));

            // format markdown
            String markdown = FreemarkerUtils.format(template, alertTemplate);

            Map<String, String> content = new HashMap<>();
            content.put("title", title);
            content.put("text", markdown);

            Map<String, Object> body = new HashMap<>();
            body.put("msgtype", "markdown");
            body.put("markdown", content);
            body.put("at", contactMap);

            sendMessage(dingTalkParams, body);
            return true;
        } catch (Exception e) {
            log.error("Failed send dingTalk alert", e);
            return false;
        }
    }

    private RobotResponse sendMessage(DingTalkParams params, Map<String, Object> body) throws ServiceException {
        // get webhook url
        String url = getWebhook(params);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        RobotResponse robotResponse;
        try {
            robotResponse = alertRestTemplate.postForObject(url, entity, RobotResponse.class);
        } catch (Exception e) {
            log.error("Failed to request DingTalk robot alarm, url:{}", url, e);
            throw new ServiceException(String.format("Failed to request DingTalk robot alert, url:%s", url), e);
        }

        if (robotResponse == null) {
            throw new ServiceException(String.format("Failed to request DingTalk robot alert, url:%s", url));
        }
        if (robotResponse.getErrcode() != 0) {
            throw new ServiceException(String.format("Failed to request DingTalk robot alert, url:%s, errorCode:%d, errorMsg:%s",
                    url, robotResponse.getErrcode(), robotResponse.getErrmsg()));
        }
        return robotResponse;
    }

    /**
     * Gets webhook.
     *
     * @param params {@link  DingTalkParams}
     * @return the webhook
     */
    private String getWebhook(DingTalkParams params) {
        String urlPef = "https://oapi.dingtalk.com/robot/send?access_token=";
        if (StringUtils.hasLength(params.getAlertDingURL())) {
            urlPef = params.getAlertDingURL();
        }

        String url;
        if (params.getSecretEnable()) {
            Long timestamp = System.currentTimeMillis();
            url = String.format(urlPef + "%s&timestamp=%d&sign=%s",
                    params.getToken(), timestamp, getSign(params.getSecretToken(), timestamp));
        } else {
            url = String.format(urlPef + "%s", params.getToken());
        }
        if (log.isDebugEnabled()) {
            log.debug("The alarm robot url of DingTalk contains signature is {}", url);
        }
        return url;
    }

    /**
     * Calculate the signature
     * <p>Reference documentation</p>
     * <a href="https://open.dingtalk.com/document/group/customize-robot-security-settings">Customize Robot Security Settings</a>
     *
     * @param secret    secret
     * @param timestamp current timestamp
     * @return Signature information calculated from timestamp
     */
    private String getSign(String secret, Long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(new String(Base64.encodeBase64(signData)), "UTF-8");
            if (log.isDebugEnabled()) {
                log.debug("Calculate the signature success, sign:{}", sign);
            }
            return sign;
        } catch (Exception e) {
            log.error("Calculate the signature failed.", e);
            return null;
        }
    }
}
