package edu.wisc.library.ocfl.itest.s3;

import static edu.wisc.library.ocfl.itest.ITestHelper.computeDigest;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static software.amazon.awssdk.http.SdkHttpConfigurationOption.TRUST_ALL_CERTIFICATES;

import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.utils.AttributeMap;

public class S3ITestHelper {

    private static final String OCFL_SPEC_FILE = "ocfl_1.1.md";

    private S3AsyncClient s3Client;

    public S3ITestHelper(S3AsyncClient s3Client) {
        this.s3Client = s3Client;
    }

    public static S3AsyncClient createS3Client(String accessKey, String secretKey) {
        return S3AsyncClient.crtBuilder()
                .region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }

    public static S3AsyncClient createMockS3Client(String endpoint) {
        return S3AsyncClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.US_EAST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
                .serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .httpClient(NettyNioAsyncHttpClient.builder()
                        .buildWithDefaults(AttributeMap.builder()
                                .put(TRUST_ALL_CERTIFICATES, Boolean.TRUE)
                                .build()))
                .build();
    }

    public void verifyRepo(Path expected, String bucket, String prefix) {
        var expectedPaths = listAllFiles(expected);
        var actualObjects = listAllObjects(bucket, prefix);

        assertThat(actualObjects, containsInAnyOrder(expectedPaths.toArray()));

        expectedPaths.forEach(path -> {
            var expectedFile = expected.resolve(path);
            assertEquals(
                    computeDigest(expectedFile),
                    computeS3Digest(bucket, prefix, path),
                    () -> String.format(
                            "Comparing %s to object in bucket %s: \n\n%s",
                            expectedFile, bucket, new String(getObjectContent(bucket, prefix, path))));
        });
    }

    private List<String> listAllFiles(Path root) {
        var paths = new ArrayList<String>();

        try (var walk = Files.walk(root)) {
            walk.filter(p -> {
                        var pStr = p.toString();
                        return !pStr.contains(".gitkeep")
                                && !pStr.equals(OCFL_SPEC_FILE)
                                && !pStr.equals("ocfl_1.0.txt");
                    })
                    .filter(Files::isRegularFile)
                    .forEach(p -> {
                        paths.add(FileUtil.pathToStringStandardSeparator(root.relativize(p)));
                    });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return paths;
    }

    private byte[] getObjectContent(String bucket, String prefix, String key) {
        return s3Client.getObject(
                        GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(prefix + "/" + key)
                                .build(),
                        AsyncResponseTransformer.toBytes())
                .join()
                .asByteArray();
    }

    private String computeS3Digest(String bucket, String prefix, String key) {
        return DigestUtil.computeDigestHex(DigestAlgorithm.md5, getObjectContent(bucket, prefix, key));
    }

    public List<String> listAllObjects(String bucket, String prefix) {
        var result = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .build())
                .join();

        return result.contents().stream()
                .map(S3Object::key)
                .map(k -> k.substring(prefix.length() + 1))
                .filter(entry -> {
                    return !entry.equals(OCFL_SPEC_FILE) && !entry.equals("ocfl_1.0.txt");
                })
                .collect(Collectors.toList());
    }
}
