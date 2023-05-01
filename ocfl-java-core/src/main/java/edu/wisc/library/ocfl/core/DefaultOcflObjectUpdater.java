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

package edu.wisc.library.ocfl.core;

import at.favre.lib.bytes.Bytes;
import edu.wisc.library.ocfl.api.OcflObjectUpdater;
import edu.wisc.library.ocfl.api.OcflOption;
import edu.wisc.library.ocfl.api.exception.FixityCheckException;
import edu.wisc.library.ocfl.api.exception.OcflInputException;
import edu.wisc.library.ocfl.api.io.FixityCheckInputStream;
import edu.wisc.library.ocfl.api.model.DigestAlgorithm;
import edu.wisc.library.ocfl.api.model.VersionNum;
import edu.wisc.library.ocfl.api.util.Enforce;
import edu.wisc.library.ocfl.core.inventory.AddFileProcessor;
import edu.wisc.library.ocfl.core.inventory.InventoryUpdater;
import edu.wisc.library.ocfl.core.model.Inventory;
import edu.wisc.library.ocfl.core.util.DigestUtil;
import edu.wisc.library.ocfl.core.util.FileUtil;
import edu.wisc.library.ocfl.core.util.UncheckedFiles;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of OcflObjectUpdater that is used by DefaultOcflRepository to provide write access to an object.
 */
