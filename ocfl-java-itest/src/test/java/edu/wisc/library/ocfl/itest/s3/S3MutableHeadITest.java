package edu.wisc.library.ocfl.itest.s3;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import com.mchange.v2.c3p0.ComboPooledDataSource;
import edu.wisc.library.ocfl.api.MutableOcflRepository;
import edu.wisc.library.ocfl.aws.OcflS3Client;
import edu.wisc.library.ocfl.core.OcflRepositoryBuilder;
import edu.wisc.library.ocfl.core.cache.NoOpCache;
import edu.wisc.library.ocfl.core.extension.storage.layout.config.HashedTruncatedNTupleConfig;
import edu.wisc.library.ocfl.core.path.constraint.ContentPathConstraints;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.itest.ITestHelper;
import edu.wisc.library.ocfl.itest.MutableHeadITest;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import static edu.wisc.library.ocfl.itest.ITestHelper.expectedRepoPath;

public class S3MutableHeadITest extends MutableHeadITest {

    @RegisterExtension
    public static S3MockExtension S3_MOCK = S3MockExtension.builder().silent().build();

    private final S3Client s3Client = S3_MOCK.createS3ClientV2();

    private S3ITestHelper s3Helper;
    private ComboPooledDataSource dataSource;
    private Set<String> createdBuckets = new HashSet<>();

    @Override
    protected void onBefore() {
        s3Helper = new S3ITestHelper(s3Client);
        dataSource = new ComboPooledDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:test");
    }

    @Override
    protected void onAfter() {
        truncateObjectDetails(dataSource);
        deleteBuckets();
    }

    @Override
    protected MutableOcflRepository defaultRepo(String name, Consumer<OcflRepositoryBuilder> consumer) {
        createBucket(name);
        var repo = new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedTruncatedNTupleConfig())
                .inventoryCache(new NoOpCache<>())
                .objectLock(lock -> lock.dataSource(dataSource))
                .objectDetailsDb(db -> db.dataSource(dataSource))
                .inventoryMapper(ITestHelper.testInventoryMapper())
                .contentPathConstraints(ContentPathConstraints.cloud())
                .cloudStorage(storage -> storage
                        .objectMapper(ITestHelper.prettyPrintMapper())
                        .cloudClient(new OcflS3Client(s3Client, name))
                        .build())
                .workDir(workDir)
                .buildMutable();
        ITestHelper.fixTime(repo, "2019-08-05T15:57:53Z");
        return repo;
    }

    @Override
    protected MutableOcflRepository existingRepo(String name, Path path, Consumer<OcflRepositoryBuilder> consumer) {
        createBucket(name);
        FileUtil.findFiles(path).forEach(file -> {
            s3Client.putObject(PutObjectRequest.builder()
                    .bucket(name)
                    .key(FileUtil.pathToStringStandardSeparator(path.relativize(file)))
                    .build(), file);
        });
        return defaultRepo(name, consumer);
    }

    @Override
    protected void verifyRepo(String name) {
        s3Helper.verifyRepo(expectedRepoPath(name), name);
    }

    @Override
    protected void writeFile(String repoName, String path, InputStream content) {
        try {
            s3Client.putObject(PutObjectRequest.builder()
                    .key(path)
                    .bucket(repoName)
                    .build(), RequestBody.fromBytes(content.readAllBytes()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void truncateObjectDetails(DataSource dataSource) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("TRUNCATE TABLE ocfl_object_details")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void createBucket(String name) {
        s3Client.createBucket(CreateBucketRequest.builder().bucket(name).build());
        createdBuckets.add(name);
    }

    private void deleteBuckets() {
        createdBuckets.forEach(bucket -> {
            s3Helper.deleteBucket(bucket);
        });
    }

}
