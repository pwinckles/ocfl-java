package edu.wisc.library.ocfl.itest.s3;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import edu.wisc.library.ocfl.api.MutableOcflRepository;
import edu.wisc.library.ocfl.aws.OcflS3Client;
import edu.wisc.library.ocfl.core.OcflRepositoryBuilder;
import edu.wisc.library.ocfl.core.cache.NoOpCache;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import edu.wisc.library.ocfl.core.path.constraint.ContentPathConstraints;
import edu.wisc.library.ocfl.core.storage.cloud.CloudClient;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.itest.BadReposITest;
import edu.wisc.library.ocfl.itest.ITestHelper;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

public class S3BadReposITest extends BadReposITest {

    private static final Logger LOG = LoggerFactory.getLogger(S3BadReposITest.class);

    private static final String REPO_PREFIX =
            "S3BadReposITest" + ThreadLocalRandom.current().nextLong();

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
        var accessKey = System.getenv().get("OCFL_TEST_AWS_ACCESS_KEY");
        var secretKey = System.getenv().get("OCFL_TEST_AWS_SECRET_KEY");
        var bucket = System.getenv().get("OCFL_TEST_S3_BUCKET");

        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey) && StringUtils.isNotBlank(bucket)) {
            LOG.info("Running tests against AWS");
            s3Client = S3ITestHelper.createS3Client(accessKey, secretKey);
            S3BadReposITest.bucket = bucket;
        } else {
            LOG.info("Running tests against S3 Mock");
            s3Client = S3ITestHelper.createMockS3Client(S3_MOCK.getServiceEndpoint());
            S3BadReposITest.bucket = UUID.randomUUID().toString();
            s3Client.createBucket(request -> {
                        request.bucket(S3BadReposITest.bucket);
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

    @Override
    protected MutableOcflRepository defaultRepo(String name) {
        copyFiles(name);
        var repo = new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .inventoryCache(new NoOpCache<>())
                .objectLock(lock -> lock.dataSource(dataSource).tableName(lockTable()))
                .objectDetailsDb(db -> db.dataSource(dataSource).tableName(detailsTable()))
                .inventoryMapper(ITestHelper.testInventoryMapper())
                .contentPathConstraints(ContentPathConstraints.cloud())
                .storage(storage ->
                        storage.objectMapper(ITestHelper.prettyPrintMapper()).cloud(createCloudClient(name)))
                .workDir(workDir)
                .buildMutable();
        ITestHelper.fixTime(repo, "2019-08-05T15:57:53Z");
        return repo;
    }

    private void copyFiles(String name) {
        var repoDir = repoDir(name);
        var client = createCloudClient(name);
        FileUtil.findFiles(repoDir).stream()
                .filter(f -> !f.getFileName().toString().equals(".gitkeep"))
                .forEach(file -> {
                    client.uploadFile(file, FileUtil.pathToStringStandardSeparator(repoDir.relativize(file)));
                });
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
