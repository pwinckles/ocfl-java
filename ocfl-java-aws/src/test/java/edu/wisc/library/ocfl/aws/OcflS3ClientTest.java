package edu.wisc.library.ocfl.aws;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static software.amazon.awssdk.http.SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES;

import com.adobe.testing.s3mock.junit5.S3MockExtension;
import edu.wisc.library.ocfl.core.storage.cloud.KeyNotFoundException;
import edu.wisc.library.ocfl.core.storage.cloud.ListResult;
import edu.wisc.library.ocfl.core.util.FileUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.utils.AttributeMap;

public class OcflS3ClientTest {

    private static final Logger LOG = LoggerFactory.getLogger(OcflS3ClientTest.class);

    private static final String REPO_PREFIX =
            "OcflS3ClientTest-" + ThreadLocalRandom.current().nextLong();

    @RegisterExtension
    public static S3MockExtension S3_MOCK = S3MockExtension.builder().silent().build();

    private static S3AsyncClient awsS3Client;
    private static OcflS3Client client;
    private static String bucket;

    @TempDir
    public Path tempDir;

    @BeforeAll
    public static void beforeAll() {
        var accessKey = System.getenv().get("OCFL_TEST_AWS_ACCESS_KEY");
        var secretKey = System.getenv().get("OCFL_TEST_AWS_SECRET_KEY");
        var bucket = System.getenv().get("OCFL_TEST_S3_BUCKET");

        if (StringUtils.isNotBlank(accessKey) && StringUtils.isNotBlank(secretKey) && StringUtils.isNotBlank(bucket)) {
            LOG.info("Running tests against AWS");
            awsS3Client = S3AsyncClient.crtBuilder()
                    .region(Region.US_EAST_2)
                    .credentialsProvider(
                            StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                    .build();
            OcflS3ClientTest.bucket = bucket;
        } else {
            LOG.info("Running tests against S3 Mock");
            awsS3Client = S3AsyncClient.builder()
                    .endpointOverride(URI.create(S3_MOCK.getServiceEndpoint()))
                    .region(Region.US_EAST_2)
                    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .build())
                    .httpClient(NettyNioAsyncHttpClient.builder()
                            .buildWithDefaults(AttributeMap.builder()
                                    .put(TRUST_ALL_CERTIFICATES, Boolean.TRUE)
                                    .build()))
                    .build();
            ;
            OcflS3ClientTest.bucket = UUID.randomUUID().toString();
            awsS3Client
                    .createBucket(request -> {
                        request.bucket(OcflS3ClientTest.bucket);
                    })
                    .join();
        }

        client = OcflS3Client.builder()
                .s3Client(awsS3Client)
                .bucket(OcflS3ClientTest.bucket)
                .repoPrefix(REPO_PREFIX)
                .build();
    }

    @AfterAll
    public static void afterAll() {
        awsS3Client.close();
        client.close();
    }

    @AfterEach
    public void after() {
        client.deletePath("");
    }

    @Test
    public void basicPutAndGet() {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        assertObjectsExist(bucket, List.of(key));

        assertEquals("content", client.downloadString(key));
    }

    @Test
    public void putObjectWithModification() throws IOException {
        var client = OcflS3Client.builder()
                .s3Client(awsS3Client)
                .bucket(bucket)
                .repoPrefix(REPO_PREFIX)
                .putObjectModifier((key, builder) -> {
                    if (key.endsWith("/test.txt")) {
                        builder.contentType("text/plain");
                    }
                })
                .build();

        var key1 = "dir/sub/test.txt";
        var key2 = "dir/sub/test.json";

        client.uploadFile(createFile("content"), key1);
        client.uploadFile(createFile("content2"), key2);

        assertObjectsExist(bucket, List.of(key1, key2));

        try (var response = awsS3Client
                .getObject(
                        builder -> builder.bucket(bucket)
                                .key(FileUtil.pathJoinIgnoreEmpty(REPO_PREFIX, key1))
                                .build(),
                        AsyncResponseTransformer.toBlockingInputStream())
                .join()) {
            assertEquals("text/plain", response.response().contentType());
        }
        try (var response = awsS3Client
                .getObject(
                        builder -> builder.bucket(bucket)
                                .key(FileUtil.pathJoinIgnoreEmpty(REPO_PREFIX, key2))
                                .build(),
                        AsyncResponseTransformer.toBlockingInputStream())
                .join()) {
            assertEquals("application/octet-stream", response.response().contentType());
        }
    }

