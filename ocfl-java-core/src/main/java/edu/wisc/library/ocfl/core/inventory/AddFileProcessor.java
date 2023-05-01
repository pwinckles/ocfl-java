/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 University of Wisconsin Board of Regents
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package edu.wisc.library.ocfl.core.inventory;

import at.favre.lib.bytes.Bytes;
import edu.wisc.library.ocfl.api.OcflOption;
import edu.wisc.library.ocfl.api.exception.OcflIOException;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.FileLocker;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates logic for adding files to an object, both adding them to the inventory and moving them into staging.
 */
public class AddFileProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(AddFileProcessor.class);

    private final InventoryUpdater inventoryUpdater;
    private final FileLocker fileLocker;
    private final Path stagingDir;
    private final DigestAlgorithm digestAlgorithm;

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        public AddFileProcessor build(
                InventoryUpdater inventoryUpdater,
                FileLocker fileLocker,
                Path stagingDir,
                DigestAlgorithm digestAlgorithm) {
            return new AddFileProcessor(inventoryUpdater, fileLocker, stagingDir, digestAlgorithm);
        }
    }

    /**
     * @see Builder
     *
     * @param inventoryUpdater the inventory updater
     * @param stagingDir the staging directory to move files into
     * @param digestAlgorithm the digest algorithm
     */
    public AddFileProcessor(
            InventoryUpdater inventoryUpdater,
            FileLocker fileLocker,
            Path stagingDir,
            DigestAlgorithm digestAlgorithm) {
        this.inventoryUpdater = Enforce.notNull(inventoryUpdater, "inventoryUpdater cannot be null");
        this.fileLocker = Enforce.notNull(fileLocker, "fileLocker cannot be null");
        this.stagingDir = Enforce.notNull(stagingDir, "stagingDir cannot be null");
        this.digestAlgorithm = Enforce.notNull(digestAlgorithm, "digestAlgorithm cannot be null");
    }

    /**
     * Adds all of the files at or under the sourcePath to the root of the object.
     *
     * @param sourcePath the file or directory to add
     * @param ocflOptions options for how to move the files
     * @return a map of logical paths to their corresponding file within the stagingDir for newly added files
     */
    public Map<String, Path> processPath(Path sourcePath, OcflOption... ocflOptions) {
        return processPath(sourcePath, "", ocflOptions);
    }

    /**
     * Adds all of the files at or under the sourcePath to the object at the specified destinationPath.
     *
     * @param sourcePath the file or directory to add
     * @param destinationPath the location to insert the file or directory at within the object
     * @param options options for how to move the files
     * @return a map of logical paths to their corresponding file within the stagingDir for newly added files
     */
    public Map<String, Path> processPath(Path sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notNull(sourcePath, "sourcePath cannot be null");
        Enforce.notNull(destinationPath, "destinationPath cannot be null");

        var results = new HashMap<String, Path>();
        var optionsSet = OcflOption.toSet(options);
        var isMove = optionsSet.contains(OcflOption.MOVE_SOURCE);
        var destination = destinationPath(destinationPath, sourcePath);
        var messageDigest = digestAlgorithm.getMessageDigest();
        var locks = new ArrayList<ReentrantLock>();

        try (var paths = Files.find(
                sourcePath, Integer.MAX_VALUE, (file, attrs) -> attrs.isRegularFile(), FileVisitOption.FOLLOW_LINKS)) {
            for (var it = paths.iterator(); it.hasNext(); ) {
                var file = it.next();
                messageDigest.reset();
                var logicalPath = logicalPath(sourcePath, file, destination);
                locks.add(fileLocker.lock(logicalPath));

                if (isMove) {
                    var digest = DigestUtil.computeDigestHex(messageDigest, file);
                    var result = inventoryUpdater.addFile(digest, logicalPath, options);

                    if (result.isNew()) {
                        var stagingFullPath = stagingFullPath(result.getPathUnderContentDir());

                        results.put(logicalPath, stagingFullPath);

                        LOG.debug("Moving file <{}> to <{}>", file, stagingFullPath);
                        FileUtil.moveFileMakeParents(file, stagingFullPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else {
                    var stagingFullPath = stagingFullPath(inventoryUpdater.innerContentPath(logicalPath));

                    if (Files.notExists(stagingFullPath.getParent())) {
                        UncheckedFiles.createDirectories(stagingFullPath.getParent());
                    }

                    String digest;
                    InventoryUpdater.AddFileResult result;

                    try (var stream = new DigestOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(
                                    stagingFullPath,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.WRITE,
                                    StandardOpenOption.TRUNCATE_EXISTING)),
                            messageDigest)) {
                        LOG.debug("Copying file <{}> to <{}>", file, stagingFullPath);
                        Files.copy(file, stream);

                        digest = Bytes.wrap(stream.getMessageDigest().digest()).encodeHex();
                        result = inventoryUpdater.addFile(digest, logicalPath, options);
                    } catch (IOException e) {
                        throw new OcflIOException(e);
                    }

                    if (result.isNew()) {
                        results.put(logicalPath, stagingFullPath);
                    } else {
                        LOG.debug(
                                "Deleting file <{}> because a file with same digest <{}> is already present in the object",
                                stagingFullPath,
                                digest);
                        UncheckedFiles.delete(stagingFullPath);
                        FileUtil.deleteDirAndParentsIfEmpty(stagingFullPath.getParent(), stagingDir);
                    }
                }
            }
        } catch (IOException e) {
            throw new OcflIOException(e);
        } finally {
            locks.forEach(ReentrantLock::unlock);
        }

        if (isMove) {
            // Cleanup empty dirs
            FileUtil.safeDeleteDirectory(sourcePath);
        }

        return results;
    }

    /**
     * Adds the file at sourcePath to the object at the specified destinationPath. The provided digest is trusted to
     * be accurate. If it is not, or is the wrong algorithm, then the object will be corrupted.
     *
     * @param digest the digest of the file. MUST use the same algorithm as the object's content digest algorithm
     * @param sourcePath the file or directory to add
     * @param destinationPath the location to insert the file or directory at within the object
     * @param options options for how to move the files
     * @return a map of logical paths to their corresponding file within the stagingDir for newly added files
     */
    public Map<String, Path> processFileWithDigest(
            String digest, Path sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notBlank(digest, "digest cannot be blank");
        Enforce.notNull(sourcePath, "sourcePath cannot be null");
        Enforce.notNull(destinationPath, "destinationPath cannot be null");

        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalStateException(String.format("%s must be a regular file", sourcePath));
        }

        var results = new HashMap<String, Path>();
        var optionsSet = OcflOption.toSet(options);
        var destination = destinationPath(destinationPath, sourcePath);

        var logicalPath = logicalPath(sourcePath, sourcePath, destination);

        return fileLocker.withLock(logicalPath, () -> {
            var result = inventoryUpdater.addFile(digest, logicalPath, options);

            if (result.isNew()) {
                var stagingFullPath = stagingFullPath(result.getPathUnderContentDir());

                results.put(logicalPath, stagingFullPath);

                if (optionsSet.contains(OcflOption.MOVE_SOURCE)) {
                    LOG.debug("Moving file <{}> to <{}>", sourcePath, stagingFullPath);
                    FileUtil.moveFileMakeParents(sourcePath, stagingFullPath, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    LOG.debug("Copying file <{}> to <{}>", sourcePath, stagingFullPath);
                    FileUtil.copyFileMakeParents(sourcePath, stagingFullPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            return results;
        });
    }

    private String destinationPath(String path, Path sourcePath) {
        if (path.isBlank() && Files.isRegularFile(sourcePath)) {
            return sourcePath.getFileName().toString();
        }
        return path;
    }

    private String logicalPath(Path sourcePath, Path sourceFile, String destinationPath) {
        var sourceRelative = FileUtil.pathToStringStandardSeparator(sourcePath.relativize(sourceFile));
        return FileUtil.pathJoinIgnoreEmpty(destinationPath, sourceRelative);
    }

    private Path stagingFullPath(String pathUnderContentDir) {
        return Paths.get(FileUtil.pathJoinFailEmpty(stagingDir.toString(), pathUnderContentDir));
    }
}
