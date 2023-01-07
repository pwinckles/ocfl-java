package edu.wisc.library.ocfl.itest;

import edu.wisc.library.ocfl.api.MutableOcflRepository;
import edu.wisc.library.ocfl.api.OcflRepository;
import edu.wisc.library.ocfl.api.model.ObjectVersionId;
import edu.wisc.library.ocfl.api.model.VersionInfo;
import edu.wisc.library.ocfl.aws.OcflS3Client;
import edu.wisc.library.ocfl.core.OcflRepositoryBuilder;
import edu.wisc.library.ocfl.core.cache.NoOpCache;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.HTTPServer;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

@Disabled
public class LoadITest {

    // AVG: rate(putObject_seconds_sum[1m])/rate(putObject_seconds_count[1m])
    // p99: histogram_quantile(0.99, sum(rate(putObject_seconds_bucket[1m])) by (le))

    private static final int KB = 1024;
    private static final long MB = 1024 * KB;

    private static final int BUFFER_SIZE = 32 * KB;

    @TempDir
    public Path tempRoot;

    private static HTTPServer prometheusServer;

    @BeforeAll
    public static void beforeAll() throws IOException {
        var registry = new PrometheusMeterRegistry(new PrometheusConfig() {
            @Override
            public Duration step() {
                return Duration.ofSeconds(30);
            }

            @Override
            public String get(final String key) {
                return null;
            }
        });
        // Enables distribution stats for all timer metrics
        registry.config().meterFilter(new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(final Meter.Id id, final DistributionStatisticConfig config) {
                if (id.getType() == Meter.Type.TIMER) {
                    return DistributionStatisticConfig.builder()
                            .percentilesHistogram(true)
                            .percentiles(0.5, 0.90, 0.99)
                            .build()
                            .merge(config);
                }
                return config;
            }
        });
        Metrics.addRegistry(registry);

