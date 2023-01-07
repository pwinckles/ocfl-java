package edu.wisc.library.ocfl.itest.s3;

import static edu.wisc.library.ocfl.itest.ITestHelper.expectedRepoPath;
import static edu.wisc.library.ocfl.itest.ITestHelper.sourceRepoPath;
import static edu.wisc.library.ocfl.itest.ITestHelper.streamString;
import static edu.wisc.library.ocfl.itest.TestHelper.inputStream;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import edu.wisc.library.ocfl.api.OcflRepository;
import edu.wisc.library.ocfl.api.model.ObjectVersionId;
import edu.wisc.library.ocfl.api.model.VersionInfo;
import edu.wisc.library.ocfl.aws.OcflS3Client;
import edu.wisc.library.ocfl.core.OcflRepositoryBuilder;
import edu.wisc.library.ocfl.core.cache.NoOpCache;
import edu.wisc.library.ocfl.core.extension.UnsupportedExtensionBehavior;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedNTupleIdEncapsulationLayoutConfig;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import edu.wisc.library.ocfl.core.path.constraint.ContentPathConstraints;
import edu.wisc.library.ocfl.core.storage.cloud.CloudClient;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.itest.ITestHelper;
import edu.wisc.library.ocfl.itest.OcflITest;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

public class S3OcflITest extends OcflITest {

    private static final Logger LOG = LoggerFactory.getLogger(S3OcflITest.class);

    private static final String ENV_ACCESS_KEY = "OCFL_TEST_AWS_ACCESS_KEY";
    private static final String ENV_SECRET_KEY = "OCFL_TEST_AWS_SECRET_KEY";
    private static final String ENV_BUCKET = "OCFL_TEST_S3_BUCKET";

    private static final String REPO_PREFIX =
            "S3OcflITest" + ThreadLocalRandom.current().nextLong();

    @RegisterExtension
    public static S3MockExtension S3_MOCK = S3MockExtension.builder().silent().build();

    private static S3AsyncClient s3Client;
    private static S3TransferManager transferManager;
    private static String bucket;

    private static ComboPooledDataSource dataSource;

    private S3ITestHelper s3Helper;
    private Set<String> repoPrefixes = new HashSet<>();

