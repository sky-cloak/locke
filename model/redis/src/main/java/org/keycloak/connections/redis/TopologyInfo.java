/*
 * Copyright 2025 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.connections.redis;

import java.security.SecureRandom;

/**
 * Topology information for Redis deployments.
 * Provides node name and site name for multi-region setups.
 *
 * @author Keycloak Redis Team
 */
public class TopologyInfo {

    private static final String NODE_PREFIX = "node_";

    private final String myNodeName;
    private final String mySiteName;

    /**
     * Create topology info with specified node and site names.
     *
     * @param nodeName node name (null for auto-generation)
     * @param siteName site name (null for single-site)
     */
    public TopologyInfo(String nodeName, String siteName) {
        this.myNodeName = (nodeName != null && !nodeName.isEmpty()) ? nodeName : generateNodeName();
        this.mySiteName = siteName;
    }

    /**
     * Create topology info with auto-generated node name.
     */
    public TopologyInfo() {
        this(null, null);
    }

    private static String generateNodeName() {
        return NODE_PREFIX + new SecureRandom().nextInt(1000000);
    }

    public String getMyNodeName() {
        return myNodeName;
    }

    public String getMySiteName() {
        return mySiteName;
    }

    @Override
    public String toString() {
        return String.format("Node name: %s, Site name: %s", myNodeName, mySiteName);
    }
}