        prometheusServer = new HTTPServer(new InetSocketAddress(1234), registry.getPrometheusRegistry());
    }

    @AfterAll
    public static void afterAll() {
        prometheusServer.stop();
    }

    @Test
    public void fsPutObjectSmallFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(7);
        var fileCount = 10;
        var fileSize = 128 * KB;

        var repo = createFsRepo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "fs", true);
    }

    @Test
    public void fsPutObjectModestFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(7);
        var fileCount = 10;
        var fileSize = 3 * MB;

        var repo = createFsRepo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "fs", true);
    }

    @Test
    public void fsPutObjectManyFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(10);
        var fileCount = 2048;
        var fileSize = 128 * KB;

        var repo = createFsRepo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "fs", true);
    }

    @Test
    public void fsPutObjectLargeFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 2;
        var fileSize = 128 * MB;

        var repo = createFsRepo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "fs", true);
    }

    @Test
    public void s3PutObjectModestFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 10;
        var fileSize = 3 * MB;

        var repo = createS3Repo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "s3", false);
        // TODO Don't forget to delete from S3!
    }

    @Test
    public void s3PutObjectManyFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 2048;
        var fileSize = 128 * KB;

        var repo = createS3Repo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "s3", false);
        // TODO Don't forget to delete from S3!
    }

    @Test
    public void s3PutObjectLargeFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 2;
        var fileSize = 128 * MB;

        var repo = createS3Repo();

        runPutTest(repo, fileCount, fileSize, threadCount, duration, "s3", false);
        // TODO Don't forget to delete from S3!
    }

    @Test
    public void fsGetObjectModestFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 10;
        var fileSize = 3 * MB;

        var repo = createFsRepo();

        runGetTest(repo, fileCount, fileSize, threadCount, duration, "fs");
    }

    @Test
    public void s3GetObjectModestFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 10;
        var fileSize = 3 * MB;

        var repo = createS3Repo();

        runGetTest(repo, fileCount, fileSize, threadCount, duration, "s3");
        // TODO Don't forget to delete from S3!
    }

    @Test
    public void s3MutableHeadCopyObjectModestFilesTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var fileCount = 10;
        var fileSize = 3 * MB;

        var repo = createS3Repo();

        System.out.println("Starting putTest");

        System.out.println("Creating test object");
        var objectPath = createTestObject(fileCount, fileSize);
        System.out.println("Created test object: " + objectPath);

        var versionInfo =
                new VersionInfo().setUser("Peter", "pwinckles@example.com").setMessage("Testing");

        var timer = Metrics.timer(
                "putObject",
                "files",
                String.valueOf(fileCount),
                "sizeBytes",
                String.valueOf(fileSize),
                "threads",
                String.valueOf(threadCount),
                "storage",
                "s3");

        var threads = new ArrayList<Thread>(threadCount);

        for (var i = 0; i < threadCount; i++) {
            threads.add(createThread(duration, objectId -> {
                repo.stageChanges(ObjectVersionId.head(objectId), versionInfo, updater -> {
                    updater.addPath(objectPath);
                });
                timer.record(() -> {
                    repo.commitStagedChanges(objectId, versionInfo);
                });
            }));
        }

        startThreads(threads);
        System.out.println("Waiting for threads to complete...");
        joinThreads(threads);

        System.out.println("Finished. Waiting for metrics collection...");
        TimeUnit.SECONDS.sleep(30);
        System.out.println("Done");
        // TODO Don't forget to delete from S3!
    }

    @Test
    public void s3WriteTest() throws InterruptedException {
        var threadCount = 10;
        var duration = Duration.ofMinutes(15);
        var objectPath = createTestObject(1, 3 * MB);
        var prefix = UUID.randomUUID().toString();

        var s3Client = S3AsyncClient.crtBuilder().region(Region.US_EAST_2).build();
        var transferManager = S3TransferManager.builder().s3Client(s3Client).build();
        var cloutClient = OcflS3Client.builder()
                .s3Client(s3Client)
                .transferManager(transferManager)
                .bucket("pwinckles-ocfl")
                .repoPrefix(prefix)
                .build();

        var timer = Metrics.timer("putObjectDirect", "threads", String.valueOf(threadCount), "storage", "s3");

        var threads = new ArrayList<Thread>(threadCount);

        for (var i = 0; i < threadCount; i++) {
            threads.add(createThread(duration, objectId -> {
                timer.record(() -> {
                    cloutClient.uploadFile(objectPath.resolve("file-0"), objectId + "/file-0");
                });
            }));
        }

        startThreads(threads);
        System.out.println("Waiting for threads to complete...");
        joinThreads(threads);

        System.out.println("Finished. Waiting for metrics collection...");
        TimeUnit.SECONDS.sleep(30);
        System.out.println("Done");

        s3Client.close();
        transferManager.close();
    }

    private void runPutTest(
            OcflRepository repo,
            int fileCount,
            long fileSize,
            int threadCount,
            Duration duration,
            String storageType,
            boolean shouldPurge)
            throws InterruptedException {
        System.out.println("Starting putTest");

        System.out.println("Creating test object");
        var objectPath = createTestObject(fileCount, fileSize);
        System.out.println("Created test object: " + objectPath);

        var versionInfo =
                new VersionInfo().setUser("Peter", "pwinckles@example.com").setMessage("Testing");

        var timer = Metrics.timer(
                "putObject",
                "files",
                String.valueOf(fileCount),
                "sizeBytes",
                String.valueOf(fileSize),
                "threads",
                String.valueOf(threadCount),
                "storage",
                storageType);

        var threads = new ArrayList<Thread>(threadCount);

        for (var i = 0; i < threadCount; i++) {
            threads.add(createThread(duration, objectId -> {
                timer.record(() -> {
                    repo.putObject(ObjectVersionId.head(objectId), objectPath, versionInfo);
                });
                if (shouldPurge) {
                    repo.purgeObject(objectId);
                }
            }));
        }

        startThreads(threads);
        System.out.println("Waiting for threads to complete...");
        joinThreads(threads);

        System.out.println("Finished. Waiting for metrics collection...");
        TimeUnit.SECONDS.sleep(30);
        System.out.println("Done");
    }

    private void runGetTest(
            OcflRepository repo, int fileCount, long fileSize, int threadCount, Duration duration, String storageType)
            throws InterruptedException {
        System.out.println("Starting getTest");

        System.out.println("Creating test object");
        var objectPath = createTestObject(fileCount, fileSize);
        System.out.println("Created test object: " + objectPath);

        var versionInfo =
                new VersionInfo().setUser("Peter", "pwinckles@example.com").setMessage("Testing");

        var objectId = UUID.randomUUID().toString();

        repo.putObject(ObjectVersionId.head(objectId), objectPath, versionInfo);

        var timer = Metrics.timer(
                "getObject",
                "files",
                String.valueOf(fileCount),
                "sizeBytes",
                String.valueOf(fileSize),
                "threads",
                String.valueOf(threadCount),
                "storage",
                storageType);

        var threads = new ArrayList<Thread>(threadCount);

        for (var i = 0; i < threadCount; i++) {
            threads.add(createThread(duration, out -> {
                var outDir = tempRoot.resolve(out);
                timer.record(() -> {
                    repo.getObject(ObjectVersionId.head(objectId), outDir);
                });
                FileUtil.safeDeleteDirectory(outDir);
            }));
        }

        startThreads(threads);
        System.out.println("Waiting for threads to complete...");
        joinThreads(threads);

        System.out.println("Finished. Waiting for metrics collection...");
        TimeUnit.SECONDS.sleep(30);
        System.out.println("Done");
    }

    private Thread createThread(Duration duration, Consumer<String> test) {
        return new Thread() {
            private final String id = UUID.randomUUID().toString();

            @Override
            public void run() {
                System.out.println("Starting thread " + id);
                var count = 0;
                var start = Instant.now();

                while (Duration.between(start, Instant.now()).compareTo(duration) < 0) {
                    if (Thread.interrupted()) {
                        break;
                    }
                    var objectId = id + "-" + count++;
                    try {
                        test.accept(objectId);
                    } catch (RuntimeException e) {
                        System.err.println("Exception in thread: " + id);
                        e.printStackTrace(System.err);
                    }
                }

                System.out.println("Completed thread " + id);
            }
        };
    }

    private void startThreads(List<Thread> threads) {
        for (var thread : threads) {
            thread.start();
        }
    }

    private void joinThreads(List<Thread> threads) throws InterruptedException {
        for (var thread : threads) {
            thread.join();
        }
    }

    private OcflRepository createFsRepo() {
        System.out.println("OCFL root: " + tempRoot.toString() + "/ocfl");
        return new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .inventoryCache(new NoOpCache<>())
                .storage(storage -> storage.fileSystem(UncheckedFiles.createDirectories(tempRoot.resolve("ocfl"))))
                .workDir(UncheckedFiles.createDirectories(tempRoot.resolve("temp")))
                .build();
    }

    private MutableOcflRepository createS3Repo() {
        var s3Client = S3AsyncClient.crtBuilder().region(Region.US_EAST_2).build();
        var transferManager = S3TransferManager.builder().s3Client(s3Client).build();
        var prefix = UUID.randomUUID().toString();
        // Note this is NOT using a db, which an S3 setup would normally use
        return new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .inventoryCache(new NoOpCache<>())
                .storage(storage -> {
                    storage.cloud(OcflS3Client.builder()
                            .bucket("pwinckles-ocfl")
                            .repoPrefix(prefix)
                            .s3Client(s3Client)
                            .transferManager(transferManager)
                            .build());
                })
                .workDir(UncheckedFiles.createDirectories(tempRoot.resolve("temp")))
                .buildMutable();
    }

    private Path createTestObject(int fileCount, long fileSize) {
        var uuid = UUID.randomUUID().toString();
        var objectPath = UncheckedFiles.createDirectories(tempRoot.resolve(uuid));

        for (int i = 0; i < fileCount; i++) {
            writeFile(objectPath.resolve("file-" + i), fileSize);
        }

        return objectPath;
    }

    private void writeFile(Path path, long size) {
        var bytes = new byte[BUFFER_SIZE];
        try (var out = new BufferedOutputStream(Files.newOutputStream(path))) {
            var written = 0;
            while (written < size) {
                ThreadLocalRandom.current().nextBytes(bytes);
                out.write(bytes);
                written += BUFFER_SIZE;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