    @Test
    public void basicDownloadFileWhenExists() throws IOException {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        var out = tempDir.resolve("test.txt");
        client.downloadFile(key, out);

        assertEquals("content", Files.readString(out));
    }

    @Test
    public void failDownloadFileWhenKeyDoesNotExist() {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        var out = tempDir.resolve("test.txt");

        assertThrows(KeyNotFoundException.class, () -> {
            client.downloadFile("bogus", out);
        });
    }

    @Test
    public void failDownloadStringWhenKeyDoesNotExist() {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        assertThrows(KeyNotFoundException.class, () -> {
            client.downloadString("bogus");
        });
    }

    @Test
    public void putWithContentType() {
        var key = "blah/blah/blah.txt";
        var content = "yawn";

        client.uploadFile(createFile(content), key, "text/plain; charset=utf-8");

        assertObjectsExist(bucket, List.of(key));

        assertEquals(content, client.downloadString(key));
    }

    @Test
    public void copyObjectWhenExists() {
        var src = "dir/file1.txt";
        var dst = "file1.txt";
        var content = "something";

        client.uploadFile(createFile(content), src);
        client.copyObject(src, dst);

        assertObjectsExist(bucket, List.of(src, dst));

        assertEquals(content, client.downloadString(dst));
    }

    @Test
    public void failCopyWhenSrcDoesNotExist() {
        var src = "dir/file1.txt";
        var dst = "file1.txt";
        var content = "something";

        client.uploadFile(createFile(content), src);

        assertThrows(RuntimeException.class, () -> {
            client.copyObject(src + "asdf", dst);
        });
    }

    @Test
    public void shouldListKeysInDirectory() {
        client.uploadBytes("f1", bytes("1"), null);
        client.uploadBytes("f2", bytes("2"), null);
        client.uploadBytes("d1/f3", bytes("3"), null);
        client.uploadBytes("d1/f4", bytes("4"), null);
        client.uploadBytes("d1/d2/f5", bytes("5"), null);
        client.uploadBytes("d1/d2/f6", bytes("6"), null);

        var result = client.listDirectory("d1");

        assertEquals(2, result.getObjects().size());
        assertObjectListingDir("d1/f3", result.getObjects().get(0));
        assertObjectListingDir("d1/f4", result.getObjects().get(1));

        assertEquals(1, result.getDirectories().size());
        assertEquals("d1/d2/", result.getDirectories().get(0).getPath());
        assertEquals("d2", result.getDirectories().get(0).getName());

        result = client.listDirectory("");

        assertEquals(2, result.getObjects().size());
        assertObjectListingDir("f1", result.getObjects().get(0));
        assertObjectListingDir("f2", result.getObjects().get(1));

        assertEquals(1, result.getDirectories().size());
        assertEquals("d1/", result.getDirectories().get(0).getPath());
        assertEquals("d1", result.getDirectories().get(0).getName());
    }

    @Test
    public void shouldListAllKeysUnderPrefix() {
        client.uploadBytes("f1", bytes("1"), null);
        client.uploadBytes("f2", bytes("2"), null);
        client.uploadBytes("d1/f3", bytes("3"), null);
        client.uploadBytes("d1/f4", bytes("4"), null);
        client.uploadBytes("d1/d2/f5", bytes("5"), null);
        client.uploadBytes("d1/d2/f6", bytes("6"), null);

        var result = client.list("d1");

        assertEquals(4, result.getObjects().size());
        assertObjectListingAll("d1", "d1/d2/f5", result.getObjects().get(0));
        assertObjectListingAll("d1", "d1/d2/f6", result.getObjects().get(1));
        assertObjectListingAll("d1", "d1/f3", result.getObjects().get(2));
        assertObjectListingAll("d1", "d1/f4", result.getObjects().get(3));

        assertEquals(0, result.getDirectories().size());
    }

