package org.eclipse.che;

import com.google.protobuf.Duration;
import io.envoyproxy.controlplane.cache.SimpleCache;
import io.envoyproxy.controlplane.cache.Snapshot;
import io.envoyproxy.controlplane.cache.SnapshotCache;
import io.envoyproxy.controlplane.server.DiscoveryServer;
import io.envoyproxy.envoy.api.v2.Cluster;
import io.envoyproxy.envoy.api.v2.ClusterLoadAssignment;
import io.envoyproxy.envoy.api.v2.RouteConfiguration;
import io.envoyproxy.envoy.api.v2.core.Address;
import io.envoyproxy.envoy.api.v2.core.SocketAddress;
import io.envoyproxy.envoy.api.v2.endpoint.Endpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LbEndpoint;
import io.envoyproxy.envoy.api.v2.endpoint.LocalityLbEndpoints;
import io.envoyproxy.envoy.api.v2.route.Route;
import io.envoyproxy.envoy.api.v2.route.RouteAction;
import io.envoyproxy.envoy.api.v2.route.RouteMatch;
import io.envoyproxy.envoy.api.v2.route.VirtualHost;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

public class EnvoyControl {
    private static final Logger LOG = LoggerFactory.getLogger(EnvoyControl.class);

    private static final String GROUP = "che-group";

    private static final AtomicLong VERSION = new AtomicLong();

    private static Snapshot initialSnapshot() {
        return Snapshot.create(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                Long.toString(VERSION.getAndIncrement()));
    }

    private static void startEnvoyManager(SnapshotCache<String> cache) throws IOException {
        DiscoveryServer discoveryServer = new DiscoveryServer(cache);

        ServerBuilder<?> builder = NettyServerBuilder.forPort(12345)
                .addService(discoveryServer.getAggregatedDiscoveryServiceImpl())
                .addService(discoveryServer.getClusterDiscoveryServiceImpl())
                .addService(discoveryServer.getEndpointDiscoveryServiceImpl())
                .addService(discoveryServer.getListenerDiscoveryServiceImpl())
                .addService(discoveryServer.getRouteDiscoveryServiceImpl());

        Server server = builder.build();

        server.start();

        LOG.info("Server has started on port " + server.getPort());

        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            LOG.error("Expecting a single commandline argument with a path to the CSV file" +
                    " defining the servers.");
            System.exit(1);
        }

        File serversFile = new File(args[0]);
        Path serversPath = serversFile.toPath();

        SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
        cache.setSnapshot(GROUP, initialSnapshot());

        startEnvoyManager(cache);

        ScheduledExecutorService configLoad = Executors.newSingleThreadScheduledExecutor();
        configLoad.schedule(() -> {
            try {
                if (serversFile.exists()) {
                    loadConfig(cache, serversPath);
                }
            } catch (Exception e) {
                LOG.error(format("Failed to load the config from file '%s': %s", serversFile, e.getMessage()));
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    private static void loadConfig(SnapshotCache<String> cache, Path serversFile) {
        try {
            // the CSV file contains the target hosts as a CSV in the following format:
            // request_path,target_service_dnsname
            List<Cluster> clusters = new ArrayList<>();
            VirtualHost.Builder routes = VirtualHost.newBuilder();
            routes.setName("workspaces").addDomains("*");

            int[] idx = new int[1];
            Files.lines(serversFile).forEach(line -> {
                String[] fields = line.split(",");
                String requestPath = fields[0];
                String requestPathWithSlash = requestPath;
                if (requestPath.endsWith("/")) {
                    requestPath = requestPath.substring(0, requestPath.length() - 1);
                } else {
                    requestPathWithSlash = requestPath + "/";
                }

                String targetService = fields[1];

                String clusterName = "cluster-" + idx[0];

                Cluster cluster = Cluster.newBuilder()
                        .setName(clusterName)
                        .setConnectTimeout(Duration.newBuilder().setSeconds(1))
                        .setType(Cluster.DiscoveryType.STRICT_DNS)
                        .setLbPolicy(Cluster.LbPolicy.ROUND_ROBIN)
                        .setLoadAssignment(ClusterLoadAssignment.newBuilder()
                                .addEndpoints(LocalityLbEndpoints.newBuilder()
                                        .addLbEndpoints(LbEndpoint.newBuilder()
                                                .setEndpoint(Endpoint.newBuilder()
                                                        .setAddress(Address.newBuilder()
                                                                .setSocketAddress(SocketAddress.newBuilder()
                                                                        .setAddress(targetService)))))))
                        .build();


                routes.addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix(requestPath))
                        .setRoute(RouteAction.newBuilder()
                                .setCluster(clusterName)
                                .setPrefixRewrite("/").build()));
                routes.addRoutes(Route.newBuilder()
                        .setMatch(RouteMatch.newBuilder()
                                .setPrefix(requestPathWithSlash))
                        .setRoute(RouteAction.newBuilder()
                                .setCluster(clusterName)
                                .setPrefixRewrite("/").build()));

                clusters.add(cluster);

                idx[0]++;
            });

            RouteConfiguration routeConfig = RouteConfiguration.newBuilder()
                    .setName("workspaces")
                    .addVirtualHosts(routes)
                    .build();

            cache.setSnapshot(GROUP, Snapshot.create(clusters, emptyList(), emptyList(), List.of(routeConfig),
                    emptyList(), Long.toString(VERSION.getAndIncrement())));
        } catch (IOException e) {
            LOG.error("Error while reading the CSV file.", e);
        }
    }
}