    @BeforeAll
    public static void beforeAll() {
        var accessKey = System.getenv().get(ENV_ACCESS_KEY);
        var secretKey = System.getenv().get(ENV_SECRET_KEY);
        var bucket = System.getenv().get(ENV_BUCKET);

        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey) && StringUtils.isNotBlank(bucket)) {
            LOG.info("Running tests against AWS");
            s3Client = S3ITestHelper.createS3Client(accessKey, secretKey);
            S3OcflITest.bucket = bucket;
        } else {
            LOG.info("Running tests against S3 Mock");
            s3Client = S3ITestHelper.createMockS3Client(S3_MOCK.getServiceEndpoint());
            S3OcflITest.bucket = UUID.randomUUID().toString();
            s3Client.createBucket(request -> {
                        request.bucket(S3OcflITest.bucket);
                    })
                    .join();
        }

        transferManager = S3TransferManager.builder().s3Client(s3Client).build();

        dataSource = new ComboPooledDataSource();
        dataSource.setJdbcUrl(System.getProperty("db.url", "jdbc:h2:mem:test"));
        dataSource.setUser(System.getProperty("db.user", ""));
        dataSource.setPassword(System.getProperty("db.password", ""));
    }

    @AfterAll
    public static void afterAll() {
        s3Client.close();
        transferManager.close();
    }

    @Override
    protected void onBefore() {
        s3Helper = new S3ITestHelper(s3Client);
    }

    @Override
    protected void onAfter() {
        repoPrefixes.forEach(prefix -> {
            createCloudClient(prefix).deletePath("");
        });
    }

    // Doesn't work with mock https://github.com/adobe/S3Mock/issues/215
    @Test
    @EnabledIfEnvironmentVariable(named = ENV_ACCESS_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_SECRET_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_BUCKET, matches = ".+")
    public void listObjectsInRepo() {
        var repoName = "repo-list";
        var repo = defaultRepo(repoName);

        repo.updateObject(ObjectVersionId.head("o1"), defaultVersionInfo, updater -> {
            updater.writeFile(inputStream("test1"), "test1.txt");
        });
        repo.updateObject(ObjectVersionId.head("o2"), defaultVersionInfo, updater -> {
            updater.writeFile(inputStream("test2"), "test2.txt");
        });
        repo.updateObject(ObjectVersionId.head("o3"), defaultVersionInfo, updater -> {
            updater.writeFile(inputStream("test3"), "test3.txt");
        });

        try (var objectIdsStream = repo.listObjectIds()) {
            var objectIds = objectIdsStream.collect(Collectors.toList());
            assertThat(objectIds, containsInAnyOrder("o1", "o2", "o3"));
        }
    }

    // Doesn't work with mock https://github.com/adobe/S3Mock/issues/215
    @Test
    @EnabledIfEnvironmentVariable(named = ENV_ACCESS_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_SECRET_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_BUCKET, matches = ".+")
    public void shouldNotListObjectsWithinTheExtensionsDir() {
        var repoName = "repo-multiple-objects";
        var repoRoot = sourceRepoPath(repoName);

        var repo = existingRepo(repoName, repoRoot, builder -> {
            builder.unsupportedExtensionBehavior(UnsupportedExtensionBehavior.WARN);
        });

        try (var list = repo.listObjectIds()) {
            assertThat(list.collect(Collectors.toList()), containsInAnyOrder("o1", "o2", "o3"));
        }
    }

    // Doesn't work with mock https://github.com/adobe/S3Mock/issues/215
    @Test
    @EnabledIfEnvironmentVariable(named = ENV_ACCESS_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_SECRET_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_BUCKET, matches = ".+")
    public void shouldReturnNoValidationErrorsWhenObjectIsValid() {
        var repoName = "valid-repo";
        var repo = defaultRepo(repoName);

        var objectId = "uri:valid-object";
        var versionInfo =
                new VersionInfo().setUser("Peter", "mailto:peter@example.com").setMessage("message");

        repo.updateObject(ObjectVersionId.head(objectId), versionInfo, updater -> {
            updater.writeFile(streamString("file1"), "file1");
            updater.writeFile(streamString("file2"), "file2");
        });

        repo.updateObject(ObjectVersionId.head(objectId), versionInfo, updater -> {
            updater.writeFile(streamString("file3"), "file3").removeFile("file2");
        });

        var results = repo.validateObject(objectId, true);

        assertEquals(0, results.getErrors().size(), () -> results.getErrors().toString());
        assertEquals(
                0, results.getWarnings().size(), () -> results.getWarnings().toString());
    }

    // This test doesn't work with S3Mock because it double encodes
    @Test
    @EnabledIfEnvironmentVariable(named = ENV_ACCESS_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_SECRET_KEY, matches = ".+")
    @EnabledIfEnvironmentVariable(named = ENV_BUCKET, matches = ".+")
    public void hashedIdLayoutLongEncoded() {
        var repoName = "hashed-id-layout-2";
        var repo = defaultRepo(
                repoName, builder -> builder.defaultLayoutConfig(new HashedNTupleIdEncapsulationLayoutConfig()));

        var objectId = "۵ݨݯژښڙڜڛڝڠڱݰݣݫۯ۞ۆݰ";

        repo.updateObject(ObjectVersionId.head(objectId), defaultVersionInfo.setMessage("1"), updater -> {
            updater.writeFile(new ByteArrayInputStream("1".getBytes()), "f1")
                    .writeFile(new ByteArrayInputStream("2".getBytes()), "f2");
        });

        verifyRepo(repoName);
    }

    @Override
    protected OcflRepository defaultRepo(String name, Consumer<OcflRepositoryBuilder> consumer) {
        var builder = new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .inventoryCache(new NoOpCache<>())
                .objectLock(lock -> lock.dataSource(dataSource).tableName(lockTable()))
                .objectDetailsDb(db -> db.dataSource(dataSource).tableName(detailsTable()))
                .inventoryMapper(ITestHelper.testInventoryMapper())
                .contentPathConstraints(ContentPathConstraints.cloud())
                .storage(storage ->
                        storage.objectMapper(ITestHelper.prettyPrintMapper()).cloud(createCloudClient(name)))
                .workDir(workDir);

        consumer.accept(builder);

        var repo = builder.build();
        ITestHelper.fixTime(repo, "2019-08-05T15:57:53Z");
        return repo;
    }

    @Override
    protected OcflRepository existingRepo(String name, Path path, Consumer<OcflRepositoryBuilder> consumer) {
        var client = createCloudClient(name);
        FileUtil.findFiles(path).stream()
                .filter(f -> !f.getFileName().toString().equals(".gitkeep"))
                .forEach(file -> {
                    client.uploadFile(file, FileUtil.pathToStringStandardSeparator(path.relativize(file)));
                });
        return defaultRepo(name, consumer);
    }

    @Override
    protected void verifyRepo(String name) {
        s3Helper.verifyRepo(expectedRepoPath(name), bucket, prefix(name));
    }

    @Override
    protected List<String> listFilesInRepo(String name) {
        return s3Helper.listAllObjects(bucket, prefix(name));
    }

    private String detailsTable() {
        return "details_" + UUID.randomUUID().toString().replaceAll("-", "");
    }

    private String lockTable() {
        return "lock_" + UUID.randomUUID().toString().replaceAll("-", "");
    }

    private CloudClient createCloudClient(String name) {
        repoPrefixes.add(name);

        return OcflS3Client.builder()
                .s3Client(s3Client)
                .transferManager(transferManager)
                .bucket(bucket)
                .repoPrefix(prefix(name))
                .build();
    }

    private String prefix(String name) {
        return REPO_PREFIX + "-" + name;
    }
}
