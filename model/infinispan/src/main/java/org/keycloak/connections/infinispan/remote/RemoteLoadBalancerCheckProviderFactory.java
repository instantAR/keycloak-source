package org.keycloak.connections.infinispan.remote;

import org.infinispan.client.hotrod.impl.InternalRemoteCache;
import org.infinispan.client.hotrod.impl.operations.PingResponse;
import org.infinispan.commons.util.concurrent.CompletableFutures;
import org.infinispan.util.concurrent.ActionSequencer;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.health.LoadBalancerCheckProvider;
import org.keycloak.health.LoadBalancerCheckProviderFactory;
import org.keycloak.infinispan.util.InfinispanUtils;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;

import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLUSTERED_CACHE_NAMES;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.LOCAL_CACHE_NAMES;
import static org.keycloak.connections.infinispan.InfinispanMultiSiteLoadBalancerCheckProviderFactory.ALWAYS_HEALTHY;
import static org.keycloak.connections.infinispan.InfinispanMultiSiteLoadBalancerCheckProviderFactory.isAnyEmbeddedCachesDown;

public class RemoteLoadBalancerCheckProviderFactory implements LoadBalancerCheckProviderFactory, EnvironmentDependentProviderFactory {

    private static final int DEFAULT_POLL_INTERVAL = 5000;
    private static final Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());

    private volatile int pollIntervalMillis;
    private volatile LoadBalancerCheckProvider provider;
    private InfinispanConnectionProvider connectionProvider;
    private ScheduledFuture<?> availabilityFuture;
    private RemoteCacheCheckList remoteCacheCheckList;

    @Override
    public boolean isSupported(Config.Scope config) {
        return InfinispanUtils.isRemoteInfinispan();
    }

    @Override
    public LoadBalancerCheckProvider create(KeycloakSession session) {
        return provider;
    }

    @Override
    public void init(Config.Scope config) {
        pollIntervalMillis = config.getInt("poll-interval", DEFAULT_POLL_INTERVAL);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        try (var session = factory.create()) {
            var provider = session.getProvider(InfinispanConnectionProvider.class);
            if (provider == null) {
                logger.warn("InfinispanConnectionProvider is not available. Load balancer check will be always healthy for Infinispan.");
                this.provider = ALWAYS_HEALTHY;
                return;
            }
            this.connectionProvider = provider;

            var remoteCacheChecks = Arrays.stream(CLUSTERED_CACHE_NAMES)
                    .map(s -> new RemoteCacheCheck(s, provider))
                    .collect(Collectors.toList());
            var sequencer = new ActionSequencer(connectionProvider.getExecutor("load-balancer-check"), false, null);

            this.remoteCacheCheckList = new RemoteCacheCheckList(remoteCacheChecks, sequencer);
            this.availabilityFuture = provider.getScheduledExecutor()
                    .scheduleAtFixedRate(remoteCacheCheckList, pollIntervalMillis, pollIntervalMillis, TimeUnit.MILLISECONDS);

            this.provider = this::isAnyCacheDown;
        }
    }

    @Override
    public void close() {
        if (availabilityFuture != null) {
            availabilityFuture.cancel(true);
            availabilityFuture = null;
        }
        provider = null;
        remoteCacheCheckList = null;
    }

    @Override
    public String getId() {
        return InfinispanUtils.REMOTE_PROVIDER_ID;
    }

    @Override
    public int order() {
        return InfinispanUtils.PROVIDER_ORDER;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                .name("poll-interval")
                .type("int")
                .helpText("The Remote caches poll interval, in milliseconds, for connection availability")
                .defaultValue(DEFAULT_POLL_INTERVAL)
                .add()
                .build();
    }

    private boolean isAnyCacheDown() {
        return isEmbeddedCachesDown() || remoteCacheCheckList.isDown();
    }

    private boolean isEmbeddedCachesDown() {
        return isAnyEmbeddedCachesDown(connectionProvider, LOCAL_CACHE_NAMES, logger);
    }

    private record RemoteCacheCheckList(List<RemoteCacheCheck> list, ActionSequencer sequencer) implements Runnable {
        @Override
        public void run() {
            list.forEach(remoteCacheCheck -> sequencer.orderOnKey(remoteCacheCheck.name(), remoteCacheCheck));
        }

        public boolean isDown() {
            return list.stream().anyMatch(RemoteCacheCheck::isDown);
        }
    }

    private static class RemoteCacheCheck implements Callable<CompletionStage<Void>>, BiFunction<PingResponse, Throwable, Void> {

        private final String name;
        private final InfinispanConnectionProvider provider;
        private volatile boolean isDown;

        private RemoteCacheCheck(String name, InfinispanConnectionProvider provider) {
            this.name = name;
            this.provider = provider;
        }

        String name() {
            return name;
        }

        boolean isDown() {
            return isDown;
        }

        @Override
        public CompletionStage<Void> call() {
            try {
                var cache = provider.getRemoteCache(name);
                if (cache instanceof InternalRemoteCache<Object, Object>) {
                    return ((InternalRemoteCache<Object, Object>) cache).ping()
                            .handle(this);
                }
                isDown = false;
            } catch (Exception e) {
                if (!isDown) {
                    logger.warnf("Remote cache '%' is down.", name);
                }
                isDown = true;
            }
            return CompletableFutures.completedNull();
        }

        @Override
        public Void apply(PingResponse response, Throwable throwable) {
            logger.debugf("Received Ping response for cache '%s'. Success=%s, Throwable=%s", name, response.isSuccess(), throwable);
            isDown = throwable != null || !response.isSuccess();
            return null;
        }
    }
}
