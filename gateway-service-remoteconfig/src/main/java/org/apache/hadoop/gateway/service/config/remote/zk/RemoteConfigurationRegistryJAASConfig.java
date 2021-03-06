/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.gateway.service.config.remote.zk;

import org.apache.hadoop.gateway.i18n.messages.MessagesFactory;
import org.apache.hadoop.gateway.service.config.remote.RemoteConfigurationMessages;
import org.apache.hadoop.gateway.service.config.remote.RemoteConfigurationRegistryConfig;
import org.apache.hadoop.gateway.services.security.AliasService;
import org.apache.hadoop.gateway.services.security.AliasServiceException;

import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration decorator that adds SASL JAAS configuration to whatever JAAS config is already applied.
 */
class RemoteConfigurationRegistryJAASConfig extends Configuration {

    // Underlying SASL mechanisms supported
    enum SASLMechanism {
        Kerberos,
        Digest
    }

    static final Map<String, String> digestLoginModules = new HashMap<>();
    static {
        digestLoginModules.put("ZOOKEEPER", "org.apache.zookeeper.server.auth.DigestLoginModule");
    }

    private static final RemoteConfigurationMessages log = MessagesFactory.get(RemoteConfigurationMessages.class);

    // Cache the current JAAS configuration
    private Configuration delegate = Configuration.getConfiguration();

    private AliasService aliasService;

    private Map<String, AppConfigurationEntry[]> contextEntries =  new HashMap<>();

    static RemoteConfigurationRegistryJAASConfig configure(List<RemoteConfigurationRegistryConfig> configs, AliasService aliasService) {
        return new RemoteConfigurationRegistryJAASConfig(configs, aliasService);
    }

    private RemoteConfigurationRegistryJAASConfig(List<RemoteConfigurationRegistryConfig> configs, AliasService aliasService) {
        this.aliasService = aliasService;

        // Populate context entries
        List<AppConfigurationEntry> appConfigEntries = new ArrayList<>();
        for (RemoteConfigurationRegistryConfig config : configs) {
            if (config.isSecureRegistry()) {
                contextEntries.put(config.getName(), createEntries(config));
            }
        }

        // If there is at least one context entry, then set this as the client configuration
        if (!contextEntries.isEmpty()) {
            // TODO: PJZ: ZooKeeper 3.6.0 will have per-client JAAS Configuration support; Upgrade ASAP!!
            // For now, set this as the static JAAS configuration
            Configuration.setConfiguration(this);
        }
    }

    @Override
    public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
        AppConfigurationEntry[] result = null;

        // First, try the delegate's context entries
        result = delegate.getAppConfigurationEntry(name);
        if (result == null || result.length < 1) {
            // Try our additional context entries
            result = contextEntries.get(name);
        }

        return result;
    }

    private AppConfigurationEntry[] createEntries(RemoteConfigurationRegistryConfig config) {
        // Only supporting a single app config entry per configuration/context
        AppConfigurationEntry[] result = new AppConfigurationEntry[1];
        result[0] = createEntry(config);
        return result;
    }

    private AppConfigurationEntry createEntry(RemoteConfigurationRegistryConfig config) {
        AppConfigurationEntry entry = null;

        Map<String, String> opts = new HashMap<>();
        SASLMechanism saslMechanism = getSASLMechanism(config.getAuthType());
        switch (saslMechanism) {
            case Digest:
                // Digest auth options
                opts.put("username", config.getPrincipal());

                char[] credential = null;
                if (aliasService != null) {
                    try {
                        credential = aliasService.getPasswordFromAliasForGateway(config.getCredentialAlias());
                    } catch (AliasServiceException e) {
                        log.unresolvedCredentialAlias(config.getCredentialAlias());
                    }
                } else {
                    throw new IllegalArgumentException("The AliasService is required to resolve credential aliases.");
                }

                if (credential != null) {
                    opts.put("password", new String(credential));
                }
                break;
            case Kerberos:
                opts.put("isUseTicketCache", String.valueOf(config.isUseTicketCache()));
                opts.put("isUseKeyTab", String.valueOf(config.isUseKeyTab()));
                opts.put("keyTab", config.getKeytab());
                opts.put("principal", config.getPrincipal());
        }

        entry = new AppConfigurationEntry(getLoginModuleName(config.getRegistryType(), saslMechanism),
                                          AppConfigurationEntry.LoginModuleControlFlag.REQUIRED,
                                          opts);

        return entry;
    }

    private static String getLoginModuleName(String registryType, SASLMechanism saslMechanism) {
        String loginModuleName = null;

        switch (saslMechanism) {
            case Kerberos:
                if (System.getProperty("java.vendor").contains("IBM")) {
                    loginModuleName = "com.ibm.security.auth.module.Krb5LoginModule";
                } else {
                    loginModuleName = "com.sun.security.auth.module.Krb5LoginModule";
                }
                break;
            case Digest:
                loginModuleName = digestLoginModules.get(registryType.toUpperCase());
        }
        return loginModuleName;
    }

    private static SASLMechanism getSASLMechanism(String authType) {
        SASLMechanism result = null;
        for (SASLMechanism at : SASLMechanism.values()) {
            if (at.name().equalsIgnoreCase(authType)) {
                result = at;
                break;
            }
        }
        return result;
    }


}
