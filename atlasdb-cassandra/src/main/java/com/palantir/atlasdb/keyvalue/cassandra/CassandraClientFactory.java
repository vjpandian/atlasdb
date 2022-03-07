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

import com.codahale.metrics.Timer;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.palantir.atlasdb.cassandra.CassandraCredentialsConfig;
import com.palantir.atlasdb.cassandra.CassandraKeyValueServiceConfig;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.common.exception.AtlasDbDependencyException;
import com.palantir.conjure.java.api.config.ssl.SslConfiguration;
import com.palantir.conjure.java.config.ssl.SslSocketFactories;
import com.palantir.logsafe.SafeArg;
import com.palantir.logsafe.UnsafeArg;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.util.TimedRunner;
import com.palantir.util.TimedRunner.TaskContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.apache.cassandra.thrift.AuthenticationRequest;
import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.Cassandra.Client;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.transport.TFramedTransport;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

public class CassandraClientFactory extends BasePooledObjectFactory<CassandraClient> {
    private static final SafeLogger log = SafeLoggerFactory.get(CassandraClientFactory.class);
    private static final LoadingCache<SslConfiguration, SSLSocketFactory> sslSocketFactoryCache =
            Caffeine.newBuilder().weakValues().build(SslSocketFactories::createSslSocketFactory);

    private final MetricsManager metricsManager;
    private final InetSocketAddress addr;
    private final CassandraKeyValueServiceConfig config;
    private final SSLSocketFactory sslSocketFactory;
    private final TimedRunner timedRunner;

    public CassandraClientFactory(
            MetricsManager metricsManager, InetSocketAddress addr, CassandraKeyValueServiceConfig config) {
        this.metricsManager = metricsManager;
        this.addr = addr;
        this.config = config;
        this.sslSocketFactory = createSslSocketFactory(config);
        this.timedRunner = TimedRunner.create(config.timeoutOnConnectionClose());
    }

    @Override
    public CassandraClient create() {
        try {
            return instrumentClient(getRawClientWithKeyspaceSet());
        } catch (Exception e) {
            String message = String.format("Failed to construct client for %s/%s", addr, config.getKeyspaceOrThrow());
            if (config.usingSsl()) {
                message += " over SSL";
            }
            throw new ClientCreationFailedException(message, e);
        }
    }

    private CassandraClient instrumentClient(Client rawClient) {
        CassandraClient client = new CassandraClientImpl(rawClient);
        // TODO(ssouza): use the kvsMethodName to tag the timers.
        client = AtlasDbMetrics.instrumentTimed(metricsManager.getRegistry(), CassandraClient.class, client);
        client = new ProfilingCassandraClient(client);
        client = new TracingCassandraClient(client);
        client = new InstrumentedCassandraClient(client, metricsManager.getTaggedRegistry());
        client = QosCassandraClient.instrumentWithMetrics(client, metricsManager);
        return client;
    }

    private Cassandra.Client getRawClientWithKeyspaceSet() throws TException {
        Client ret = getRawClientWithTimedCreation();
        try {
            ret.set_keyspace(config.getKeyspaceOrThrow());
            log.debug(
                    "Created new client for {}/{}{}{}",
                    SafeArg.of("address", CassandraLogHelper.host(addr)),
                    UnsafeArg.of("keyspace", config.getKeyspaceOrThrow()),
                    SafeArg.of("usingSsl", config.usingSsl() ? " over SSL" : ""),
                    UnsafeArg.of(
                            "usernameConfig", " as user " + config.credentials().username()));
            return ret;
        } catch (TException e) {
            ret.getOutputProtocol().getTransport().close();
            throw e;
        }
    }

    static CassandraClient getClientInternal(InetSocketAddress addr, CassandraKeyValueServiceConfig config)
            throws TException {
        return new CassandraClientImpl(getRawClient(addr, config, createSslSocketFactory(config)));
    }

    private static SSLSocketFactory createSslSocketFactory(CassandraKeyValueServiceConfig config) {
        return config.sslConfiguration()
                .map(sslSocketFactoryCache::get)
                // This path should never be hit in production code, since we expect SSL is configured explicitly.
                .orElseGet(() -> (SSLSocketFactory) SSLSocketFactory.getDefault());
    }

    private Cassandra.Client getRawClientWithTimedCreation() throws TException {
        Timer clientCreation = metricsManager.registerOrGetTimer(CassandraClientFactory.class, "clientCreation");
        try (Timer.Context timer = clientCreation.time()) {
            return getRawClient(addr, config, sslSocketFactory);
        }
    }