    @Test
    public void directoryExistsWhenContainsObjects() {
        client.uploadBytes("f1", bytes("1"), null);
        client.uploadBytes("f2", bytes("2"), null);
        client.uploadBytes("d1/f3", bytes("3"), null);
        client.uploadBytes("d1/f4", bytes("4"), null);
        client.uploadBytes("d1/d2/f5", bytes("5"), null);
        client.uploadBytes("d1/d2/f6", bytes("6"), null);
        client.uploadBytes("d1/d3/d4/f7", bytes("7"), null);

        assertTrue(client.directoryExists("d1"));
        assertTrue(client.directoryExists("d1/d3"));
        assertFalse(client.directoryExists("d5"));
    }

    @Test
    public void shouldReturnWhenBucketExists() {
        assertTrue(client.bucketExists());
    }

    @Test
    public void shouldDeleteAllObjectsUnderPrefix() {
        client.uploadBytes("f1", bytes("1"), null);
        client.uploadBytes("f2", bytes("2"), null);
        client.uploadBytes("d1/f3", bytes("3"), null);
        client.uploadBytes("d1/f4", bytes("4"), null);
        client.uploadBytes("d1/d2/f5", bytes("5"), null);
        client.uploadBytes("d1/d2/f6", bytes("6"), null);

        client.deletePath("d1");

        assertObjectsExist(bucket, List.of("f1", "f2"));
    }

    @Test
    public void shouldSafeDeleteAllObjectsUnderPrefix() {
        client.uploadBytes("f1", bytes("1"), null);
        client.uploadBytes("f2", bytes("2"), null);
        client.uploadBytes("d1/f3", bytes("3"), null);
        client.uploadBytes("d1/f4", bytes("4"), null);
        client.uploadBytes("d1/d2/f5", bytes("5"), null);
        client.uploadBytes("d1/d2/f6", bytes("6"), null);

        client.safeDeleteObjects("d1/f3", "d1/d2/f6");

        assertObjectsExist(bucket, List.of("f1", "f2", "d1/f4", "d1/d2/f5"));
    }

    @Test
    public void headWhenExists() {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        var result = client.head(key);

        assertEquals(7, result.getContentLength());
        assertEquals("\"9a0364b9e99bb480dd25e1f0284c8555\"", result.getETag());
        assertNotNull(result.getLastModified());
    }

    @Test
    public void failHeadWhenDoesNotExist() {
        var key = "dir/sub/test.txt";

        client.uploadFile(createFile("content"), key);

        assertThrows(KeyNotFoundException.class, () -> {
            client.head("bogus");
        });
    }

    private Path createFile(String content) {
        try {
            return Files.writeString(
                    tempDir.resolve("temp-file-" + ThreadLocalRandom.current().nextLong()), content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private void assertObjectListingDir(String key, ListResult.ObjectListing actual) {
        assertEquals(key, actual.getKey().getPath());
        assertEquals(
                FileUtil.pathJoinIgnoreEmpty(REPO_PREFIX, key), actual.getKey().getKey());
        assertEquals(key.substring(key.lastIndexOf('/') + 1), actual.getKeySuffix());
    }

    private void assertObjectListingAll(String searchPrefix, String key, ListResult.ObjectListing actual) {
        assertEquals(key, actual.getKey().getPath());
        assertEquals(
                FileUtil.pathJoinIgnoreEmpty(REPO_PREFIX, key), actual.getKey().getKey());
        assertEquals(key.substring(searchPrefix.length() + 1), actual.getKeySuffix());
    }

    private void assertObjectsExist(String bucket, Collection<String> expectedKeys) {
        var result = awsS3Client
                .listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(REPO_PREFIX)
                        .build())
                .join();

        var actualKeys = result.contents().stream().map(S3Object::key).collect(Collectors.toList());
        var prefixedExpected = expectedKeys.stream()
                .map(k -> FileUtil.pathJoinIgnoreEmpty(REPO_PREFIX, k))
                .collect(Collectors.toList());

        assertThat(actualKeys, containsInAnyOrder(prefixedExpected.toArray(String[]::new)));
    }
}