public class DefaultOcflObjectUpdater implements OcflObjectUpdater {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultOcflObjectUpdater.class);

    private final Inventory inventory;
    private final InventoryUpdater inventoryUpdater;
    private final Path stagingDir;
    private final AddFileProcessor addFileProcessor;
    private final FileLocker fileLocker;
    private final Map<String, Path> stagedFileMap;

    public DefaultOcflObjectUpdater(
            Inventory inventory,
            InventoryUpdater inventoryUpdater,
            Path stagingDir,
            AddFileProcessor addFileProcessor,
            FileLocker fileLocker) {
        this.inventory = Enforce.notNull(inventory, "inventory cannot be null");
        this.inventoryUpdater = Enforce.notNull(inventoryUpdater, "inventoryUpdater cannot be null");
        this.stagingDir = Enforce.notNull(stagingDir, "stagingDir cannot be null");
        this.addFileProcessor = Enforce.notNull(addFileProcessor, "addFileProcessor cannot be null");
        this.fileLocker = Enforce.notNull(fileLocker, "fileLocker cannot be null");
        this.stagedFileMap = new ConcurrentHashMap<>();
    }

    @Override
    public OcflObjectUpdater addPath(Path sourcePath, OcflOption... options) {
        return addPath(sourcePath, "", options);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater addPath(Path sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notNull(sourcePath, "sourcePath cannot be null");
        Enforce.notNull(destinationPath, "destinationPath cannot be null");

        LOG.debug("Add <{}> to object <{}> at logical path <{}>", sourcePath, inventory.getId(), destinationPath);

        var newStagedFiles = addFileProcessor.processPath(sourcePath, destinationPath, options);
        stagedFileMap.putAll(newStagedFiles);

        return this;
    }

    @Override
    public OcflObjectUpdater unsafeAddPath(
            String digest, Path sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notBlank(digest, "digest cannot be blank");
        Enforce.notNull(sourcePath, "sourcePath cannot be null");
        Enforce.notNull(destinationPath, "destinationPath cannot be null");

        LOG.debug(
                "Unsafe add <{}> to object <{}> at logical path <{}> with digest <{}>",
                sourcePath,
                inventory.getId(),
                destinationPath,
                digest);

        var newStagedFiles = addFileProcessor.processFileWithDigest(digest, sourcePath, destinationPath, options);
        stagedFileMap.putAll(newStagedFiles);

        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater writeFile(InputStream input, String destinationPath, OcflOption... options) {
        Enforce.notNull(input, "input cannot be null");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        return fileLocker.withLock(destinationPath, () -> {
            LOG.debug("Write stream to object <{}> at logical path <{}>", inventory.getId(), destinationPath);

            var stagingFullPath = stagingFullPath(inventoryUpdater.innerContentPath(destinationPath));

            var digestInput = wrapInDigestInputStream(input);
            LOG.debug("Writing input stream to: {}", stagingFullPath);
            if (Files.notExists(stagingFullPath.getParent())) {
                UncheckedFiles.createDirectories(stagingFullPath.getParent());
            }
            UncheckedFiles.copy(digestInput, stagingFullPath, StandardCopyOption.REPLACE_EXISTING);

            if (input instanceof FixityCheckInputStream) {
                try {
                    ((FixityCheckInputStream) input).checkFixity();
                } catch (FixityCheckException e) {
                    FileUtil.safeDelete(stagingFullPath);
                    FileUtil.deleteDirAndParentsIfEmpty(stagingFullPath.getParent(), stagingDir);
                    throw e;
                }
            }

            String digest;

            if (digestInput instanceof FixityCheckInputStream) {
                digest = ((FixityCheckInputStream) digestInput)
                        .getActualDigestValue()
                        .get();
            } else {
                digest = Bytes.wrap(digestInput.getMessageDigest().digest()).encodeHex();
            }

            var result = inventoryUpdater.addFile(digest, destinationPath, options);

            if (!result.isNew()) {
                LOG.debug(
                        "Deleting file <{}> because a file with same digest <{}> is already present in the object",
                        stagingFullPath,
                        digest);
                UncheckedFiles.delete(stagingFullPath);
                FileUtil.deleteDirAndParentsIfEmpty(stagingFullPath.getParent(), stagingDir);
            } else {
                stagedFileMap.put(destinationPath, stagingFullPath);
            }

            return this;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater removeFile(String path) {
        Enforce.notBlank(path, "path cannot be blank");

        return fileLocker.withLock(path, () -> {
            LOG.debug("Remove <{}> from object <{}>", path, inventory.getId());

            var results = inventoryUpdater.removeFile(path);
            removeUnneededStagedFiles(results);

            return this;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater renameFile(String sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notBlank(sourcePath, "sourcePath cannot be blank");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        var lock1 = fileLocker.lock(sourcePath);
        try {
            var lock2 = fileLocker.lock(destinationPath);
            try {
                LOG.debug(
                        "Rename file in object <{}> from <{}> to <{}>", inventory.getId(), sourcePath, destinationPath);

                var results = inventoryUpdater.renameFile(sourcePath, destinationPath, options);
                removeUnneededStagedFiles(results);

                return this;
            } finally {
                lock2.unlock();
            }
        } finally {
            lock1.unlock();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater reinstateFile(
            VersionNum sourceVersionNum, String sourcePath, String destinationPath, OcflOption... options) {
        Enforce.notNull(sourceVersionNum, "sourceVersionNum cannot be null");
        Enforce.notBlank(sourcePath, "sourcePath cannot be blank");
        Enforce.notBlank(destinationPath, "destinationPath cannot be blank");

        return fileLocker.withLock(destinationPath, () -> {
            LOG.debug("Reinstate file at <{}> in object <{}> to <{}>", sourcePath, sourceVersionNum, destinationPath);

            var results = inventoryUpdater.reinstateFile(sourceVersionNum, sourcePath, destinationPath, options);
            removeUnneededStagedFiles(results);

            return this;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater clearVersionState() {
        LOG.debug("Clear current version state in object <{}>", inventory.getId());
        inventoryUpdater.clearState();
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater addFileFixity(String logicalPath, DigestAlgorithm algorithm, String value) {
        Enforce.notBlank(logicalPath, "logicalPath cannot be blank");
        Enforce.notNull(algorithm, "algorithm cannot be null");
        Enforce.notBlank(value, "value cannot be null");

        return fileLocker.withLock(logicalPath, () -> {
            LOG.debug(
                    "Add file fixity for file <{}> in object <{}>: Algorithm: {}; Value: {}",
                    logicalPath,
                    inventory.getId(),
                    algorithm.getOcflName(),
                    value);

            var digest = inventoryUpdater.getFixityDigest(logicalPath, algorithm);
            var alreadyExists = true;

            if (digest == null) {
                alreadyExists = false;

                if (!algorithm.hasJavaStandardName()) {
                    throw new OcflInputException(
                            "The specified digest algorithm is not mapped to a Java name: " + algorithm);
                }

                var file = stagedFileMap.get(logicalPath);

                if (file == null) {
                    throw new OcflInputException(String.format(
                            "%s was not newly added in this update. Fixity information can only be added on new files.",
                            logicalPath));
                }

                LOG.debug("Computing {} hash of {}", algorithm.getJavaStandardName(), file);
                digest = DigestUtil.computeDigestHex(algorithm, file);
            }

            if (!value.equalsIgnoreCase(digest)) {
                throw new FixityCheckException(String.format(
                        "Expected %s digest of %s to be %s, but was %s.",
                        algorithm.getJavaStandardName(), logicalPath, value, digest));
            }

            if (!alreadyExists) {
                inventoryUpdater.addFixity(logicalPath, algorithm, digest);
            }

            return this;
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OcflObjectUpdater clearFixityBlock() {
        LOG.info("Clear fixity block in object <{}>", inventory.getId());
        inventoryUpdater.clearFixity();
        return this;
    }

    private void removeUnneededStagedFiles(Set<InventoryUpdater.RemoveFileResult> removeFiles) {
        removeFiles.forEach(remove -> {
            var stagingPath = stagingFullPath(remove.getPathUnderContentDir());
            if (Files.exists(stagingPath)) {
                LOG.debug("Deleting {} because it was added and then removed in the same version.", stagingPath);
                UncheckedFiles.delete(stagingPath);
            }
        });
    }

    private Path stagingFullPath(String pathUnderContentDir) {
        return Paths.get(FileUtil.pathJoinFailEmpty(stagingDir.toString(), pathUnderContentDir));
    }

    private DigestInputStream wrapInDigestInputStream(InputStream input) {
        if (input instanceof DigestInputStream) {
            var digestAlgorithm = ((DigestInputStream) input).getMessageDigest().getAlgorithm();
            if (inventory.getDigestAlgorithm().getJavaStandardName().equalsIgnoreCase(digestAlgorithm)) {
                if (input instanceof FixityCheckInputStream) {
                    // Need to ensure fixity checking is enabled so that the digest is calculated
                    ((FixityCheckInputStream) input).enableFixityCheck(true);
                }
                return (DigestInputStream) input;
            }
        }

        return new DigestInputStream(input, inventory.getDigestAlgorithm().getMessageDigest());
    }
}