    private static Cassandra.Client getRawClient(
            InetSocketAddress addr, CassandraKeyValueServiceConfig config, SSLSocketFactory sslSocketFactory)
            throws TException {
        TSocket thriftSocket = new TSocket(addr.getHostString(), addr.getPort(), config.socketTimeoutMillis());
        thriftSocket.open();
        setSocketOptions(
                thriftSocket,
                socket -> {
                    socket.getSocket().setKeepAlive(true);
                    socket.getSocket().setSoTimeout(config.initialSocketQueryTimeoutMillis());
                },
                addr);

        if (config.usingSsl()) {
            boolean success = false;
            try {
                SSLSocket socket = (SSLSocket) sslSocketFactory.createSocket(
                        thriftSocket.getSocket(), addr.getHostString(), addr.getPort(), true);
                thriftSocket = new TSocket(socket);
                success = true;
            } catch (IOException e) {
                throw new TTransportException(e);
            } finally {
                if (!success) {
                    thriftSocket.close();
                }
            }
        }
        TTransport thriftFramedTransport =
                new TFramedTransport(thriftSocket, CassandraConstants.CLIENT_MAX_THRIFT_FRAME_SIZE_BYTES);
        TProtocol protocol = new TBinaryProtocol(thriftFramedTransport);
        Cassandra.Client client = new Cassandra.Client(protocol);

        try {
            login(client, config.credentials());
            setSocketOptions(
                    thriftSocket, socket -> socket.getSocket().setSoTimeout(config.socketQueryTimeoutMillis()), addr);
        } catch (TException e) {
            client.getOutputProtocol().getTransport().close();
            log.error("Exception thrown attempting to authenticate with config provided credentials", e);
            throw e;
        }

        return client;
    }

    private static void login(Client client, CassandraCredentialsConfig config) throws TException {
        Map<String, String> credsMap = new HashMap<>();
        credsMap.put("username", config.username());
        credsMap.put("password", config.password());
        client.login(new AuthenticationRequest(credsMap));
    }

    @Override
    public boolean validateObject(PooledObject<CassandraClient> client) {
        try {
            return client.getObject().getOutputProtocol().getTransport().isOpen();
        } catch (Throwable t) {
            log.info(
                    "Failed when attempting to validate a Cassandra client in the Cassandra client pool."
                            + " Defensively believing that this object is NOT valid.",
                    SafeArg.of("cassandraClient", CassandraLogHelper.host(addr)),
                    t);
            return false;
        }
    }

    @Override
    public PooledObject<CassandraClient> wrap(CassandraClient client) {
        return new DefaultPooledObject<>(client);
    }

    @Override
    public void destroyObject(PooledObject<CassandraClient> client) {
        if (log.isDebugEnabled()) {
            log.debug(
                    "Attempting to close transport for client {} of host {}",
                    UnsafeArg.of("client", client),
                    SafeArg.of("cassandraClient", CassandraLogHelper.host(addr)));
        }
        try {
            TaskContext<Void> taskContext =
                    TaskContext.createRunnable(() -> client.getObject().close(), () -> {});
            timedRunner.run(taskContext);
        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug(
                        "Failed to close transport for client {} of host {}",
                        UnsafeArg.of("client", client),
                        SafeArg.of("cassandraClient", CassandraLogHelper.host(addr)),
                        t);
            }
            throw new SafeRuntimeException("Threw while attempting to close transport for client", t);
        }
        if (log.isDebugEnabled()) {
            log.debug(
                    "Closed transport for client {} of host {}",
                    UnsafeArg.of("client", client),
                    SafeArg.of("cassandraClient", CassandraLogHelper.host(addr)));
        }
    }

    static class ClientCreationFailedException extends AtlasDbDependencyException {
        private static final long serialVersionUID = 1L;

        ClientCreationFailedException(String message, Exception cause) {
            super(message, cause);
        }

        @Override
        public Exception getCause() {
            return (Exception) super.getCause();
        }
    }

    private static void setSocketOptions(TSocket thriftSocket, SocketConsumer consumer, InetSocketAddress addr) {
        try {
            consumer.accept(thriftSocket);
        } catch (SocketException e) {
            log.error(
                    "Couldn't set socket options for host {}", SafeArg.of("address", CassandraLogHelper.host(addr)), e);
        }
    }

    @FunctionalInterface
    private interface SocketConsumer {
        void accept(TSocket value) throws SocketException;
    }
}
