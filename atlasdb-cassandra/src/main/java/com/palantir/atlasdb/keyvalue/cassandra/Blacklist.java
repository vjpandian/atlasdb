/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
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
package com.palantir.atlasdb.keyvalue.cassandra;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfig;
import com.palantir.atlasdb.keyvalue.cassandra.pool.CassandraServer;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.refreshable.Refreshable;
import java.time.Clock;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Blacklist {
    private static final SafeLogger log = SafeLoggerFactory.get(Blacklist.class);

    private final CassandraKeyValueServiceConfig config;
    private final Clock clock;
    private final Refreshable<Integer> unresponsiveHostBackoffTimeSecondsRefreshable;

    private Map<CassandraServer, Long> blacklist;

    public Blacklist(
            CassandraKeyValueServiceConfig config, Refreshable<Integer> unresponsiveHostBackoffTimeSecondsRefreshable) {
        this(config, unresponsiveHostBackoffTimeSecondsRefreshable, Clock.systemUTC());
    }

    @VisibleForTesting
    Blacklist(
            CassandraKeyValueServiceConfig config,
            Refreshable<Integer> unresponsiveHostBackoffTimeSecondsRefreshable,
            Clock clock) {
        this.config = config;
        this.unresponsiveHostBackoffTimeSecondsRefreshable = unresponsiveHostBackoffTimeSecondsRefreshable;
        this.blacklist = new ConcurrentHashMap<>();
        this.clock = clock;
    }

    void checkAndUpdate(Map<CassandraServer, CassandraClientPoolingContainer> pools) {
        if (blacklist.isEmpty()) {
            return; // nothing to review, no need to iterate
        }

        // Check blacklist and re-integrate or continue to wait as necessary
        Iterator<Entry<CassandraServer, Long>> blacklistIterator =
                blacklist.entrySet().iterator();
        while (blacklistIterator.hasNext()) {
            Map.Entry<CassandraServer, Long> blacklistedEntry = blacklistIterator.next();
            if (coolOffPeriodExpired(blacklistedEntry)) {
                CassandraServer cassandraServer = blacklistedEntry.getKey();
                CassandraClientPoolingContainer container = pools.get(cassandraServer);
                if (container == null) {
                    // Probably the pool changed underneath us
                    blacklistIterator.remove();
                    log.info(
                            "Removing cassandraServer {} from the blacklist as it wasn't found in the pool.",
                            SafeArg.of("cassandraServer", cassandraServer.cassandraHostName()));
                } else if (isHostHealthy(container)) {
                    blacklistIterator.remove();
                    log.info(
                            "Added cassandraServer {} back into the pool after a waiting period and successful health"
                                    + " check.",
                            SafeArg.of("cassandraServer", cassandraServer.cassandraHostName()));
                }
            }
        }
    }

    private boolean coolOffPeriodExpired(Map.Entry<CassandraServer, Long> blacklistedEntry) {
        long backoffTimeMillis = TimeUnit.SECONDS.toMillis(unresponsiveHostBackoffTimeSecondsRefreshable.get());
        return blacklistedEntry.getValue() + backoffTimeMillis < clock.millis();
    }

    private boolean isHostHealthy(CassandraClientPoolingContainer container) {
        try {
            container.runWithPooledResource(CassandraUtils.getDescribeRing(config));
            container.runWithPooledResource(CassandraUtils.getValidatePartitioner(config));
            return true;
        } catch (Exception e) {
            log.info(
                    "We tried to add blacklisted host '{}' back into the pool, but got an exception"
                            + " that caused us to distrust this host further. Exception message was: {} : {}",
                    SafeArg.of("host", container.getCassandraServer().cassandraHostName()),
                    SafeArg.of("exceptionClass", e.getClass().getCanonicalName()),
                    UnsafeArg.of("exceptionMessage", e.getMessage()),
                    e);
            return false;
        }
    }

    public Set<CassandraServer> filterBlacklistedHostsFrom(ImmutableSet<CassandraServer> potentialHosts) {
        return Sets.difference(potentialHosts, blacklist.keySet());
    }

    boolean contains(CassandraServer cassandraServer) {
        return blacklist.containsKey(cassandraServer);
    }

    public void add(CassandraServer cassandraServer) {
        blacklist.put(cassandraServer, clock.millis());
        log.info(
                "Blacklisted cassandra host '{}' with proxy '{}'",
                SafeArg.of("badHost", cassandraServer.cassandraHostName()),
                SafeArg.of("proxy", CassandraLogHelper.host(cassandraServer.proxy())));
    }

    void addAll(Set<CassandraServer> hosts) {
        hosts.forEach(this::add);
    }

    public void remove(CassandraServer host) {
        blacklist.remove(host);
        log.info(
                "Remove blacklisted host '{}' with proxy '{}'",
                SafeArg.of("badHost", host.cassandraHostName()),
                SafeArg.of("proxy", CassandraLogHelper.host(host.proxy())));
    }

    void removeAll() {
        blacklist.clear();
    }

    public int size() {
        return blacklist.size();
    }

    public String describeBlacklistedHosts() {
        return blacklist.keySet().toString();
    }

    public List<String> blacklistDetails() {
        return blacklist.entrySet().stream()
                .map(blacklistedHostToBlacklistTime -> String.format(
                        "host: %s was blacklisted at %s",
                        blacklistedHostToBlacklistTime.getKey().cassandraHostName(),
                        blacklistedHostToBlacklistTime.getValue().longValue()))
                .collect(Collectors.toList());
    }
}
