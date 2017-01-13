/**
 * Copyright 2016 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.timelock.paxos;

import java.nio.file.Paths;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.net.ssl.SSLSocketFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.palantir.atlasdb.config.ImmutableLeaderConfig;
import com.palantir.atlasdb.config.LeaderConfig;
import com.palantir.atlasdb.factory.Leaders;
import com.palantir.atlasdb.http.NotCurrentLeaderExceptionMapper;
import com.palantir.atlasdb.timelock.ServerImplementation;
import com.palantir.atlasdb.timelock.TimeLockServices;
import com.palantir.atlasdb.timelock.config.PaxosConfiguration;
import com.palantir.atlasdb.timelock.config.TimeLockServerConfiguration;
import com.palantir.leader.LeaderElectionService;
import com.palantir.leader.proxy.AwaitingLeadershipProxy;
import com.palantir.lock.LockService;
import com.palantir.lock.impl.LockServiceImpl;
import com.palantir.paxos.PaxosAcceptor;
import com.palantir.paxos.PaxosLearner;
import com.palantir.paxos.PaxosProposer;
import com.palantir.remoting.ssl.SslSocketFactories;
import com.palantir.timestamp.PersistentTimestampService;
import com.palantir.timestamp.TimestampService;

import io.dropwizard.setup.Environment;

public class PaxosServerImplementation implements ServerImplementation {

    private final PaxosConfiguration paxosConfiguration;
    private final Environment environment;

    private Set<String> remoteServers;
    private Optional<SSLSocketFactory> optionalSecurity = Optional.absent();
    private LeaderElectionService leaderElectionService;
    private PaxosResource paxosResource;

    public PaxosServerImplementation(PaxosConfiguration configuration, Environment environment) {
        this.paxosConfiguration = configuration;
        this.environment = environment;
    }

    @Override
    public void onStartup(TimeLockServerConfiguration configuration) {
        registerPaxosResource();

        optionalSecurity = constructOptionalSslSocketFactory(paxosConfiguration);

        registerLeaderElectionService(configuration);
    }

    private void registerPaxosResource() {
        paxosResource = PaxosResource.create(paxosConfiguration.paxosDataDir().toString());
        environment.jersey().register(paxosResource);
    }

    private void registerLeaderElectionService(TimeLockServerConfiguration configuration) {
        remoteServers = getRemotePaths(configuration);

        LeaderConfig leaderConfig = ImmutableLeaderConfig.builder()
                .leaders(configuration.cluster().servers())
                .localServer(configuration.cluster().localServer())
                .acceptorLogDir(Paths.get(paxosConfiguration.paxosDataDir().toString(),
                        PaxosTimeLockConstants.ACCEPTOR_PATH).toFile())
                .learnerLogDir(Paths.get(paxosConfiguration.paxosDataDir().toString(),
                        PaxosTimeLockConstants.LEARNER_PATH).toFile())
                .pingRateMs(paxosConfiguration.pingRateMs())
                .quorumSize(getQuorumSize(configuration.cluster().servers()))
                .leaderPingResponseWaitMs(paxosConfiguration.leaderPingResponseWaitMs())
                .randomWaitBeforeProposingLeadershipMs(paxosConfiguration.maximumWaitBeforeProposalMs())
                .build();

        Leaders.LocalPaxosServices localPaxosServices = Leaders.createLocalServices(leaderConfig);

        leaderElectionService = localPaxosServices.leaderElectionService();

        environment.jersey().register(localPaxosServices.ourAcceptor());
        environment.jersey().register(localPaxosServices.ourLearner());
        environment.jersey().register(leaderElectionService);
        environment.jersey().register(new NotCurrentLeaderExceptionMapper());
    }

    @VisibleForTesting
    static <T> int getQuorumSize(Collection<T> elements) {
        return elements.size() / 2 + 1;
    }

    private static Optional<SSLSocketFactory> constructOptionalSslSocketFactory(
            PaxosConfiguration configuration) {
        return configuration.sslConfiguration().transform(SslSocketFactories::createSslSocketFactory);
    }

    @Override
    public void onStop() {
        // Nothing to do
    }

    @Override
    public void onStartupFailure() {
        // Nothing
    }

    @Override
    public TimeLockServices createInvalidatingTimeLockServices(String client) {
        TimestampService timestampService = createPaxosBackedTimestampService(client);
        LockService lockService = AwaitingLeadershipProxy.newProxyInstance(
                LockService.class,
                LockServiceImpl::create,
                leaderElectionService);

        return TimeLockServices.create(timestampService, lockService);
    }

    private TimestampService createPaxosBackedTimestampService(String client) {
        paxosResource.addClient(client);

        ExecutorService executor = Executors.newCachedThreadPool(new ThreadFactoryBuilder()
                .setNameFormat("atlas-consensus-" + client + "-%d")
                .setDaemon(true)
                .build());

        Set<String> namespacedUris = getNamespacedUris(remoteServers, client);
        List<PaxosAcceptor> acceptors = Leaders.createAcceptorList(
                paxosResource.getPaxosAcceptor(client),
                namespacedUris,
                optionalSecurity);
        List<PaxosLearner> learners = Leaders.createLearnerList(
                paxosResource.getPaxosLearner(client),
                namespacedUris,
                optionalSecurity);

        PaxosProposer proposer = Leaders.createPaxosProposer(
                paxosResource.getPaxosLearner(client),
                ImmutableList.copyOf(acceptors),
                ImmutableList.copyOf(learners),
                getQuorumSize(acceptors),
                executor);

        return AwaitingLeadershipProxy.newProxyInstance(
                TimestampService.class,
                () -> PersistentTimestampService.create(
                        new PaxosTimestampBoundStore(
                                proposer,
                                paxosResource.getPaxosLearner(client),
                                ImmutableList.copyOf(acceptors),
                                ImmutableList.copyOf(learners),
                                paxosConfiguration.maximumWaitBeforeProposalMs())),
                leaderElectionService);
    }

    private static Set<String> getRemoteAddresses(TimeLockServerConfiguration configuration) {
        return Sets.difference(configuration.cluster().servers(),
                ImmutableSet.of(configuration.cluster().localServer()));
    }

    private Set<String> getRemotePaths(TimeLockServerConfiguration configuration) {
        String protocolPrefix = paxosConfiguration.sslConfiguration().isPresent()
                ? "https://" : "http://";
        return getRemoteAddresses(configuration).stream()
                .map(address -> protocolPrefix + address)
                .collect(Collectors.toSet());
    }

    private static Set<String> getNamespacedUris(Set<String> addresses, String suffix) {
        return addresses.stream()
                .map(address -> address + "/" + suffix)
                .collect(Collectors.toSet());
    }
}
